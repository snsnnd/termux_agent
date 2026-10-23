import os
import subprocess
import json
from pathlib import Path


class ToolRouter:
    def __init__(self, workspace):
        self.workspace = Path(workspace).resolve()
        self.workspace.mkdir(parents=True, exist_ok=True)

    def safe_path(self, path):
        target = (self.workspace / path).resolve()

        if target != self.workspace and self.workspace not in target.parents:
            raise PermissionError("Path escapes workspace")

        return target

    def definitions(self):
        return [
            {
                "type": "function",
                "function": {
                    "name": "shell",
                    "description": "Execute a shell command inside the workspace.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "command": {"type": "string"}
                        },
                        "required": ["command"]
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "read_file",
                    "description": "Read a UTF-8 text file from the workspace.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string"}
                        },
                        "required": ["path"]
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "write_file",
                    "description": "Create or overwrite a text file.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string"},
                            "content": {"type": "string"}
                        },
                        "required": ["path", "content"]
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "list_files",
                    "description": "List files in a workspace directory.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string"}
                        }
                    }
                }
            }
        ]

    async def execute(self, name, arguments):
        try:
            if isinstance(arguments, str):
                args = json.loads(arguments)
            else:
                args = arguments

            if name == "shell":
                return self.shell(args["command"])

            if name == "read_file":
                return self.read_file(args["path"])

            if name == "write_file":
                return self.write_file(
                    args["path"],
                    args["content"]
                )

            if name == "list_files":
                return self.list_files(args.get("path", "."))

            return f"Unknown tool: {name}"

        except Exception as e:
            return f"Tool error: {type(e).__name__}: {e}"

    def shell(self, command):
        # MVP：仅在 workspace 中执行。
        # 后续接 QQ 前还要增加 owner 权限和危险命令策略。
        result = subprocess.run(
            command,
            shell=True,
            cwd=self.workspace,
            capture_output=True,
            text=True,
            timeout=60
        )

        output = ""

        if result.stdout:
            output += result.stdout

        if result.stderr:
            output += "\nSTDERR:\n" + result.stderr

        output += f"\nexit_code={result.returncode}"

        return output[-20000:]

    def read_file(self, path):
        target = self.safe_path(path)

        if not target.is_file():
            return "File does not exist"

        return target.read_text(
            encoding="utf-8",
            errors="replace"
        )[:50000]

    def write_file(self, path, content):
        target = self.safe_path(path)
        target.parent.mkdir(parents=True, exist_ok=True)

        target.write_text(
            content,
            encoding="utf-8"
        )

        return f"Wrote {len(content)} characters to {path}"

    def list_files(self, path="."):
        target = self.safe_path(path)

        if not target.exists():
            return "Path does not exist"

        if not target.is_dir():
            return str(target.relative_to(self.workspace))

        result = []

        for item in sorted(target.iterdir()):
            suffix = "/" if item.is_dir() else ""
            result.append(
                str(item.relative_to(self.workspace)) + suffix
            )

        return "\n".join(result[:500])
