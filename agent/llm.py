"""OpenAI-compatible streaming client.

Why streaming fixes the read-timeout problem:
- Non-streaming: the server must finish the ENTIRE reply within `timeout`.
  Long tool-using turns easily take >120s -> httpx.ReadTimeout.
- Streaming (SSE): the timeout applies to the gap BETWEEN chunks, so the
  connection stays alive as long as tokens keep flowing, and the user sees
  output immediately instead of a blank screen.

On top of that:
- exponential-backoff retries for transient failures BEFORE any output was
  shown (connect errors, read timeouts, 429/5xx) -- mobile networks where
  Termux runs break often;
- if the stream dies AFTER output was shown, we do NOT retry (that would
  duplicate text on screen); instead a partial assistant message is
  attached to the raised LLMError and the agent finishes the turn with it;
- usage accounting (`stream_options.include_usage`, auto-disabled if the
  provider rejects it) feeds token accounting + estimator calibration;
- debug_log records requests/responses/status codes for later diagnosis.
"""

import asyncio
import json
import random
import time
from pathlib import Path

import httpx

RETRY_STATUS = {408, 409, 425, 429, 500, 502, 503, 504, 529}


class LLMError(Exception):
    def __init__(self, message, status_code=None, partial=None,
                 retryable=None):
        super().__init__(message)
        self.status_code = status_code
        # partial assistant message if the stream broke mid-output
        self.partial = partial
        # force override of the retry decision when not None
        self.retryable = retryable


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
        self.stream_usage = bool(config.get("stream_usage", True))
        self.debug_path = config.get("debug_log")

        self._tried_no_so = False   # one retry without stream_options on 400

    # ------------------------------------------------------------ logging
    def _log(self, msg):
        if not self.debug_path:
            return
        try:
            p = Path(self.debug_path)
            p.parent.mkdir(parents=True, exist_ok=True)
            with open(p, "a", encoding="utf-8") as f:
                f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}\n")
        except OSError:
            pass

    # -------------------------------------------------------------- chat
    async def chat(self, messages, tools=None, on_delta=None, on_think=None,
                   on_status=None, on_usage=None):
        """Stream a chat completion; return the assembled assistant message.

        on_delta(text)  - each piece of visible content
        on_think(text)  - each piece of reasoning content (dim text)
        on_status(text) - waiting/retrying indicators
        on_usage(dict)  - token usage of this call (when the provider sends it)
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
        if self.stream_usage:
            body["stream_options"] = {"include_usage": True}

        self._log(
            f">>> model={self.model} msgs={len(messages)} "
            f"tools={len(tools) if tools else 0}"
        )

        attempt = 0
        while True:
            try:
                message = await self._stream(body, on_delta, on_think, on_usage)
                self._log(
                    f"<<< ok content={len(message.get('content') or '')} "
                    f"tools={[c['function']['name'] for c in message.get('tool_calls') or []]}"
                )
                return message
            except LLMError as e:
                # provider rejected stream_options -> retry once without it
                if (e.status_code == 400 and self.stream_usage
                        and not self._tried_no_so):
                    self._tried_no_so = True
                    self.stream_usage = False
                    body.pop("stream_options", None)
                    self._log("400 mentioning stream_options; retrying without it")
                    continue
                if not self._retryable(e) or attempt >= self.max_retries:
                    self._log(f"!!! giving up: {e}")
                    raise
                delay = self._backoff(attempt)
                attempt += 1
                if on_status:
                    on_status(f"Reconnecting {attempt}/{self.max_retries}"
                              f" in {delay:.0f}s")
                self._log(f"retry {attempt} in {delay:.1f}s: {e}")
                await asyncio.sleep(delay)
                if on_status:
                    on_status("Thinking")
            except (httpx.ConnectError, httpx.ConnectTimeout,
                    httpx.ReadError, httpx.ReadTimeout,
                    httpx.WriteError, httpx.RemoteProtocolError,
                    httpx.PoolTimeout) as e:
                if attempt >= self.max_retries:
                    self._log(f"!!! giving up: {type(e).__name__}: {e}")
                    raise
                delay = self._backoff(attempt)
                attempt += 1
                if on_status:
                    on_status(f"Reconnecting {attempt}/{self.max_retries}"
                              f" in {delay:.0f}s")
                self._log(f"retry {attempt} in {delay:.1f}s: "
                          f"{type(e).__name__}: {e}")
                await asyncio.sleep(delay)
                if on_status:
                    on_status("Thinking")

    @staticmethod
    def _backoff(attempt):
        return min(2 ** (attempt + 1), 8) + random.uniform(0, 0.5)

    @staticmethod
    def _retryable(exc):
        if isinstance(exc, LLMError):
            if exc.retryable is not None:
                return exc.retryable
            return exc.status_code in RETRY_STATUS
        return isinstance(exc, httpx.HTTPError)

    def _headers(self):
        return {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        }

    def _assemble(self, content, tool_map):
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

    async def _stream(self, body, on_delta, on_think, on_usage):
        timeout = httpx.Timeout(
            connect=self.connect_timeout,
            read=self.read_timeout,
            write=self.connect_timeout,
            pool=self.connect_timeout,
        )

        content = ""
        tool_map = {}
        emitted = {"shown": False}
        usage = None

        def emit_delta(t):
            if t:
                emitted["shown"] = True
                if on_delta:
                    on_delta(t)

        def emit_think(t):
            if t:
                emitted["shown"] = True
                if on_think:
                    on_think(t)

        try:
            async with httpx.AsyncClient(timeout=timeout, trust_env=True) as client:
                async with client.stream(
                    "POST",
                    f"{self.base_url}/chat/completions",
                    headers=self._headers(),
                    json=body,
                ) as resp:
                    if resp.status_code != 200:
                        raw = (await resp.aread()).decode("utf-8", "replace")
                        self._log(f"HTTP {resp.status_code}: {raw[:600]}")
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
                            self._log(f"stream error: {str(chunk['error'])[:600]}")
                            raise LLMError(str(chunk["error"])[:400])

                        # usage may arrive in its own chunk (empty choices)
                        if chunk.get("usage"):
                            usage = chunk["usage"]

                        choices = chunk.get("choices") or []
                        if not choices:
                            continue
                        delta = choices[0].get("delta") or {}

                        text = delta.get("content")
                        if text:
                            content += text
                            emit_delta(text)

                        think = (delta.get("reasoning_content")
                                 or delta.get("reasoning"))
                        if think:
                            emit_think(think)

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

        except LLMError:
            raise
        except httpx.HTTPError as e:
            if emitted["shown"]:
                # already printed to the screen: retrying would duplicate it
                self._log(f"stream interrupted after output: "
                          f"{type(e).__name__}: {e}")
                raise LLMError(
                    f"stream interrupted: {type(e).__name__}: {e}",
                    partial=self._assemble(content, tool_map),
                    retryable=False,
                )
            raise   # nothing shown yet -> normal retry path in chat()

        if usage and on_usage:
            try:
                on_usage(usage)
            except Exception:
                pass

        return self._assemble(content, tool_map)
