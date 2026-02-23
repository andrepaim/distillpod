"""
Snip engine — extracts text from pre-computed transcript by timestamp range.
Zero latency, zero cost. Optionally generates a GPT-4o-mini summary.
"""
import uuid
from datetime import datetime, timezone
from openai import AsyncOpenAI
from config import settings
from models import Snip
from services.transcriber import get_transcript_words


async def create_snip(
    episode_id: str,
    podcast_id: str,
    episode_title: str,
    podcast_title: str,
    current_seconds: float,
    with_summary: bool = False,
) -> Snip:
    """
    Extract the last N seconds of transcript up to current_seconds.
    Optionally generates a short GPT-4o-mini summary.
    """
    context = settings.snip_context_seconds
    start = max(0.0, current_seconds - context)
    end = current_seconds

    words = await get_transcript_words(episode_id)
    if not words:
        raise ValueError(f"No transcript available for episode {episode_id}")

    # Filter words in time window
    snip_words = [w for w in words if w["start"] >= start and w["end"] <= end + 1.0]
    text = " ".join(w["word"].strip() for w in snip_words).strip()

    if not text:
        raise ValueError("No transcribed content in the selected time range")

    # Optional: GPT-4o-mini summary
    summary = None
    if with_summary and settings.openai_api_key:
        client = AsyncOpenAI(api_key=settings.openai_api_key)
        response = await client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": "You are a helpful assistant that summarizes podcast quotes in 1-2 sentences. Be concise and capture the core insight."},
                {"role": "user", "content": f"Summarize this podcast quote:\n\n{text}"},
            ],
            max_tokens=100,
        )
        summary = response.choices[0].message.content.strip()

    snip = Snip(
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
    return snip
