"""
Gist engine — extracts text from pre-computed transcript by timestamp range.
Zero latency, zero cost. Optionally generates a Claude summary via CLI subprocess.
"""
import asyncio
import subprocess
import uuid
from datetime import datetime, timezone

from config import settings
from models import Gist
from services.transcriber import get_transcript_words


def _claude_summarize_sync(text: str) -> str | None:
    """
    Blocking subprocess call to `claude --print`. Runs in a thread pool
    so it doesn't block the async event loop.
    Uses the Claude Max subscription already authenticated on this VPS.
    """
    prompt = (
        "From this podcast transcript excerpt, extract two things:\n"
        "1. The single most memorable/quotable sentence — pick verbatim from the text\n"
        "2. A 1-2 sentence insight capturing the core idea\n\n"
        "Respond with ONLY valid JSON, no markdown, no extra text:\n"
        "{\"quote\": \"...\", \"insight\": \"...\"}\n\n"
        f"Transcript:\n{text}"
    )
    result = subprocess.run(
        ["/root/.local/bin/claude", "--print", prompt],
        capture_output=True,
        text=True,
        timeout=60,
    )
    if result.returncode != 0:
        return None
    out = result.stdout.strip()
    # Strip markdown code fences if Claude ignores the "no markdown" instruction
    if out.startswith("```"):
        out = out.split("\n", 1)[-1]  # drop first line (```json)
        if out.endswith("```"):
            out = out.rsplit("```", 1)[0]
    return out.strip() or None


async def create_gist(
    episode_id: str,
    podcast_id: str,
    episode_title: str,
    podcast_title: str,
    current_seconds: float,
    with_summary: bool = False,
) -> Gist:
    """
    Extract the last N seconds of transcript up to current_seconds.
    Optionally generates a Claude summary (via CLI subprocess, free with Max subscription).
    """
    context = settings.gist_context_seconds
    start = max(0.0, current_seconds - context)
    end = current_seconds

    words = await get_transcript_words(episode_id)
    if not words:
        raise ValueError(f"No transcript available for episode {episode_id}")

    # Filter words in time window
    shot_words = [w for w in words if w["start"] >= start and w["end"] <= end + 1.0]
    text = " ".join(w["word"].strip() for w in shot_words).strip()

    if not text:
        raise ValueError("No transcribed content in the selected time range")

    # Optional: Claude summary via subprocess (non-blocking, runs in thread pool)
    summary = None
    if with_summary:
        summary = await asyncio.to_thread(_claude_summarize_sync, text)

    gist = Gist(
        id=str(uuid.uuid4()),
        episode_id=episode_id,
        podcast_id=podcast_id,
        episode_title=episode_title,
        podcast_title=podcast_title,
        start_seconds=start,
        end_seconds=end,
        text=text,
        summary=summary,
        created_at=datetime.now(timezone.utc),
    )
    return gist
