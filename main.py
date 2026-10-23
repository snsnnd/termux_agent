import asyncio
import json

from agent.llm import LLM
from agent.core import Agent
from agent.memory import Memory
from tools.router import ToolRouter


async def main():
    with open("config.json", "r", encoding="utf-8") as f:
        config = json.load(f)

    llm = LLM(config)

    tools = ToolRouter(
        config.get("workspace", "./workspace")
    )

    memory = Memory("./data/memory.db")

    agent = Agent(
        llm,
        tools,
        memory,
        config.get("max_tool_rounds", 10)
    )

    print("Termux Agent")
    print("Type /exit to quit.\n")

    while True:
        try:
            text = input("You > ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break

        if not text:
            continue

        if text in {"/exit", "/quit"}:
            break

        try:
            answer = await agent.run(text)
            print(f"\nAgent > {answer}\n")

        except Exception as e:
            print(
                f"\nERROR: {type(e).__name__}: {e}\n"
            )


if __name__ == "__main__":
    asyncio.run(main())
