"""
Transcription via faster-whisper.
Runs in a background thread (CPU-bound) and stores word-level timestamps in DB.
"""
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional
import aiosqlite
from faster_whisper import WhisperModel
from config import settings
from database import get_db

# Lazy-load model (expensive to init)
_model: Optional[WhisperModel] = None

def _get_model() -> WhisperModel:
    global _model
    if _model is None:
        _model = WhisperModel(
            settings.whisper_model,
            device=settings.whisper_device,
            compute_type="int8",
        )
    return _model


def _transcribe_sync(audio_path: str) -> list[dict]:
    """Synchronous transcription — runs in thread pool."""
    model = _get_model()
    segments, _ = model.transcribe(audio_path, word_timestamps=True)
    words = []
    for segment in segments:
        if segment.words:
            for w in segment.words:
                words.append({"word": w.word, "start": w.start, "end": w.end})
    return words


async def transcribe_episode(episode_id: str, audio_path: Path) -> None:
    """
    Transcribe episode in background thread, save words to DB.
    Updates transcript_status in episodes table throughout.
    """
    db = await get_db()
    try:
        # Mark as processing
        await db.execute(
            "UPDATE episodes SET transcript_status = 'processing' WHERE id = ?",
            (episode_id,)
        )
        await db.commit()

        # Run CPU-bound transcription in thread pool
        loop = asyncio.get_event_loop()
        words = await loop.run_in_executor(None, _transcribe_sync, str(audio_path))

        # Save transcript
        await db.execute(
            """INSERT OR REPLACE INTO transcripts (episode_id, words_json, language, created_at)
               VALUES (?, ?, ?, ?)""",
            (episode_id, json.dumps(words), "auto", datetime.now(timezone.utc).isoformat())
        )
        await db.execute(
            "UPDATE episodes SET transcript_status = 'done' WHERE id = ?",
            (episode_id,)
        )
        await db.commit()

    except Exception as e:
        await db.execute(
            "UPDATE episodes SET transcript_status = 'error' WHERE id = ?",
            (episode_id,)
        )
        await db.commit()
        raise e
    finally:
        await db.close()


async def get_transcript_words(episode_id: str) -> list[dict]:
    db = await get_db()
    row = await db.execute_fetchone(
        "SELECT words_json FROM transcripts WHERE episode_id = ?", (episode_id,)
    )
    await db.close()
    if not row:
        return []
    return json.loads(row["words_json"])
