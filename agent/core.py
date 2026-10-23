"""Agent loop (ReAct-style tool calling) with context budgeting & compaction.

Context design (follows common production-agent practice):

    [system prompt]              verbatim, byte-stable
  + [rolling summary]            compressed old turns (summary table, upto id)
  + [recent turns]               verbatim, trimmed to fit token budget
  + [current user message]       always kept

Compression levers, cheapest first:
  1. tool-result capping      - one huge shell dump never enters context
  2. tool-output clearing     - within a turn, stale tool outputs are replaced
                                by a placeholder (last few kept verbatim)
  3. rolling summarization    - when trimmed history reaches `summary_trigger`
                                messages, the LLM folds them into the summary

Events emitted via `emit(event)` (rendered by ui.UI):
  {"type":"status",  "text": ...}   waiting/retrying indicator
  {"type":"tool",    "name","args","index","total"}
  {"type":"tool_result","result","ms"}
  {"type":"answer",  "text": ...}   final answer (when nothing was streamed)
  {"type":"stream_end"}             final answer was streamed, close the block
  {"type":"info",    "text": ...}   compaction/pruning notes
"""

import time

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
""".strip()

CLEARED = "[old tool output cleared to save context]"
MAX_FETCH = 300          # hard cap on rows fetched from DB per turn
KEEP_TOOL_OUTPUTS = 4    # newest tool outputs kept verbatim inside one turn


def estimate_tokens(text):
    """Rough, deliberately conservative token estimate (CJK aware)."""
    if not text:
        return 0
    cjk = 0
    for ch in text:
        o = ord(ch)
        if (0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF
                or 0x3000 <= o <= 0x303F or 0xFF00 <= o <= 0xFFEF):
            cjk += 1
    other = len(text) - cjk
    return int(other / 4 + cjk * 0.9) + 1


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


class Agent:
    def __init__(self, llm, tools, memory, max_rounds=10, context=None):
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

    # ------------------------------------------------------------- public
    async def run(self, text, session="cli", emit=None, on_delta=None,
                  on_think=None):
        emit = emit or (lambda e: None)
        emit({"type": "status", "text": "Thinking"})

        # build first so the current message is not also in history
        messages = await self._build_messages(session, text, emit)
        self.memory.add(session, "user", text)

        streamed = [0]

        def delta(t):
            if t:
                streamed[0] += len(t)
                if on_delta:
                    on_delta(t)

        think = on_think or (lambda t: None)
        on_status = lambda s: emit({"type": "status", "text": s})

        for _ in range(self.max_rounds):
            self._clear_stale_tool_outputs(messages, emit)
            emit({"type": "status", "text": "Thinking"})

            message = await self.llm.chat(
                messages,
                self.tools.definitions(),
                on_delta=delta,
                on_think=think,
                on_status=on_status,
            )
            messages.append(message)

            tool_calls = message.get("tool_calls")
            if not tool_calls:
                answer = (message.get("content") or "").strip()
                if not answer:
                    answer = "(empty response)"
                return self._finish(emit, session, answer, streamed[0])

            total = len(tool_calls)
            for i, call in enumerate(tool_calls, start=1):
                fn = call.get("function") or {}
                name = fn.get("name", "?")
                args = fn.get("arguments", "{}")

                emit({
                    "type": "tool",
                    "name": name,
                    "args": args,
                    "index": i,
                    "total": total,
                })

                started = time.monotonic()
                result = await self.tools.execute(name, args)
                ms = int((time.monotonic() - started) * 1000)

                emit({"type": "tool_result", "result": str(result), "ms": ms})

                messages.append({
                    "role": "tool",
                    "tool_call_id": call.get("id", ""),
                    "content": cap_text(str(result), self.tool_result_limit),
                })

        answer = "Reached maximum tool rounds; rephrase or continue."
        return self._finish(emit, session, answer, streamed[0])

    async def compact(self, session="cli", emit=None):
        """/compact - fold ALL pending history into the summary now."""
        emit = emit or (lambda e: None)
        rows = self._pending_rows(session)
        if len(rows) < 2:
            emit({"type": "info", "text": "nothing to compact"})
            return
        summary = self.memory.get_summary(session)
        new = await self._summarize(summary, rows, emit)
        if new:
            self.memory.save_summary(session, new, upto=rows[-1]["id"])
            emit({
                "type": "info",
                "text": f"compacted {len(rows)} messages into summary "
                        f"(~{estimate_tokens(new)} tokens)",
            })

    def clear(self, session="cli", emit=None):
        emit = emit or (lambda e: None)
        self.memory.clear(session)
        emit({"type": "info", "text": f"session '{session}' cleared"})

    # ------------------------------------------------------------ internals
    def _finish(self, emit, session, answer, streamed):
        self.memory.add(session, "assistant", answer)
        if streamed:
            emit({"type": "stream_end"})
        else:
            emit({"type": "answer", "text": answer})
        return answer

    def _pending_rows(self, session):
        summary = self.memory.get_summary(session)
        upto = summary["upto"] if summary else 0
        rows = self.memory.recent(session, limit=MAX_FETCH)
        return [r for r in rows if r["id"] > upto]

    async def _build_messages(self, session, text, emit):
        summary = self.memory.get_summary(session)
        pending = self._pending_rows(session)
        current = {"role": "user", "content": text}

        # system + summary are fixed; trim oldest history until we fit
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
            new = await self._summarize(summary, dropped, emit)
            if new:
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
            # below trigger: keep them for next time, they are re-fetched
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
        messages.append(current)
        return messages

    async def _summarize(self, summary, rows, emit):
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
            "Preserve: user goals and constraints, decisions made, file "
            "paths and commands that matter later, unresolved issues.\n"
            "Drop pleasantries and stale detail. Max 250 words, plain text, "
            "short bullets.\n"
            f"{prev}\nNew messages:\n{convo}"
        )
        try:
            resp = await self.llm.chat(
                [{"role": "user", "content": prompt}],
                tools=None,
                on_status=lambda s: emit({"type": "status", "text": s}),
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

        def total_tokens():
            return sum(
                estimate_tokens(m.get("content") or "") + 4
                for m in messages
            )

        limit = int(self.budget * 1.5)   # a single turn may exceed the budget
        if total_tokens() <= limit:
            return

        keep = set(tool_idx[-KEEP_TOOL_OUTPUTS:])
        cleared = 0
        for i in tool_idx:
            if i in keep:
                break
            if messages[i]["content"] != CLEARED:
                messages[i]["content"] = CLEARED
                cleared += 1
            if total_tokens() <= limit:
                break

        if cleared:
            emit({
                "type": "info",
                "text": f"cleared {cleared} stale tool outputs "
                        f"(kept newest {KEEP_TOOL_OUTPUTS})",
            })
