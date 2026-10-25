"""Agent loop (ReAct-style tool calling) with context budgeting & compaction.

Context design (follows common production-agent practice):

    [system prompt]              verbatim, byte-stable (prefix-cache friendly)
  + [rolling summary]            compressed old turns (summary table, upto id)
  + [recent turns]               verbatim, trimmed to fit token budget
  + [current user message]       always kept

Compression levers, cheapest first:
  1. tool-result capping      - one huge shell dump never enters context
  2. tool-output clearing     - within a turn, stale tool outputs are replaced
                                by a placeholder (last few kept verbatim)
  3. rolling summarization    - trimmed history reaching `summary_trigger`
                                messages is folded into a structured summary

Other responsibilities:
- parallel tool execution (asyncio.gather over to_thread-dispatched tools)
- approval policy (auto / ask / readonly) for side-effecting tools
- token accounting: real usage from the provider calibrates the local
  estimator (EMA), session totals land in meta for /usage
- interrupt consistency: a cancelled turn stores "[interrupted by user]"
  so history never contains dangling user messages
- partial replies: if the stream dies after output was shown, the turn
  finishes with the partial text instead of duplicating it via retry

Events emitted via `emit(event)` (rendered by ui.UI / adapters):
  {"type":"status",  "text": ...}   waiting/retrying indicator
  {"type":"tool",    "name","args","index","total"}
  {"type":"tool_result","result","ms"}
  {"type":"answer",  "text": ...}   final answer (when nothing was streamed)
  {"type":"stream_end"}             final answer was streamed, close the block
  {"type":"info",    "text": ...}   compaction/pruning notes
  {"type":"usage",   "prompt","completion","cached","calls"}
"""

import asyncio
import json
import time

from agent.llm import LLMError
from tools.router import is_safe_command

SYSTEM_PROMPT = """
You are a capable autonomous agent running inside Termux on Android.

You can reason, communicate with the user, inspect files, write files,
search code, and execute commands using the provided tools.

When the user asks you to perform a task:
- Use tools when actual execution or inspection is needed.
- Prefer read-only tools (read_file, grep_files, glob_files, list_files)
  for investigation; prefer edit_file over write_file for modifications.
- Several independent tool calls in one turn run in parallel; batch them.
- For commands longer than 60s use shell(background=true) and poll with
  the job tool instead of blocking.
- Do not claim a command succeeded unless you observed its result.
- Inspect results before deciding the next step.
- Continue using tools until the task is complete or blocked.
- Keep changes inside the provided workspace unless explicitly supported.
- If a tool fails or is denied, analyze the reason and adapt (ask the user
  or try another approach) rather than retrying blindly.
- Do not invent tool results.
""".strip()

CLEARED = "[old tool output cleared to save context]"
MAX_FETCH = 300          # hard cap on rows fetched from DB per turn
KEEP_TOOL_OUTPUTS = 4    # newest tool outputs kept verbatim inside one turn

# ---------------------------------------------------------------- tokens
_TOKEN_SCALE = 1.0


def set_token_scale(x):
    global _TOKEN_SCALE
    _TOKEN_SCALE = max(0.25, min(4.0, float(x)))


def get_token_scale():
    return _TOKEN_SCALE


def estimate_tokens(text):
    """Rough token estimate (CJK aware), calibrated by real usage (EMA)."""
    if not text:
        return 0
    cjk = 0
    for ch in text:
        o = ord(ch)
        if (0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF
                or 0x3000 <= o <= 0x303F or 0xFF00 <= o <= 0xFFEF):
            cjk += 1
    other = len(text) - cjk
    base = int(other / 4 + cjk * 0.9)
    return int(base * _TOKEN_SCALE) + 1


def cap_text(text, limit):
    """Keep head+tail of an overlong string, note what was cut."""
    if len(text) <= limit:
        return text
    head = limit * 2 // 3
    tail = limit - head
    cut = len(text) - head - tail
    return (text[:head]
            + f"\n... [truncated {cut} chars] ...\n"
            + text[-tail:])


def _empty_usage():
    return {"prompt": 0, "completion": 0, "cached": 0, "calls": 0}


class Agent:
    def __init__(self, llm, tools, memory, max_rounds=10, context=None,
                 approve=None):
        cfg = context or {}
        self.llm = llm
        self.tools = tools
        self.memory = memory
        self.max_rounds = max_rounds

        # token budget for everything sent to the model (history + summary)
        self.budget = int(cfg.get("context_budget", 6000))
        # per-tool-result cap entering the context
        self.tool_result_limit = int(cfg.get("tool_result_limit", 6000))
        # how many trimmed messages trigger a summarization call
        self.summary_trigger = int(cfg.get("summary_trigger", 4))

        # approval: auto (run all) | ask (confirm side effects) | readonly
        approval = str(cfg.get("approval", "auto")).lower()
        self.approval = approval if approval in {"auto", "ask", "readonly"} \
            else "auto"
        # async fn(desc) -> bool, provided by the frontend (None = no one to ask)
        self.approve = approve

        self._schema_tokens = estimate_tokens(json.dumps(self.tools.definitions()))
        self._pending_est = 0

        # calibrate estimator with previously observed real usage
        try:
            saved = float(self.memory.get_meta("token_scale", "1") or 1)
            set_token_scale(saved)
        except (TypeError, ValueError):
            set_token_scale(1.0)

    # ------------------------------------------------------------- public
    async def run(self, text, session="cli", emit=None, on_delta=None,
                  on_think=None):
        emit = emit or (lambda e: None)
        usage = _empty_usage()
        user_stored = False
        done = False
        streamed = [0]

        def delta(t):
            if t:
                streamed[0] += len(t)
                if on_delta:
                    on_delta(t)

        think = on_think or (lambda t: None)

        try:
            emit({"type": "status", "text": "Thinking"})
            # build first so the current message is not also in history
            messages = await self._build_messages(session, text, emit, usage)
            self.memory.add(session, "user", text)
            user_stored = True

            for _ in range(self.max_rounds):
                self._clear_stale_tool_outputs(messages, emit)
                emit({"type": "status", "text": "Thinking"})

                message = await self._ask(messages, emit, delta, think, usage)
                partial = message.pop("_partial", False)

                if not partial and not message.get("tool_calls"):
                    answer = (message.get("content") or "").strip() \
                        or "(empty response)"
                    done = True
                    return self._finish(emit, session, answer, streamed[0])

                if partial:
                    answer = (message.get("content") or "").strip() \
                        or "[response interrupted]"
                    done = True
                    return self._finish(emit, session, answer, streamed[0])

                messages.append(message)

                results = await self._run_tools(message["tool_calls"], emit)
                for call, (result, ms) in zip(message["tool_calls"], results):
                    emit({"type": "tool_result", "result": result, "ms": ms})
                    messages.append({
                        "role": "tool",
                        "tool_call_id": call.get("id", ""),
                        "content": cap_text(result, self.tool_result_limit),
                    })

            # synthesized locally: never streamed on screen, must print
            # (streamed[] counts deltas from ALL rounds and is >0 here,
            # which would wrongly emit only stream_end and hide the text)
            answer = "Reached maximum tool rounds; rephrase or continue."
            done = True
            return self._finish(emit, session, answer, 0)

        except (asyncio.CancelledError, KeyboardInterrupt):
            # keep history consistent: no dangling unanswered user message
            if user_stored and not done:
                try:
                    self.memory.add(session, "assistant",
                                    "[interrupted by user]")
                except Exception:
                    pass
            raise
        finally:
            self._usage_final(emit, usage, session)

    async def compact(self, session="cli", emit=None):
        """/compact - fold ALL pending history into the summary now."""
        emit = emit or (lambda e: None)
        usage = _empty_usage()
        try:
            rows = self._pending_rows(session)
            if len(rows) < 2:
                emit({"type": "info", "text": "nothing to compact"})
                return
            summary = self.memory.get_summary(session)
            new = await self._summarize(summary, rows, emit, usage)
            if new:
                self.memory.save_summary(session, new, upto=rows[-1]["id"])
                emit({
                    "type": "info",
                    "text": f"compacted {len(rows)} messages into summary "
                            f"(~{estimate_tokens(new)} tokens)",
                })
        finally:
            self._usage_final(emit, usage, session)

    def clear(self, session="cli", emit=None):
        emit = emit or (lambda e: None)
        self.memory.clear(session)
        emit({"type": "info", "text": f"session '{session}' cleared"})

    # ------------------------------------------------------ llm plumbing
    async def _ask(self, messages, emit, delta, think, usage):
        """One LLM round; converts mid-stream failures into partial replies."""
        self._pending_est = self._estimate(messages) + self._schema_tokens
        try:
            return await self.llm.chat(
                messages,
                self.tools.definitions(),
                on_delta=delta,
                on_think=think,
                on_status=lambda s: emit({"type": "status", "text": s}),
                on_usage=lambda u: self._on_usage(u, usage),
            )
        except LLMError as e:
            if e.partial is None:
                raise
            # stream broke after showing output: keep it, finish the turn
            partial = dict(e.partial)
            partial.pop("tool_calls", None)   # args may be truncated JSON
            partial["_partial"] = True
            emit({"type": "info",
                  "text": f"stream interrupted; using partial reply "
                          f"({type(e).__name__})"})
            return partial

    def _finish(self, emit, session, answer, streamed):
        self.memory.add(session, "assistant", answer)
        if streamed:
            emit({"type": "stream_end"})
        else:
            emit({"type": "answer", "text": answer})
        return answer

    # -------------------------------------------------- approval policy
    def _risky(self, name, args):
        if name in ("write_file", "edit_file"):
            return True
        if name == "shell":
            return not is_safe_command(args.get("command", ""))
        if name == "job":
            return args.get("action") == "kill"
        return False

    @staticmethod
    def _describe(name, args):
        if name == "shell":
            return f"shell: {str(args.get('command', ''))[:160]}"
        if name in ("write_file", "edit_file"):
            return f"{name}: {args.get('path', '?')}"
        if name == "job":
            return f"job {args.get('action')} {args.get('id', '')}"
        try:
            pretty = json.dumps(args, ensure_ascii=False)
        except (TypeError, ValueError):
            pretty = str(args)
        return f"{name} {pretty[:120]}"

    async def _authorize(self, name, args):
        """Return None to allow, or a denial reason string."""
        if self.approval == "auto" or not self._risky(name, args):
            return None
        if self.approval == "readonly":
            return "blocked by approval policy (readonly mode)"
        if self.approve is None:
            return ("denied: no interactive terminal and approval=ask "
                    "(set approval=auto in config to allow)")
        try:
            ok = await self.approve(self._describe(name, args))
        except Exception as e:
            return f"denied: approval error ({e})"
        return None if ok else "denied by user"

    # ------------------------------------------------- tool execution
    async def _run_tools(self, tool_calls, emit):
        """Authorize, then execute all calls in parallel; keep call order."""
        total = len(tool_calls)
        prepared = []
        for i, call in enumerate(tool_calls, start=1):
            fn = call.get("function") or {}
            name = fn.get("name", "?")
            raw = fn.get("arguments", "{}")
            emit({"type": "tool", "name": name, "args": raw,
                  "index": i, "total": total})

            try:
                args = json.loads(raw) if isinstance(raw, str) else (raw or {})
                if not isinstance(args, dict):
                    raise ValueError("arguments must be an object")
            except (ValueError, TypeError):
                args = {}

            denial = await self._authorize(name, args)
            prepared.append((name, args, denial))

        runnable = [p for p in prepared if p[2] is None]
        if runnable:
            emit({"type": "status",
                  "text": f"Running {len(runnable)} tool(s)"})
            run_results = await asyncio.gather(
                *(self._timed(name, args) for name, args, _ in runnable)
            )
        else:
            run_results = []

        out = []
        it = iter(run_results)
        for name, args, denial in prepared:
            if denial is None:
                out.append(next(it))
            else:
                out.append((f"Denied: {denial}", 0))
        return out

    async def _timed(self, name, args):
        started = time.monotonic()
        try:
            result = await self.tools.execute(name, args)
        except Exception as e:
            result = f"Tool error: {type(e).__name__}: {e}"
        return str(result), int((time.monotonic() - started) * 1000)

    # ------------------------------------------------------ usage / calib
    def _on_usage(self, u, acc):
        try:
            p = int(u.get("prompt_tokens") or 0)
            c = int(u.get("completion_tokens") or 0)
            details = u.get("prompt_tokens_details") or {}
            cached = int(details.get("cached_tokens") or 0)
        except (TypeError, ValueError):
            return
        acc["prompt"] += p
        acc["completion"] += c
        acc["cached"] += cached
        acc["calls"] += 1
        self._calibrate(p)

    def _calibrate(self, actual):
        est = self._pending_est
        if not actual or est < 100 or actual < 100:
            return
        ratio = max(0.25, min(4.0, actual / est))
        new = 0.7 * get_token_scale() + 0.3 * ratio
        if abs(new - get_token_scale()) > 0.01:
            set_token_scale(new)
            try:
                self.memory.set_meta("token_scale", f"{new:.3f}")
            except Exception:
                pass

    def _usage_final(self, emit, acc, session):
        if not acc.get("calls"):
            return
        try:
            emit({"type": "usage",
                  "prompt": acc["prompt"],
                  "completion": acc["completion"],
                  "cached": acc["cached"],
                  "calls": acc["calls"]})
        except Exception:
            pass
        # accumulate per-session totals for /usage
        try:
            prev = json.loads(self.memory.get_meta(f"usage:{session}", "{}"))
            merged = {
                "prompt": int(prev.get("prompt", 0)) + acc["prompt"],
                "completion": int(prev.get("completion", 0)) + acc["completion"],
                "cached": int(prev.get("cached", 0)) + acc["cached"],
                "calls": int(prev.get("calls", 0)) + acc["calls"],
            }
            self.memory.set_meta(f"usage:{session}", json.dumps(merged))
        except Exception:
            pass

    def _estimate(self, messages):
        return sum(
            estimate_tokens(m.get("content") or "") + 4
            for m in messages
        )

    # ------------------------------------------------------------ context
    def _pending_rows(self, session):
        summary = self.memory.get_summary(session)
        upto = summary["upto"] if summary else 0
        rows = self.memory.recent(session, limit=MAX_FETCH)
        return [r for r in rows if r["id"] > upto]

    async def _build_messages(self, session, text, emit, usage=None):
        usage = usage or _empty_usage()
        summary = self.memory.get_summary(session)
        pending = self._pending_rows(session)

        def fits():
            total = estimate_tokens(SYSTEM_PROMPT)
            if summary:
                total += estimate_tokens(summary["content"]) + 10
            total += sum(
                estimate_tokens(m["content"]) + 4 for m in pending
            )
            total += estimate_tokens(text) + 4
            return total <= self.budget

        dropped = []
        while pending and not fits():
            dropped.append(pending.pop(0))

        if len(dropped) >= self.summary_trigger:
            new = await self._summarize(summary, dropped, emit, usage)
            if new:
                new = cap_text(new, 6000)
                self.memory.save_summary(
                    session, new, upto=dropped[-1]["id"]
                )
                summary = {"content": new, "upto": dropped[-1]["id"]}
                emit({
                    "type": "info",
                    "text": f"context compacted: {len(dropped)} messages "
                            f"folded into summary "
                            f"(~{estimate_tokens(new)} tokens, "
                            f"budget {self.budget})",
                })
        elif dropped:
            # below trigger: kept for next time, they are re-fetched
            emit({
                "type": "info",
                "text": f"history trimmed to budget "
                        f"({len(dropped)} messages pending compaction)",
            })

        messages = [{"role": "system", "content": SYSTEM_PROMPT}]
        if summary:
            messages.append({
                "role": "system",
                "content": "Earlier conversation summary:\n"
                           + summary["content"],
            })
        messages.extend(
            {"role": m["role"], "content": m["content"]} for m in pending
        )
        messages.append({"role": "user", "content": text})
        return messages

    async def _summarize(self, summary, rows, emit, usage=None):
        usage = usage or _empty_usage()
        emit({"type": "status", "text": "Compacting context"})
        convo = "\n".join(
            f"{m['role']}: {cap_text(m['content'], 800)}" for m in rows
        )
        prev = ""
        if summary:
            prev = f"\nExisting summary:\n{summary['content']}\n"
        prompt = (
            "You maintain a compact running summary of a conversation "
            "between a user and a CLI agent. Merge the existing summary "
            "with the new messages below.\n"
            "Use exactly these sections (omit empty ones, "
            "max 300 words total, plain text bullets):\n"
            "GOALS: user objectives and constraints\n"
            "DECIDED: decisions and agreements\n"
            "FILES: important file paths, commands run, key outputs\n"
            "OPEN: unresolved issues and pending work\n"
            "FACTS: durable facts about environment/user preferences\n"
            "Drop pleasantries and stale detail.\n"
            f"{prev}\nNew messages:\n{convo}"
        )
        try:
            self._pending_est = estimate_tokens(prompt)
            resp = await self.llm.chat(
                [{"role": "user", "content": prompt}],
                tools=None,
                on_status=lambda s: emit({"type": "status", "text": s}),
                on_usage=lambda u: self._on_usage(u, usage),
            )
            text = (resp.get("content") or "").strip()
            return text or None
        except Exception as e:
            emit({
                "type": "info",
                "text": f"compaction skipped ({type(e).__name__}: {e})",
            })
            return None

    def _clear_stale_tool_outputs(self, messages, emit):
        """Lever 2: replace old in-turn tool outputs with a placeholder."""
        tool_idx = [
            i for i, m in enumerate(messages) if m["role"] == "tool"
        ]
        if len(tool_idx) <= KEEP_TOOL_OUTPUTS:
            return

        limit = int(self.budget * 1.5)   # a single turn may exceed the budget
        if self._estimate(messages) <= limit:
            return

        keep = set(tool_idx[-KEEP_TOOL_OUTPUTS:])
        cleared = 0
        for i in tool_idx:
            if i in keep:
                break
            if messages[i]["content"] != CLEARED:
                messages[i]["content"] = CLEARED
                cleared += 1
            if self._estimate(messages) <= limit:
                break

        if cleared:
            emit({
                "type": "info",
                "text": f"cleared {cleared} stale tool outputs "
                        f"(kept newest {KEEP_TOOL_OUTPUTS})",
            })
