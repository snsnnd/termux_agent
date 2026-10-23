"""OpenAI-compatible streaming client.

Why streaming fixes the read-timeout problem:
- Non-streaming: the server must finish the ENTIRE reply within `timeout`.
  Long tool-using turns easily take >120s -> httpx.ReadTimeout.
- Streaming (SSE): the timeout applies to the gap BETWEEN chunks, so the
  connection stays alive as long as tokens keep flowing, and the user sees
  output immediately instead of a blank screen.

On top of that: exponential-backoff retries for transient failures
(connect errors, read timeouts, 429/5xx), which matter a lot on mobile
networks where Termux can be suspended mid-request.
"""

import asyncio
import json
import random

import httpx

RETRY_STATUS = {408, 409, 425, 429, 500, 502, 503, 504, 529}


class LLMError(Exception):
    def __init__(self, message, status_code=None):
        super().__init__(message)
        self.status_code = status_code


class LLM:
    def __init__(self, config):
        self.base_url = config["base_url"].rstrip("/")
        self.model = config["model"]
        self.api_key = config["api_key"]
        self.temperature = config.get("temperature", 0.6)
        # seconds allowed between two SSE chunks (covers time-to-first-token)
        self.read_timeout = float(config.get("read_timeout", 120))
        self.connect_timeout = float(config.get("connect_timeout", 15))
        self.max_retries = int(config.get("max_retries", 3))

    async def chat(self, messages, tools=None, on_delta=None, on_think=None,
                   on_status=None):
        """Stream a chat completion; return the assembled assistant message.

        on_delta(text)  - called for each piece of visible content
        on_think(text)  - called for each piece of reasoning content (dim text)
        on_status(text) - called when waiting/retrying ("Thinking", "Reconnecting 1/3")
        """
        body = {
            "model": self.model,
            "messages": messages,
            "temperature": self.temperature,
            "stream": True,
        }
        if tools:
            body["tools"] = tools
            body["tool_choice"] = "auto"

        attempt = 0
        while True:
            try:
                return await self._stream(body, on_delta, on_think)
            except Exception as e:
                attempt += 1
                if attempt > self.max_retries or not self._retryable(e):
                    raise
                delay = min(2 ** attempt, 8) + random.uniform(0, 0.5)
                if on_status:
                    on_status(f"Reconnecting {attempt}/{self.max_retries}"
                              f" in {delay:.0f}s")
                await asyncio.sleep(delay)
                if on_status:
                    on_status("Thinking")

    @staticmethod
    def _retryable(exc):
        if isinstance(exc, (httpx.ConnectError, httpx.ConnectTimeout,
                            httpx.ReadError, httpx.ReadTimeout,
                            httpx.WriteError, httpx.RemoteProtocolError,
                            httpx.PoolTimeout)):
            return True
        if isinstance(exc, LLMError):
            return exc.status_code in RETRY_STATUS
        return False

    def _headers(self):
        return {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        }

    async def _stream(self, body, on_delta, on_think):
        timeout = httpx.Timeout(
            connect=self.connect_timeout,
            read=self.read_timeout,
            write=self.connect_timeout,
            pool=self.connect_timeout,
        )

        content = ""
        tool_map = {}

        async with httpx.AsyncClient(timeout=timeout, trust_env=True) as client:
            async with client.stream(
                "POST",
                f"{self.base_url}/chat/completions",
                headers=self._headers(),
                json=body,
            ) as resp:
                if resp.status_code != 200:
                    raw = (await resp.aread()).decode("utf-8", "replace")
                    raise LLMError(
                        f"HTTP {resp.status_code}: {raw[:400]}",
                        status_code=resp.status_code,
                    )

                async for line in resp.aiter_lines():
                    line = line.strip()
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if data == "[DONE]":
                        break

                    try:
                        chunk = json.loads(data)
                    except ValueError:
                        continue

                    if chunk.get("error"):
                        raise LLMError(str(chunk["error"])[:400])

                    choices = chunk.get("choices") or []
                    if not choices:
                        continue
                    delta = choices[0].get("delta") or {}

                    text = delta.get("content")
                    if text:
                        content += text
                        if on_delta:
                            on_delta(text)

                    think = delta.get("reasoning_content") or delta.get("reasoning")
                    if think and on_think:
                        on_think(think)

                    for tc in delta.get("tool_calls") or []:
                        slot = tool_map.setdefault(
                            tc.get("index", 0),
                            {"id": "", "name": "", "arguments": ""},
                        )
                        if tc.get("id"):
                            slot["id"] = tc["id"]
                        fn = tc.get("function") or {}
                        if fn.get("name"):
                            slot["name"] = fn["name"]
                        if fn.get("arguments"):
                            slot["arguments"] += fn["arguments"]

        message = {"role": "assistant", "content": content}
        if tool_map:
            message["tool_calls"] = [
                {
                    "id": tool_map[i]["id"] or f"call_{i}",
                    "type": "function",
                    "function": {
                        "name": tool_map[i]["name"],
                        "arguments": tool_map[i]["arguments"] or "{}",
                    },
                }
                for i in sorted(tool_map)
            ]
            if not content:
                # some providers reject content:"" alongside tool_calls
                message["content"] = None
        return message
