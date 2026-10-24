"""Termux Agent CLI entry point.

Termux specifics handled here:
- all paths are anchored to this file, so it works no matter which
  directory the shell starts in (Termux launches often land in $HOME)
- optional `termux-wake-lock` around each agent turn so the device does
  not suspend mid-request (no-op outside Termux)
- stdin is drained by a background thread into an async queue, so a
  tool-approval prompt can read a line in the middle of a streamed turn
- Ctrl+C interrupts the current turn (history stays consistent) and only
  exits the program when pressed at an idle prompt
- optional `termux-notification` when a long turn finishes in background
- sessions persist across restarts (last one is restored from the DB)
"""

import asyncio
import difflib
import json
import queue
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

from agent.llm import LLM
from agent.core import Agent
from agent.memory import Memory
from tools.router import ToolRouter
from ui import UI, paint

BASE = Path(__file__).resolve().parent

# single source for command handling: dispatcher fallback + Tab completion
_COMMANDS = ("/help", "/new", "/sessions", "/resume", "/clear", "/compact",
             "/usage", "/undo", "/export", "/model", "/exit", "/quit")

_readline = None


def _completer(text, state):
    """Tab-complete slash commands, bash-style (double-Tab lists them all)."""
    line = _readline.get_line_buffer()
    if " " in line or (line and not line.startswith("/")):
        return None
    hits = [c for c in _COMMANDS if c.startswith(text)]
    return hits[state] if state < len(hits) else None


def setup_line_editing():
    """Make the prompt shell-like: Tab completes /commands, up/down arrows
    recall history, Home/End edit the line. No-op without readline."""
    global _readline
    try:
        import readline as rl
    except ImportError:
        return
    _readline = rl
    rl.set_completer(_completer)
    # "/" must NOT break words, otherwise the completer sees "ses"
    # instead of "/ses" and never matches anything
    rl.set_completer_delims(" \t\n")
    rl.parse_and_bind("tab: complete")
    rl.set_history_length(500)


def load_config(argv):
    path = Path(argv[1]) if len(argv) > 1 else BASE / "config.json"
    with open(path, "r", encoding="utf-8") as f:
        config = json.load(f)
    # paths in the config are relative to the config file, never to $PWD
    dbg = config.get("debug_log")
    if dbg:
        p = Path(dbg).expanduser()
        config["debug_log"] = str(p if p.is_absolute()
                                  else path.parent / p)
    return config, path


def resolve_under(base_dir, value, default_name):
    """Config paths are relative to the config file, never to $PWD."""
    p = Path(value or default_name)
    return p if p.is_absolute() else (base_dir / p)


def validate_config(config):
    """Startup self-check: return (fatal, warnings)."""
    fatal, warns = [], []
    key = str(config.get("api_key") or "").strip()
    if not key or key.startswith("REPLACE") or len(key) < 16:
        fatal.append("api_key is missing or still a placeholder")
    url = str(config.get("base_url") or "")
    if not url.startswith(("http://", "https://")):
        fatal.append("base_url must start with http:// or https://")
    approval = str(config.get("approval", "auto")).lower()
    if approval not in {"auto", "ask", "readonly"}:
        warns.append(f"approval={approval!r} unknown, falling back to 'auto'")
    for name in ("max_tool_rounds", "context_budget", "tool_result_limit",
                 "summary_trigger", "max_retries"):
        v = config.get(name)
        if v is not None and (not isinstance(v, int) or isinstance(v, bool)
                              or v <= 0):
            warns.append(f"{name}={v!r} is not a positive int, using default")
    return fatal, warns


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


async def notify_done(title, body):
    """Best-effort Termux notification; silent no-op outside Termux."""
    exe = shutil.which("termux-notification")
    if not exe:
        return
    try:
        await asyncio.to_thread(
            subprocess.run,
            [exe, "--id", "agent.turn", "--title", title, "--content", body],
            timeout=5, check=False, capture_output=True,
        )
    except Exception:
        pass


def valid_session(name):
    return bool(re.fullmatch(r"[A-Za-z0-9_.-]{1,32}", name))


class InputSource:
    """Blocking stdin on a thread -> asyncio.Queue.

    Lets approval prompts read a line mid-turn while the reader thread is
    the only one touching sys.stdin, and turns EOF (backgrounded Termux
    app closing stdin) into an orderly shutdown signal.
    """

    def __init__(self):
        self._queue = asyncio.Queue()
        self._prompts = queue.Queue()   # prompt handoff to the reader thread
        self._loop = None
        self._eof = False

    def start(self, loop):
        self._loop = loop
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        # input(prompt) (not sys.stdin.readline) so the readline module gives
        # Tab completion + history with correct prompt-aware redrawing; the
        # prompt arrives through _prompts because main() decides it per read
        while True:
            prompt = self._prompts.get()
            try:
                line = input(prompt)
            except EOFError:
                line = ""
            except (OSError, ValueError):
                line = ""
            if line == "":
                self._post(None)
                return
            self._post(line.rstrip("\r\n"))

    def _post(self, item):
        try:
            self._loop.call_soon_threadsafe(self._queue.put_nowait, item)
        except RuntimeError:
            pass  # event loop already closed

    def interrupt(self):
        """Ctrl+C at an idle prompt behaves like 'leave this prompt'."""
        if not self._eof:
            self._queue.put_nowait(None)

    async def read_line(self, prompt=""):
        if self._eof:
            return None
        # hand the prompt to the reader thread: input(prompt) makes readline
        # aware of it, so completion redraws don't clobber the prompt
        self._prompts.put(prompt)
        line = await self._queue.get()
        if line is None:
            self._eof = True
            return None
        if not sys.stdin.isatty():
            # piped stdin does not echo the typed line; add the newline
            sys.stdout.write("\n")
            sys.stdout.flush()
        return line


async def main(argv):
    config, cfg_path = load_config(argv)
    cfg_dir = cfg_path.parent

    fatal, warns = validate_config(config)
    for w in warns:
        print(paint(f"[config] {w}", "yellow"))
    if fatal:
        for f in fatal:
            print(paint(f"[config] fatal: {f}", "bold", "red"))
        print(paint(f"fix it in {cfg_path} and start again", "dim"))
        return 2

    llm = LLM(config)
    tools = ToolRouter(resolve_under(cfg_dir, config.get("workspace"),
                                     "workspace"))
    memory = Memory(str(resolve_under(cfg_dir, config.get("db"),
                                      "data/memory.db")))
    ui = UI()
    src = InputSource()

    agent = Agent(
        llm, tools, memory,
        config.get("max_tool_rounds", 10),
        context=config,
        # approval=ask routes side-effect tools through the terminal
        approve=lambda desc: ui.approve(desc, src.read_line),
    )

    session = memory.get_meta("current_session") or "cli"
    use_wake = config.get("termux_wake_lock", True)
    notify = bool(config.get("notify", False))
    notify_after = float(config.get("notify_after", 5))

    loop = asyncio.get_running_loop()
    setup_line_editing()
    src.start(loop)
    current = {"task": None}

    def on_sigint():
        task = current["task"]
        if task is not None and not task.done():
            task.cancel()          # mid-turn: interrupt, keep history sane
        else:
            src.interrupt()        # idle: behave like EOF at the prompt

    try:
        loop.add_signal_handler(signal.SIGINT, on_sigint)
    except (NotImplementedError, RuntimeError):
        pass  # e.g. non-Unix: falls back to plain KeyboardInterrupt

    ui.banner(llm.model, session, memory.count(session))

    try:
        while True:
            try:
                line = await src.read_line(ui.prompt_text())
            except KeyboardInterrupt:
                print()
                break
            if line is None:
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

            if text == "/usage":
                show_usage(memory, session)
                continue

            if text == "/undo":
                ui.turn_start()
                ui.info(str(tools.undo()))
                ui.end_turn()
                continue

            if text.startswith("/export"):
                export_session(memory, session, text[7:].strip())
                continue

            if text.startswith("/model"):
                name = text[6:].strip()
                if not name:
                    ui.info(f"model: {llm.model}")
                elif " " in name:
                    ui.error("usage: /model [name]")
                else:
                    old = llm.model
                    llm.model = name
                    config["model"] = name
                    note = ""
                    try:
                        cfg_path.write_text(
                            json.dumps(config, ensure_ascii=False, indent=2)
                            + "\n",
                            encoding="utf-8",
                        )
                        note = " (saved to config)"
                    except OSError:
                        note = " (this run only)"
                    ui.info(f"model: {old} -> {name}{note}")
                continue

            # slash input matched no command above: almost always a typo
            # (phone keyboards drop keys) -> correct it locally instead of
            # paying for an LLM turn that just guesses "did you mean"
            if text.startswith("/"):
                close = difflib.get_close_matches(text, _COMMANDS, n=1,
                                                  cutoff=0.5)
                hint = f"did you mean {close[0]}?" if close else "try /help"
                ui.error(f'unknown command "{text}" - {hint}')
                continue

            # ---- normal turn --------------------------------------------
            ui.turn_start()
            memory.set_meta("current_session", session)
            locked = use_wake and await wake_lock(True)
            task = asyncio.create_task(agent.run(
                text,
                session=session,
                emit=ui.emit,
                on_delta=ui.on_delta,
                on_think=ui.on_think,
            ))
            current["task"] = task
            started = time.monotonic()
            try:
                await task
            except asyncio.CancelledError:
                ui.error("interrupted")
            except KeyboardInterrupt:
                ui.error("interrupted")
            except Exception as e:
                ui.error(f"{type(e).__name__}: {e}")
            finally:
                current["task"] = None
                if locked:
                    await wake_lock(False)
                ui.end_turn()
                elapsed = time.monotonic() - started
                if notify and elapsed >= notify_after:
                    await notify_done(
                        "Termux Agent",
                        f"turn finished in {int(elapsed)}s",
                    )
    finally:
        memory.set_meta("current_session", session)
        memory.close()
        try:
            await ui.aclose()
        except Exception:
            pass


def show_usage(memory, session):
    """Per-session token totals accumulated from real API usage."""
    rows = memory.meta_like("usage:")
    if not rows:
        print(paint("  no usage recorded yet", "dim"))
        return
    total = {"prompt": 0, "completion": 0, "cached": 0, "calls": 0}
    print(paint("  usage by session:", "dim"))
    for key in sorted(rows, key=lambda k: -_usage_sum(rows[k])):
        try:
            data = json.loads(rows[key])
        except ValueError:
            continue
        name = key.split(":", 1)[1]
        mark = paint("*", "green", "bold") if name == session else " "
        print(f"  {mark} {name.ljust(16)} {_usage_line(data)}")
        for k in total:
            total[k] += int(data.get(k, 0) or 0)
    print(paint(f"  {'total'.ljust(18)} {_usage_line(total)}", "bold"))
    try:
        scale = float(memory.get_meta("token_scale", "1") or 1)
        if abs(scale - 1.0) > 0.02:
            print(paint(f"  estimator calibrated to {scale:.2f}x", "dim"))
    except (TypeError, ValueError):
        pass


def _usage_sum(raw):
    try:
        d = json.loads(raw)
        return int(d.get("prompt", 0)) + int(d.get("completion", 0))
    except (ValueError, TypeError, AttributeError):
        return 0


def _usage_line(d):
    p = int(d.get("prompt", 0) or 0)
    c = int(d.get("completion", 0) or 0)
    cached = int(d.get("cached", 0) or 0)
    calls = int(d.get("calls", 0) or 0)
    text = f"{p} prompt + {c} completion"
    if cached:
        text += f" ({cached} cached)"
    return text + f" in {calls} calls"


def export_session(memory, session, arg):
    """Dump the current session (summary + turns) as markdown."""
    rows = memory.recent(session, limit=5000)
    if not rows:
        print(paint("  nothing to export", "dim"))
        return
    if arg:
        out = Path(arg).expanduser()
        if not out.is_absolute():
            out = BASE / out
    else:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        out = BASE / "exports" / f"{session}-{stamp}.md"

    lines = [f"# session: {session}", ""]
    summary = memory.get_summary(session)
    if summary:
        lines += ["## summary", "", summary["content"], ""]
    lines += ["## messages", ""]
    for r in rows:
        who = "User" if r["role"] == "user" else "Agent"
        lines += [f"### {who}", "", str(r["content"] or ""), ""]

    try:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text("\n".join(lines), encoding="utf-8")
    except OSError as e:
        print(paint(f"  export failed: {e}", "red"))
        return
    print(paint(f"  exported {len(rows)} messages -> {out}", "magenta"))


if __name__ == "__main__":
    try:
        code = asyncio.run(main(sys.argv))
    except KeyboardInterrupt:
        code = 130
    sys.exit(code or 0)
