import sqlite3
import time


class Memory:
    def __init__(self, path="./data/memory.db"):
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
        self.db.commit()

    def add(self, session, role, content):
        self.db.execute(
            """
            INSERT INTO messages
            (session, role, content, timestamp)
            VALUES (?, ?, ?, ?)
            """,
            (session, role, content, time.time())
        )
        self.db.commit()

    def recent(self, session, limit=30):
        rows = self.db.execute(
            """
            SELECT role, content
            FROM messages
            WHERE session=?
            ORDER BY id DESC
            LIMIT ?
            """,
            (session, limit)
        ).fetchall()

        rows.reverse()

        return [
            {
                "role": role,
                "content": content
            }
            for role, content in rows
        ]
