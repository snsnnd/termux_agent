"""SQLite persistence: sessions, message history, rolling summaries, meta.

Session model (one DB for all sessions):
- messages(session, role, content, timestamp)   -> verbatim recent turns
- summaries(session PRIMARY KEY, content, upto) -> compressed older context;
  `upto` is the message id up to which the summary already covers, so the
  context builder only fetches rows with id > upto
- meta(key, value)                              -> e.g. last active session
"""

import sqlite3
import time
from pathlib import Path


class Memory:
    def __init__(self, path="./data/memory.db"):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
        self.db.execute("""
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session TEXT,
                role TEXT,
                content TEXT,
                timestamp REAL
            )
        """)
        self.db.execute("""
            CREATE TABLE IF NOT EXISTS summaries (
                session TEXT PRIMARY KEY,
                content TEXT,
                upto INTEGER,
                timestamp REAL
            )
        """)
        self.db.execute("""
            CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        """)
        self.db.execute(
            "CREATE INDEX IF NOT EXISTS idx_msg_session ON messages(session, id)"
        )
        self.db.commit()

    # ------------------------------------------------------------ messages
    def add(self, session, role, content):
        self.db.execute(
            "INSERT INTO messages (session, role, content, timestamp)"
            " VALUES (?, ?, ?, ?)",
            (session, role, content, time.time()),
        )
        self.db.commit()

    def recent(self, session, limit=300):
        """Most recent `limit` rows, ascending, each {'id','role','content'}."""
        rows = self.db.execute(
            """
            SELECT id, role, content
            FROM messages
            WHERE session=?
            ORDER BY id DESC
            LIMIT ?
            """,
            (session, limit),
        ).fetchall()
        rows.reverse()
        return [
            {"id": r[0], "role": r[1], "content": r[2]}
            for r in rows
        ]

    def count(self, session):
        return self.db.execute(
            "SELECT COUNT(*) FROM messages WHERE session=?",
            (session,),
        ).fetchone()[0]

    def clear(self, session):
        self.db.execute("DELETE FROM messages WHERE session=?", (session,))
        self.db.execute("DELETE FROM summaries WHERE session=?", (session,))
        self.db.commit()

    # ------------------------------------------------------------ summaries
    def get_summary(self, session):
        row = self.db.execute(
            "SELECT content, upto FROM summaries WHERE session=?",
            (session,),
        ).fetchone()
        if not row:
            return None
        return {"content": row[0], "upto": row[1]}

    def save_summary(self, session, content, upto):
        self.db.execute(
            """
            INSERT INTO summaries (session, content, upto, timestamp)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(session) DO UPDATE SET
                content=excluded.content,
                upto=excluded.upto,
                timestamp=excluded.timestamp
            """,
            (session, content, upto, time.time()),
        )
        self.db.commit()

    # ------------------------------------------------------------ sessions
    def sessions(self):
        rows = self.db.execute(
            """
            SELECT session, COUNT(*), MAX(timestamp)
            FROM messages
            GROUP BY session
            ORDER BY MAX(timestamp) DESC
            """
        ).fetchall()
        return [
            {"session": r[0], "count": r[1], "last": r[2]}
            for r in rows
        ]

    def session_exists(self, session):
        return self.db.execute(
            "SELECT 1 FROM messages WHERE session=? LIMIT 1",
            (session,),
        ).fetchone() is not None

    # ---------------------------------------------------------------- meta
    def get_meta(self, key, default=None):
        row = self.db.execute(
            "SELECT value FROM meta WHERE key=?", (key,)
        ).fetchone()
        return row[0] if row else default

    def set_meta(self, key, value):
        self.db.execute(
            "INSERT INTO meta (key, value) VALUES (?, ?)"
            " ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, str(value)),
        )
        self.db.commit()

    def meta_like(self, prefix):
        rows = self.db.execute(
            "SELECT key, value FROM meta WHERE key LIKE ?",
            (prefix + "%",),
        ).fetchall()
        return {r[0]: r[1] for r in rows}

    def close(self):
        self.db.close()
