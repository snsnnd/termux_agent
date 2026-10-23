"""Terminal UI: colors, thinking animation, event rendering.

Termux-aware design:
- ASCII spinner frames (no braille/emoji, safe in any Termux font)
- colors auto-off when stdout is not a TTY or NO_COLOR is set (FORCE_COLOR=1 forces)
- terminal width is read dynamically (Termux resize/rotate friendly)
"""

import asyncio
import datetime
import json
import os
import shutil
import sys

_FRAMES = ("", ".", "..", "...")
_RESET = "\033[0m"
_STYLES = {
    "bold": "\033[1m",
    "dim": "\033[2m",
    "red": "\033[31m",
    "green": "\033[32m",
    "yellow": "\033[33m",
    "blue": "\033[34m",
    "magenta": "\033[35m",
    "cyan": "\033[36m",
}


def _isatty(stream):
    try:
        return bool(stream.isatty())
    except (OSError, ValueError):
        return False


def _colors_enabled():
    if os.environ.get("FORCE_COLOR"):
        return True
    if "NO_COLOR" in os.environ:
        return False
    if os.environ.get("TERM") == "dumb":
        return False
    return _isatty(sys.stdout)


COLOR = _colors_enabled()
TTY = _isatty(sys.stdout)


def paint(text, *styles):
    if not COLOR:
        return text
    prefix = "".join(_STYLES[s] for s in styles)
    return f"{prefix}{text}{_RESET}"


def _cols():
    try:
        return max(40, shutil.get_terminal_size((80, 24)).columns)
    except OSError:
        return 80


def _head():
    return paint("Agent > ", "bold", "green")


def _preview(result, max_lines=14):
    lines = str(result).splitlines() or ["(no output)"]
    if len(lines) > max_lines:
        more = len(lines) - max_lines
        lines = lines[:max_lines] + [f"... ({more} more lines)"]
    width = _cols() - 4
    return [ln if len(ln) <= width else ln[:width - 3] + "..." for ln in lines]


class UI:
    """Renders agent events. One instance per process.

    _pending tracks the currently open output block:
      None | "think" (dim reasoning block) | "stream" (green answer being streamed)
    """

    def __init__(self):
        self._spin_on = False
        self._spin_task = None
        self._spin_text = "Thinking"
        self._frame = 0
        self._pending = None
        self._head_done = False
        self._last_status = None

    # ------------------------------------------------------------------ setup
    def banner(self, model, session, messages=0):
        line1 = paint("Termux Agent", "bold", "cyan")
        line2 = paint(f"  model={model}  session={session} ({messages} msgs)", "dim")
        print(f"{line1}{line2}")
        print(paint(
            "commands: /help  /new  /sessions  /resume  /compact  /clear  /exit",
            "dim",
        ))
        print()

    def prompt_text(self):
        return paint("You > ", "bold", "cyan")

    def help(self):
        rows = [
            ("/new [name]", "create and switch to a new session"),
            ("/sessions", "list all sessions"),
            ("/resume <name>", "switch back to a session"),
            ("/compact", "summarize the whole history now"),
            ("/clear", "erase history of the current session"),
            ("/exit", "quit"),
        ]
        for cmd, desc in rows:
            print(f"  {paint(cmd.ljust(14), 'cyan')} {paint(desc, 'dim')}")

    def sessions(self, rows, current):
        if not rows:
            print(paint("  (no sessions yet)", "dim"))
            return
        print(paint("  sessions:", "dim"))
        for row in rows:
            name = row["session"]
            mark = paint("*", "green", "bold") if name == current else " "
            when = datetime.datetime.fromtimestamp(row["last"]).strftime("%m-%d %H:%M")
            print(f"  {mark} {name}  {row['count']} msgs  {when}")

    # -------------------------------------------------------- lifecycle hooks
    def turn_start(self):
        self._close_pending()
        self.stop_status()
        self._pending = None
        self._head_done = False

    def end_turn(self):
        self._close_pending()
        self.stop_status()
        print(flush=True)

    def _close_pending(self):
        if self._pending is not None:
            sys.stdout.write("\n")
            sys.stdout.flush()
            self._pending = None

    # ------------------------------------------------- status / spinner
    def status(self, text):
        """Show current activity. Spinner while idle, plain note while streaming."""
        if self._pending is not None:
            # text already on screen: only surface retries, on their own line
            if text.startswith("Reconnecting") and text != self._last_status:
                sys.stdout.write("\n" + paint(f"  {text}", "yellow") + "\n")
                sys.stdout.flush()
                self._last_status = text
            return

        self._spin_text = text
        if TTY:
            if not self._spin_on:
                self._frame = 0
            self._spin_on = True
            if self._spin_task is None:
                self._spin_task = asyncio.ensure_future(self._spin_loop())
        elif text != self._last_status:
            # piped output: no animation, print once per distinct status
            print(paint(f"[{text}...]", "dim"), flush=True)
        self._last_status = text

    async def _spin_loop(self):
        while True:
            if self._spin_on:
                dots = _FRAMES[self._frame % len(_FRAMES)]
                self._frame += 1
                line = "\r" + paint(f"{self._spin_text}{dots}", "cyan") + "\033[K"
                sys.stdout.write(line)
                sys.stdout.flush()
                await asyncio.sleep(0.35)
            else:
                await asyncio.sleep(0.2)

    def stop_status(self):
        self._spin_on = False
        if TTY:
            sys.stdout.write("\r\033[K")
            sys.stdout.flush()

    async def aclose(self):
        self._spin_on = False
        if self._spin_task is not None:
            self._spin_task.cancel()
            try:
                await self._spin_task
            except asyncio.CancelledError:
                pass
            self._spin_task = None

    # ---------------------------------------------------- streamed output
    def on_think(self, text):
        if not text:
            return
        if self._pending != "think":
            self.stop_status()
            if self._pending == "stream":
                sys.stdout.write("\n")
            self._pending = "think"
            sys.stdout.write(paint("[thinking]", "dim") + "\n")
        sys.stdout.write(paint(text, "dim"))
        sys.stdout.flush()

    def on_delta(self, text):
        if not text:
            return
        if self._pending != "stream":
            self.stop_status()
            if self._pending == "think":
                sys.stdout.write("\n")
            self._pending = "stream"
            if not self._head_done:
                sys.stdout.write(_head())
                self._head_done = True
        sys.stdout.write(text)
        sys.stdout.flush()

    def answer(self, text):
        self.stop_status()
        if self._pending == "think":
            sys.stdout.write("\n")
        self._pending = None
        if not self._head_done:
            sys.stdout.write(_head())
            self._head_done = True
        sys.stdout.write(str(text) + "\n")
        sys.stdout.flush()

    # --------------------------------------------------------- events
    def tool(self, name, args, index=None, total=None):
        self._close_pending()
        self.stop_status()
        if isinstance(args, str):
            pretty = args.strip()
        else:
            pretty = json.dumps(args, ensure_ascii=False)
        pretty = " ".join(pretty.split())
        width = _cols() - 18
        if len(pretty) > width:
            pretty = pretty[:width - 3] + "..."
        seq = f" ({index}/{total})" if total and total > 1 else ""
        print(
            f"  {paint('[tool]', 'bold', 'yellow')} "
            f"{paint(name, 'yellow')}{paint(seq, 'yellow')} {paint(pretty, 'dim')}",
            flush=True,
        )

    def tool_result(self, result, ms=None):
        self.stop_status()
        for ln in _preview(result):
            print(paint("  | " + ln, "dim"), flush=True)
        if ms is not None:
            print(paint(f"  | ({ms} ms)", "dim"), flush=True)

    def info(self, text):
        self._close_pending()
        self.stop_status()
        print(paint(f"[info] {text}", "magenta"), flush=True)

    def error(self, text):
        self._close_pending()
        self.stop_status()
        print(paint(f"[error] {text}", "bold", "red"), flush=True)

    def emit(self, event):
        kind = event.get("type")
        if kind == "status":
            self.status(event.get("text", "Thinking"))
        elif kind == "tool":
            self.tool(
                event.get("name", "?"),
                event.get("args", {}),
                event.get("index"),
                event.get("total"),
            )
        elif kind == "tool_result":
            self.tool_result(event.get("result", ""), event.get("ms"))
        elif kind == "answer":
            self.answer(event.get("text", ""))
        elif kind == "stream_end":
            self._close_pending()
        elif kind == "info":
            self.info(event.get("text", ""))
