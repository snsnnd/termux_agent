"""Termux Agent CLI entry point.

Termux specifics handled here:
- all paths are anchored to this file, so it works no matter which
  directory the shell starts in (Termux launches often land in $HOME)
- optional `termux-wake-lock` around each agent turn so the device does
  not suspend mid-request (no-op outside Termux)
- Ctrl+C interrupts the current turn without killing the session,
  EOF (backgrounded app closing stdin) exits cleanly
- sessions persist across restarts (last one is restored from the DB)
"""

import asyncio
import json
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

from agent.llm import LLM
from agent.core import Agent
from agent.memory import Memory
from tools.router import ToolRouter
from ui import UI

BASE = Path(__file__).resolve().parent


def load_config(argv):
    path = Path(argv[1]) if len(argv) > 1 else BASE / "config.json"
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f), path.parent


def resolve_under(base_dir, value, default_name):
    """Config paths are relative to the config file, never to $PWD."""
    p = Path(value or default_name)
    return p if p.is_absolute() else (base_dir / p)


async def wake_lock(on):
    """Best-effort Termux wake lock; returns True if it did anything."""
    cmd = "termux-wake-lock" if on else "termux-wake-unlock"
    exe = shutil.which(cmd)
    if not exe:
        return False
    try:
        await asyncio.to_thread(
            subprocess.run, exe, timeout=5,
            check=False, capture_output=True,
        )
        return True
    except Exception:
        return False


def valid_session(name):
    return bool(re.fullmatch(r"[A-Za-z0-9_.-]{1,32}", name))


async def main(argv):
    config, cfg_dir = load_config(argv)

    llm = LLM(config)
    tools = ToolRouter(resolve_under(cfg_dir, config.get("workspace"), "workspace"))
    memory = Memory(str(resolve_under(cfg_dir, config.get("db"), "data/memory.db")))
    agent = Agent(
        llm, tools, memory,
        config.get("max_tool_rounds", 10),
        context=config,
    )

    ui = UI()
    session = memory.get_meta("current_session") or "cli"
    use_wake = config.get("termux_wake_lock", True)

    ui.banner(config.get("model", "?"), session, memory.count(session))

    try:
        while True:
            try:
                line = input(ui.prompt_text())
            except (EOFError, KeyboardInterrupt):
                print()
                break

            text = line.strip()
            if not text:
                continue

            if text in {"/exit", "/quit"}:
                break

            if text == "/help":
                ui.help()
                continue

            if text == "/sessions":
                ui.sessions(memory.sessions(), session)
                continue

            if text.startswith("/new"):
                name = text[4:].strip() or datetime.now().strftime("s%m%d-%H%M%S")
                if not valid_session(name):
                    ui.error("session names: letters, digits, _ . - (max 32)")
                    continue
                session = name
                memory.set_meta("current_session", session)
                ui.turn_start()
                ui.info(f"session '{session}' active "
                        f"({memory.count(session)} messages)")
                continue

            if text.startswith("/resume"):
                name = text[7:].strip()
                if not name:
                    ui.error("usage: /resume <name>")
                elif not memory.session_exists(name):
                    ui.error(f"no session '{name}'")
                    ui.sessions(memory.sessions(), session)
                else:
                    session = name
                    memory.set_meta("current_session", session)
                    ui.turn_start()
                    ui.info(f"resumed '{session}' "
                            f"({memory.count(session)} messages)")
                continue

            if text == "/clear":
                agent.clear(session, emit=ui.emit)
                continue

            if text == "/compact":
                ui.turn_start()
                try:
                    await agent.compact(session, emit=ui.emit)
                except Exception as e:
                    ui.error(f"{type(e).__name__}: {e}")
                ui.end_turn()
                continue

            # ---- normal turn --------------------------------------------
            ui.turn_start()
            memory.set_meta("current_session", session)
            locked = use_wake and await wake_lock(True)
            try:
                await agent.run(
                    text,
                    session=session,
                    emit=ui.emit,
                    on_delta=ui.on_delta,
                    on_think=ui.on_think,
                )
            except asyncio.CancelledError:
                ui.error("interrupted")
                raise
            except KeyboardInterrupt:
                ui.error("interrupted")
            except Exception as e:
                ui.error(f"{type(e).__name__}: {e}")
            finally:
                if locked:
                    await wake_lock(False)
                ui.end_turn()
    finally:
        memory.set_meta("current_session", session)
        memory.close()
        try:
            await ui.aclose()
        except Exception:
            pass


if __name__ == "__main__":
    try:
        asyncio.run(main(sys.argv))
    except KeyboardInterrupt:
        pass
