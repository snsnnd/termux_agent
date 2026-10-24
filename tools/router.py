"""Tool layer: definitions, execution, background jobs, write snapshots.

Design notes:
- execute() dispatches via asyncio.to_thread, so core can gather() several
  tool calls and they really run in parallel (shell included).
- write_file/edit_file snapshot the previous file version into
  <workspace>/.snapshots first; /undo restores them step by step.
- shell(background=true) detaches long commands into <workspace>/.agent_jobs
  with a log file, read back through the `job` tool.
- is_safe_command() powers the approval policy in agent/core.py.
"""

import asyncio
import json
import os
import shlex
import shutil
import signal
import subprocess
import time
from pathlib import Path

# Directories never touched by grep/glob walk
HIDDEN_DIRS = {".snapshots", ".agent_jobs", ".git", "node_modules", "__pycache__"}

# Commands allowed to run without approval in `ask` mode:
# read-only and side-effect free (no redirection/pipes/substitutions,
# enforced together with the metacharacter check below).
SAFE_COMMAND_BINS = {
    "ls", "cat", "head", "tail", "wc", "grep", "rg", "sort", "uniq",
    "cut", "tr", "diff", "cmp", "find", "tree", "pwd", "echo", "printf",
    "date", "uname", "uptime", "df", "du", "which", "file", "stat",
    "id", "whoami", "env", "printenv", "ps", "sleep", "true", "false",
    "test", "basename", "dirname", "realpath", "md5sum", "sha1sum",
    "sha256sum", "cksum", "nl", "rev", "comm", "join", "od", "hexdump",
    "xxd", "strings", "cat", "seq", "expr",
}

UNSAFE_CHARS = set(";|&><`$\n")


def is_safe_command(command):
    """True only for plain read-only commands with no shell tricks."""
    cmd = (command or "").strip()
    if not cmd:
        return False
    if any(ch in cmd for ch in UNSAFE_CHARS):
        return False
    try:
        parts = shlex.split(cmd)
    except ValueError:
        return False
    if not parts:
        return False
    return Path(parts[0]).name in SAFE_COMMAND_BINS


def _shell_exe():
    # Termux: /bin/sh does not exist inside the app sandbox, shell=True
    # would fail -> use $SHELL (Termux sets it) with Linux fallbacks.
    return (
        os.environ.get("SHELL")
        or shutil.which("bash")
        or shutil.which("sh")
        or "/system/bin/sh"
    )


class ToolRouter:
    def __init__(self, workspace):
        self.workspace = Path(workspace).resolve()
        self.workspace.mkdir(parents=True, exist_ok=True)
        self.snapshots = self.workspace / ".snapshots"
        self.jobs_dir = self.workspace / ".agent_jobs"
        self.jobs_dir.mkdir(exist_ok=True)

        self._jobs = {}      # id -> {"proc", "cmd", "log", "started"}
        self._seq = 0

        self._dispatch = {
            "shell": lambda **kw: self.shell(**kw),
            "job": lambda **kw: self.job(**kw),
            "read_file": lambda **kw: self.read_file(**kw),
            "write_file": lambda **kw: self.write_file(**kw),
            "edit_file": lambda **kw: self.edit_file(**kw),
            "list_files": lambda **kw: self.list_files(**kw),
            "grep_files": lambda **kw: self.grep_files(**kw),
            "glob_files": lambda **kw: self.glob_files(**kw),
        }

    # ------------------------------------------------------------- schema
    def definitions(self):
        return [
            {
                "type": "function",
                "function": {
                    "name": "shell",
                    "description": (
                        "Execute a shell command inside the workspace. "
                        "For long tasks (builds, downloads) use "
                        "background=true and read output with the job tool."
                    ),
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "command": {"type": "string"},
                            "timeout": {
                                "type": "integer",
                                "description": "seconds 1-3600, default 60",
                            },
                            "background": {
                                "type": "boolean",
                                "description": (
                                    "detach and return a job id immediately"
                                ),
                            },
                        },
                        "required": ["command"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "job",
                    "description": (
                        "Manage background jobs started with "
                        "shell(background=true): list status, tail logs, kill."
                    ),
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "action": {
                                "type": "string",
                                "enum": ["list", "tail", "kill"],
                            },
                            "id": {
                                "type": "string",
                                "description": "job id (not needed for list)",
                            },
                            "lines": {
                                "type": "integer",
                                "description": "tail lines, default 40",
                            },
                        },
                        "required": ["action"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "read_file",
                    "description": (
                        "Read a UTF-8 text file from the workspace, "
                        "numbered lines, paginated."
                    ),
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string"},
                            "offset": {
                                "type": "integer",
                                "description": "first line to read, 0-based",
                            },
                            "limit": {
                                "type": "integer",
                                "description": "max lines, default 200",
                            },
                        },
                        "required": ["path"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "write_file",
                    "description": (
                        "Create or overwrite a text file. An existing file "
                        "is snapshotted first (restorable via /undo)."
                    ),
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string"},
                            "content": {"type": "string"},
                        },
                        "required": ["path", "content"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "edit_file",
                    "description": (
                        "Replace an exact string in a file. old_string must "
                        "match exactly once unless replace_all=true; prefer "
                        "this over write_file for edits."
                    ),
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string"},
                            "old_string": {"type": "string"},
                            "new_string": {"type": "string"},
                            "replace_all": {"type": "boolean"},
                        },
                        "required": ["path", "old_string", "new_string"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "list_files",
                    "description": "List files in a workspace directory.",
                    "parameters": {
                        "type": "object",
                        "properties": {"path": {"type": "string"}},
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "grep_files",
                    "description": (
                        "Search file contents recursively without running "
                        "shell. Returns file:line:content matches."
                    ),
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "pattern": {"type": "string"},
                            "path": {
                                "type": "string",
                                "description": "root dir, default .",
                            },
                            "glob": {
                                "type": "string",
                                "description": 'filter, e.g. "*.py"',
                            },
                            "regex": {"type": "boolean", "default": True},
                            "ignore_case": {"type": "boolean", "default": False},
                            "max_results": {
                                "type": "integer",
                                "default": 100,
                            },
                        },
                        "required": ["pattern"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "glob_files",
                    "description": (
                        "Find file paths by pattern, e.g. '**/*.py'."
                    ),
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "pattern": {"type": "string"},
                            "max_results": {
                                "type": "integer",
                                "default": 200,
                            },
                        },
                        "required": ["pattern"],
                    },
                },
            },
        ]

    async def execute(self, name, arguments):
        try:
            if isinstance(arguments, str):
                args = json.loads(arguments)
            else:
                args = arguments or {}
            if not isinstance(args, dict):
                return "Tool error: arguments must be a JSON object"

            fn = self._dispatch.get(name)
            if fn is None:
                return f"Unknown tool: {name}"

            # run in a worker thread so parallel tool calls really overlap
            return await asyncio.to_thread(fn, **args)
        except json.JSONDecodeError as e:
            return f"Tool error: invalid JSON arguments: {e}"
        except TypeError as e:
            return f"Tool error: bad arguments: {e}"
        except Exception as e:
            return f"Tool error: {type(e).__name__}: {e}"

    # ------------------------------------------------------------- shell
    def shell(self, command, timeout=60, background=False):
        if background:
            return self._start_background(command)

        timeout = max(1, min(int(timeout or 60), 3600))
        try:
            result = subprocess.run(
                command,
                shell=True,
                executable=_shell_exe(),
                cwd=self.workspace,
                capture_output=True,
                text=True,
                timeout=timeout,
                errors="replace",
            )
        except subprocess.TimeoutExpired:
            return f"Tool error: command timed out after {timeout}s " \
                   "(use background=true for longer tasks)"
        except FileNotFoundError:
            return "Tool error: shell not found"

        output = ""
        if result.stdout:
            output += result.stdout
        if result.stderr:
            output += "\nSTDERR:\n" + result.stderr
        output += f"\nexit_code={result.returncode}"
        return output[-20000:]

    def _start_background(self, command):
        self._seq += 1
        jid = f"j{self._seq:03d}_{int(time.time()) % 1000000}"
        log_path = self.jobs_dir / f"{jid}.log"
        try:
            with open(log_path, "wb") as log_f:
                proc = subprocess.Popen(
                    command,
                    shell=True,
                    executable=_shell_exe(),
                    cwd=self.workspace,
                    stdout=log_f,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                )
        except Exception as e:
            return f"Tool error: could not start background job: {e}"

        self._jobs[jid] = {
            "proc": proc,
            "cmd": command,
            "log": log_path,
            "started": time.time(),
        }
        return (
            f"Job {jid} started in background (detached).\n"
            f"command: {command}\n"
            f"Read output with job: action=tail, id={jid}; "
            f"status with action=list."
        )

    def job(self, action="list", id="", lines=40):
        if action == "list":
            return self._job_list()
        if action == "tail":
            return self._job_tail(id, int(lines or 40))
        if action == "kill":
            return self._job_kill(id)
        return f"Tool error: unknown job action '{action}' (list|tail|kill)"

    def _job_list(self):
        rows = []
        for jid, info in self._jobs.items():
            rc = info["proc"].poll()
            if rc is None:
                status = f"running ({int(time.time() - info['started'])}s)"
            else:
                status = f"exited rc={rc}"
            rows.append(f"{jid}  {status}  {info['cmd'][:80]}")

        # logs from previous runs are still tailable
        known = set(self._jobs)
        for p in sorted(self.jobs_dir.glob("*.log")):
            if p.stem not in known:
                rows.append(f"{p.stem}  done(log only)  --")

        if not rows:
            return "No jobs."
        return "\n".join(rows)

    def _job_tail(self, jid, lines):
        if not jid or not jid.replace("_", "").isalnum():
            return "Tool error: job id required"
        log_path = self.jobs_dir / f"{jid}.log"
        if not log_path.is_file():
            return f"Tool error: no log for job '{jid}'"
        try:
            text = log_path.read_text(encoding="utf-8", errors="replace")
        except OSError as e:
            return f"Tool error: {e}"

        all_lines = text.splitlines()
        tail = all_lines[-max(1, min(lines, 500)):]
        body = "\n".join(tail)[-8000:]
        rc = ""
        info = self._jobs.get(jid)
        if info:
            done = info["proc"].poll()
            rc = "running" if done is None else f"exited rc={done}"
        header = f"--- job {jid} last {len(tail)} lines ({rc}) ---"
        if not body:
            return header + "\n(no output yet)"
        return f"{header}\n{body}"

    def _job_kill(self, jid):
        info = self._jobs.get(jid)
        if not info:
            return f"Tool error: unknown or live job '{jid}'"
        if info["proc"].poll() is not None:
            return f"Job {jid} already exited (rc={info['proc'].returncode})"
        try:
            os.killpg(os.getpgid(info["proc"].pid), signal.SIGTERM)
        except Exception as e:
            return f"Tool error: kill failed: {e}"
        return f"Sent SIGTERM to job {jid} (pid {info['proc'].pid})"

    # ----------------------------------------------------------- files
    def safe_path(self, path):
        target = (self.workspace / path).resolve()
        if target != self.workspace and self.workspace not in target.parents:
            raise PermissionError("Path escapes workspace")
        return target

    def read_file(self, path, offset=0, limit=200):
        target = self.safe_path(path)

        if not target.is_file():
            return "File does not exist"

        size = target.stat().st_size
        if size == 0:
            return "(empty file)"

        with open(target, "rb") as f:
            if b"\x00" in f.read(8192):
                return f"Binary file ({size} bytes); refusing to display"

        capped = ""
        if size > 4_000_000:
            with open(target, "rb") as f:
                raw = f.read(4_000_000)
            capped = "\n[file larger than 4MB; showing first 4MB]"
        else:
            raw = target.read_bytes()

        lines = raw.decode("utf-8", errors="replace").splitlines()
        total = len(lines)

        offset = max(0, int(offset or 0))
        limit = max(1, min(int(limit or 200), 1000))
        chunk = lines[offset:offset + limit]

        if not chunk:
            return f"(no lines: offset {offset} >= total {total} lines)"

        out = [f"{i + 1:>6}\t{ln}" for i, ln in enumerate(chunk, start=offset)]
        text = "\n".join(out)
        if len(text) > 15000:
            text = text[:15000] + "\n... [page too wide, use smaller limit]"

        footer = f"--- lines {offset + 1}-{offset + len(chunk)} " \
                 f"of {total} ({path}) ---"
        if offset + len(chunk) < total:
            footer += f"\nnext page: offset={offset + len(chunk)}"
        return f"{text}\n{footer}{capped}"

    def write_file(self, path, content):
        target = self.safe_path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        self._snapshot(target, "write")
        target.write_text(content, encoding="utf-8")
        return f"Wrote {len(content)} characters to {path}"

    def edit_file(self, path, old_string, new_string, replace_all=False):
        target = self.safe_path(path)
        if not target.is_file():
            return "File does not exist"
        if not old_string:
            return "Tool error: old_string must not be empty"

        text = target.read_text(encoding="utf-8", errors="replace")
        count = text.count(old_string)
        if count == 0:
            return ("Tool error: old_string not found (must match exactly, "
                    "including whitespace)")
        if count > 1 and not replace_all:
            return (f"Tool error: old_string occurs {count} times; add more "
                    f"context to make it unique or set replace_all=true")

        self._snapshot(target, "edit")
        if replace_all:
            new_text = text.replace(old_string, new_string)
            n = count
        else:
            new_text = text.replace(old_string, new_string, 1)
            n = 1
        target.write_text(new_text, encoding="utf-8")
        return f"Edited {path}: replaced {n} occurrence(s)"

    def list_files(self, path="."):
        target = self.safe_path(path)

        if not target.exists():
            return "Path does not exist"

        if not target.is_dir():
            return str(target.relative_to(self.workspace))

        result = []
        for item in sorted(target.iterdir()):
            if item.name in HIDDEN_DIRS:
                continue
            suffix = "/" if item.is_dir() else ""
            result.append(str(item.relative_to(self.workspace)) + suffix)

        if not result:
            return "(empty directory)"
        return "\n".join(result[:500])

    def grep_files(self, pattern, path=".", glob=None, regex=True,
                   ignore_case=False, max_results=100):
        import fnmatch
        import re as _re

        root = self.safe_path(path)
        if not root.exists():
            return "Path does not exist"

        flags = _re.IGNORECASE if ignore_case else 0
        if regex:
            try:
                rx = _re.compile(pattern, flags)
            except _re.error as e:
                return f"Tool error: bad regex: {e}"
        else:
            needle = pattern.lower() if ignore_case else pattern

        def match(line):
            if regex:
                return rx.search(line)
            hay = line.lower() if ignore_case else line
            return needle in hay

        max_results = max(1, min(int(max_results or 100), 500))
        hits = []
        total = 0
        scanned = 0
        skipped = 0

        if root.is_file():
            candidates = [root]
        else:
            candidates = []
            for p in sorted(root.rglob("*")):
                if not p.is_file():
                    continue
                if p.suffix == ".pyc" or any(
                    part in HIDDEN_DIRS for part in p.parts
                ):
                    continue
                if glob and not fnmatch.fnmatch(p.name, glob):
                    continue
                candidates.append(p)

        for p in candidates:
            try:
                if p.stat().st_size > 2_000_000:
                    skipped += 1
                    continue
                with open(p, "rb") as f:
                    raw = f.read()
                if b"\x00" in raw:
                    skipped += 1
                    continue
                scanned += 1
                rel = str(p.relative_to(self.workspace)) \
                    if p.is_relative_to(self.workspace) else str(p)
                for i, line in enumerate(
                    raw.decode("utf-8", errors="replace").splitlines(), 1
                ):
                    if match(line):
                        total += 1
                        if len(hits) < max_results:
                            hits.append(f"{rel}:{i}:{line[:300]}")
            except OSError:
                skipped += 1

        if not hits:
            return f"No matches ({scanned} files scanned)."
        out = "\n".join(hits)
        note = f"\n--- {total} match(es) in {scanned} files"
        if skipped:
            note += f", {skipped} skipped (binary/large)"
        if total > len(hits):
            note += f"; showing first {len(hits)} (max_results)"
        return out + note + " ---"

    def glob_files(self, pattern, max_results=200):
        if not pattern:
            return "Tool error: pattern required"
        try:
            matches = sorted(self.workspace.glob(pattern))
        except ValueError as e:
            return f"Tool error: bad pattern: {e}"

        out = []
        for p in matches:
            if any(part in HIDDEN_DIRS for part in p.parts):
                continue
            rel = str(p.relative_to(self.workspace))
            out.append(rel + ("/" if p.is_dir() else ""))
            if len(out) >= max(1, int(max_results or 200)):
                break

        if not out:
            return f"No files match '{pattern}'"
        return "\n".join(out)

    # ------------------------------------------------- snapshots / undo
    def _snapshot(self, target, op):
        """Best-effort pre-write copy; never blocks the write itself."""
        try:
            if not target.is_file():
                return
            rel = target.relative_to(self.workspace)
            top = self.snapshots / f"{int(time.time() * 1000)}_{op}"
            dest = top / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, dest)
            self._prune_snapshots()
        except Exception:
            pass

    def _prune_snapshots(self, keep=100):
        try:
            dirs = sorted(
                (d for d in self.snapshots.iterdir() if d.is_dir()),
                key=lambda p: p.name,
            )
            for old in dirs[:-keep]:
                shutil.rmtree(old, ignore_errors=True)
        except OSError:
            pass

    def undo(self):
        """Restore the most recent snapshot; consumes it (step-wise undo)."""
        if not self.snapshots.is_dir():
            return "Nothing to undo"
        dirs = sorted(
            (d for d in self.snapshots.iterdir() if d.is_dir()),
            key=lambda p: p.name,
        )
        if not dirs:
            return "Nothing to undo"

        top = dirs[-1]
        ts, _, op = top.name.partition("_")
        files = [p for p in top.rglob("*") if p.is_file()]
        if not files:
            return "Nothing to undo"

        restored = []
        for src in files:
            rel = src.relative_to(top)
            dest = self.workspace / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dest)
            restored.append(str(rel))

        shutil.rmtree(top, ignore_errors=True)
        return (f"Restored {len(restored)} file(s) from {op} snapshot "
                f"({ts}): " + ", ".join(restored))
