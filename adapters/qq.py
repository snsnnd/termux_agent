"""QQ adapter: OneBot 11 forward-WebSocket client (NapCat/Lagrange compatible).

Run next to NapCat inside Termux:

    python3 -m adapters.qq [config.json]

Config keys (with defaults):
    "qq_url": "ws://127.0.0.1:3001"   # NapCat OneBot11 WebSocket server
    "qq_token": ""                    # access token (sent as header + query)
    "qq_group_at_only": true          # in groups only answer @mentions
    "qq_reply_max": 1800              # max chars per outgoing message

Design notes:
- one shared Agent; an asyncio.Lock per conversation serializes turns and
  doubles as the queue (messages arriving mid-turn are answered in order)
- only the FINAL answer is sent to chat (streaming would spam the group);
  tool runs and retries are logged to the terminal instead
- approval has no y/N prompt here: set config "approval" to "auto" or
  "readonly" for unattended use
- the socket reconnects with capped exponential backoff
"""

import asyncio
import json
import re
import signal
import sys
import time

import websockets

from agent.llm import LLM
from agent.core import Agent
from agent.memory import Memory
from main import load_config, resolve_under, validate_config, wake_lock
from tools.router import ToolRouter
from ui import paint

_CQ_SEG = re.compile(r"\[CQ:[^\]]*\]")
_CQ_AT = re.compile(r"\[CQ:at,qq=(\d+)\]")


def cq_unescape(text):
    # order matters: numeric entities first, &amp; last, single pass
    text = text.replace("&#91;", "[").replace("&#93;", "]")
    text = text.replace("&#44;", ",").replace("&amp;", "&")
    return text


def extract_text(event):
    """Return (plain text, mentioned qq ids) from an OneBot11 message."""
    msg = event.get("message")
    if isinstance(msg, list):
        parts, ats = [], []
        for seg in msg:
            if not isinstance(seg, dict):
                continue
            kind = seg.get("type")
            # standard is {"type","data":{...}}; some senders flatten it
            data = seg.get("data") or {
                k: v for k, v in seg.items() if k != "type"}
            if kind == "text":
                parts.append(str(data.get("text", "")))
            elif kind == "at":
                qq = str(data.get("qq", ""))
                ats.append(qq)
                parts.append(f"@{qq} ")
            elif kind == "image":
                parts.append("[image]")
            elif kind == "face":
                parts.append("[face]")
            # reply/record/video segments are not part of the ask
        return "".join(parts).strip(), ats

    raw = event.get("raw_message")
    if raw is None and isinstance(msg, str):
        raw = msg
    raw = str(raw or "")
    ats = _CQ_AT.findall(raw)
    text = cq_unescape(_CQ_SEG.sub("", raw)).strip()
    return text, ats


def chunk_text(text, limit):
    """Split a reply into QQ-safe pieces, preferring paragraph boundaries."""
    text = str(text).strip()
    if limit <= 0 or len(text) <= limit:
        return [text] if text else []
    out, rest = [], text
    while len(rest) > limit:
        window = rest[:limit]
        cut = max(window.rfind("\n\n"), window.rfind("\n"), window.rfind("。"))
        cut = cut if cut > limit // 3 else limit
        out.append(rest[:cut].rstrip())
        rest = rest[cut:].lstrip()
    if rest:
        out.append(rest)
    return out


class QQBot:
    def __init__(self, config, cfg_dir, log=print):
        self.config = config
        self.url = str(config.get("qq_url", "ws://127.0.0.1:3001"))
        self.token = str(config.get("qq_token") or "")
        self.at_only = bool(config.get("qq_group_at_only", True))
        self.reply_max = int(config.get("qq_reply_max", 1800))
        self.use_wake = bool(config.get("termux_wake_lock", True))
        self.log = log

        llm = LLM(config)
        tools = ToolRouter(resolve_under(cfg_dir, config.get("workspace"),
                                         "workspace"))
        memory = Memory(str(resolve_under(cfg_dir, config.get("db"),
                                          "data/memory.db")))
        # no interactive approval channel in chat -> policy from config
        self.agent = Agent(
            llm, tools, memory,
            config.get("max_tool_rounds", 10),
            context=config,
            approve=None,
        )
        self.memory = memory

        self.ws = None
        self.bot_id = ""
        self._locks = {}        # session -> asyncio.Lock (per-chat serial)
        self._pending = {}      # echo -> Future (API call responses)
        self._seq = 0
        self._stopping = False

    # -------------------------------------------------------------- wire
    def _connect(self):
        headers = {}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        uri = self.url
        if self.token and "access_token=" not in uri:
            sep = "&" if "?" in uri else "?"
            uri = f"{uri}{sep}access_token={self.token}"
        kwargs = dict(max_size=4 * 1024 * 1024, ping_interval=20,
                      ping_timeout=20)
        try:
            return websockets.connect(
                uri, additional_headers=headers or None, **kwargs)
        except TypeError:      # websockets < 14
            return websockets.connect(uri, extra_headers=headers or None,
                                      **kwargs)

    async def _call(self, action, params=None, timeout=15):
        """OneBot API call over the socket; returns data or None."""
        if self.ws is None:
            return None
        self._seq += 1
        echo = f"a{self._seq}"
        fut = asyncio.get_running_loop().create_future()
        self._pending[echo] = fut
        try:
            await self.ws.send(json.dumps(
                {"action": action, "params": params or {}, "echo": echo},
                ensure_ascii=False,
            ))
            return await asyncio.wait_for(fut, timeout)
        except Exception:
            return None
        finally:
            self._pending.pop(echo, None)

    async def _dispatch(self, raw):
        try:
            obj = json.loads(raw)
        except ValueError:
            return
        if not isinstance(obj, dict):
            return
        echo = obj.get("echo")
        if isinstance(echo, str) and echo in self._pending:
            fut = self._pending[echo]
            if not fut.done():
                fut.set_result(obj.get("data"))
            if obj.get("status") not in (None, "ok"):
                self.log(paint(
                    f"[qq] api {echo} failed: retcode="
                    f"{obj.get('retcode')} {obj.get('message') or ''}",
                    "yellow"))
            return
        if obj.get("post_type") != "message":
            return   # lifecycle/heartbeat/other
        # run the turn as its own task so this reader keeps draining
        asyncio.create_task(self._on_message(obj))

    # ----------------------------------------------------------- routing
    def _session_of(self, ev):
        if ev.get("message_type") == "group":
            return f"qq_g{ev.get('group_id')}", "group"
        return f"qq_p{ev.get('user_id')}", "private"

    async def _on_message(self, ev):
        text, ats = extract_text(ev)
        if not text:
            return
        session, kind = self._session_of(ev)
        if kind == "group" and self.at_only:
            if self.bot_id and self.bot_id not in ats:
                return   # not our mention -> stay quiet in groups

        lock = self._locks.setdefault(session, asyncio.Lock())
        async with lock:      # FIFO: queued messages answered in order
            await self._turn(session, kind, ev, text)

    async def _turn(self, session, kind, ev, text):
        self.log(paint(f"[qq] {session} <- {text[:80]}", "cyan"))
        self.memory.set_meta("current_session", session)
        locked = self.use_wake and await wake_lock(True)
        started = time.monotonic()
        try:
            answer = await self.agent.run(
                text,
                session=session,
                emit=lambda e: None,       # keep chat clean; log below
            )
        except Exception as e:
            answer = f"[error] {type(e).__name__}: {e}"
            self.log(paint(f"[qq] {session} !! {answer[:160]}", "red"))
        finally:
            if locked:
                await wake_lock(False)

        elapsed = time.monotonic() - started
        self.log(paint(
            f"[qq] {session} -> {(answer or '')[:80]} ({elapsed:.1f}s)",
            "green"))
        if answer:
            await self._reply(ev, kind, answer)

    async def _reply(self, ev, kind, answer):
        if kind == "group":
            action, params = "send_group_msg", {"group_id": ev.get("group_id")}
        else:
            action, params = "send_private_msg", {"user_id": ev.get("user_id")}
        chunks = chunk_text(answer, self.reply_max)
        for i, piece in enumerate(chunks):
            if i:
                await asyncio.sleep(0.4)   # avoid hit rate limits
            data = await self._call(action, {**params, "message": piece})
            if data is None:
                self.log(paint(f"[qq] reply send failed ({action})", "yellow"))
                break

    # ------------------------------------------------------------- serve
    async def _pump(self, ws):
        while True:
            raw = await ws.recv()
            await self._dispatch(raw)

    async def serve(self):
        backoff = 1.0
        while not self._stopping:
            pump = None
            try:
                async with self._connect() as ws:
                    self.ws = ws
                    backoff = 1.0
                    # reader MUST run before the login call, otherwise the
                    # response future never resolves
                    pump = asyncio.create_task(self._pump(ws))
                    info = await self._call("get_login_info", {}, timeout=5)
                    if info:
                        self.bot_id = str(info.get("user_id") or "")
                    self.log(paint(
                        f"[qq] connected to {self.url} "
                        f"(bot={self.bot_id or '?'})",
                        "green", "bold"))
                    await pump      # runs until the socket drops
            except asyncio.CancelledError:
                raise
            except Exception as e:
                if not self._stopping:
                    self.log(paint(f"[qq] connection lost: {e}", "yellow"))
            finally:
                self.ws = None
                if pump is not None:
                    pump.cancel()
                    try:
                        await pump
                    except (asyncio.CancelledError, Exception):
                        pass
            if self._stopping:
                break
            self.log(paint(f"[qq] reconnect in {backoff:.0f}s", "dim"))
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 30.0)

    def stop(self):
        self._stopping = True
        ws = self.ws
        if ws is not None:
            asyncio.ensure_future(ws.close())


async def run(argv):
    config, cfg_path = load_config(argv)
    cfg_dir = cfg_path.parent

    fatal, warns = validate_config(config)
    for w in warns:
        print(paint(f"[config] {w}", "yellow"))
    if fatal:
        for f in fatal:
            print(paint(f"[config] fatal: {f}", "bold", "red"))
        return 2

    bot = QQBot(config, cfg_dir)
    loop = asyncio.get_running_loop()
    for sig_name in ("SIGINT", "SIGTERM"):
        try:
            loop.add_signal_handler(getattr(signal, sig_name), bot.stop)
        except (NotImplementedError, RuntimeError, AttributeError):
            pass

    print(paint(
        f"QQ adapter starting: {bot.url} "
        f"(group_at_only={bot.at_only}, approval="
        f"{config.get('approval', 'auto')})",
        "dim"))
    try:
        await bot.serve()
    finally:
        bot.memory.close()
    print(paint("[qq] stopped", "dim"))
    return 0


def cli(argv=None):
    argv = argv if argv is not None else sys.argv
    try:
        import asyncio as _a
        code = _a.run(run(argv))
    except KeyboardInterrupt:
        code = 130
    sys.exit(code or 0)


if __name__ == "__main__":
    cli()
