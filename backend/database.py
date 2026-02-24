import aiosqlite
import json
from pathlib import Path
from config import settings

DB_PATH = str(settings.db_path)

SCHEMA = """
CREATE TABLE IF NOT EXISTS subscriptions (
    podcast_id   TEXT PRIMARY KEY,
    feed_url     TEXT NOT NULL,
    title        TEXT NOT NULL,
    image_url    TEXT,
    last_checked TEXT,
    subscribed_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS episodes (
    id                TEXT PRIMARY KEY,
    podcast_id        TEXT NOT NULL,
    title             TEXT NOT NULL,
    description       TEXT,
    audio_url         TEXT NOT NULL,
    duration_seconds  INTEGER,
    published_at      TEXT,
    image_url         TEXT,
    downloaded        INTEGER DEFAULT 0,
    local_path        TEXT,
    transcript_status TEXT DEFAULT 'none',
    FOREIGN KEY (podcast_id) REFERENCES subscriptions(podcast_id)
);

CREATE TABLE IF NOT EXISTS transcripts (
    episode_id  TEXT PRIMARY KEY,
    words_json  TEXT NOT NULL,       -- JSON array of {word, start, end}
    language    TEXT,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS shots (
    id             TEXT PRIMARY KEY,
    episode_id     TEXT NOT NULL,
    podcast_id     TEXT NOT NULL,
    episode_title  TEXT NOT NULL,
    podcast_title  TEXT NOT NULL,
    start_seconds  REAL NOT NULL,
    end_seconds    REAL NOT NULL,
    text           TEXT NOT NULL,
    summary        TEXT,
    created_at     TEXT NOT NULL
);
"""

async def get_db() -> aiosqlite.Connection:
    db = await aiosqlite.connect(DB_PATH)
    db.row_factory = aiosqlite.Row

    # Convenience helpers (aiosqlite doesn't have these natively)
    async def _fetchone(sql, params=()):
        cursor = await db.execute(sql, params)
        return await cursor.fetchone()

    async def _fetchall(sql, params=()):
        cursor = await db.execute(sql, params)
        return await cursor.fetchall()

    db.execute_fetchone = _fetchone
    db.execute_fetchall = _fetchall
    return db

async def init_db():
    Path(DB_PATH).parent.mkdir(parents=True, exist_ok=True)
    async with aiosqlite.connect(DB_PATH) as db:
        await db.executescript(SCHEMA)
        await db.commit()
