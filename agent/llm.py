import httpx


class LLM:
    def __init__(self, config):
        self.base_url = config["base_url"].rstrip("/")
        self.model = config["model"]
        self.api_key = config["api_key"]

    async def chat(self, messages, tools=None):
        body = {
            "model": self.model,
            "messages": messages,
            "temperature": 0.6
        }

        if tools:
            body["tools"] = tools
            body["tool_choice"] = "auto"

        async with httpx.AsyncClient(timeout=120) as client:
            r = await client.post(
                f"{self.base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json"
                },
                json=body
            )

            r.raise_for_status()
            return r.json()["choices"][0]["message"]
