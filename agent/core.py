import json


SYSTEM_PROMPT = """
You are a capable autonomous agent running inside Termux on Android.

You can reason, communicate with the user, inspect files, write files,
and execute commands using the provided tools.

When the user asks you to perform a task:
- Use tools when actual execution or inspection is needed.
- Do not claim a command succeeded unless you observed its result.
- Inspect results before deciding the next step.
- Continue using tools until the task is complete or blocked.
- Keep changes inside the provided workspace unless explicitly supported.
- If a tool fails, analyze the error and attempt a reasonable correction.
- Do not invent tool results.
"""


class Agent:
    def __init__(self, llm, tools, memory, max_rounds=10):
        self.llm = llm
        self.tools = tools
        self.memory = memory
        self.max_rounds = max_rounds

    async def run(self, text, session="cli"):
        history = self.memory.recent(session)

        messages = [
            {
                "role": "system",
                "content": SYSTEM_PROMPT
            },
            *history,
            {
                "role": "user",
                "content": text
            }
        ]

        self.memory.add(session, "user", text)

        for _ in range(self.max_rounds):
            message = await self.llm.chat(
                messages,
                self.tools.definitions()
            )

            messages.append(message)

            tool_calls = message.get("tool_calls")

            if not tool_calls:
                answer = message.get("content") or ""
                self.memory.add(session, "assistant", answer)
                return answer

            for call in tool_calls:
                function = call["function"]
                name = function["name"]
                arguments = function.get("arguments", "{}")

                print(f"\n[tool] {name}")
                print(f"[args] {arguments}")

                result = await self.tools.execute(
                    name,
                    arguments
                )

                print(f"[result]\n{result[:2000]}\n")

                messages.append({
                    "role": "tool",
                    "tool_call_id": call["id"],
                    "content": str(result)
                })

        answer = "Agent reached maximum tool rounds."
        self.memory.add(session, "assistant", answer)
        return answer
