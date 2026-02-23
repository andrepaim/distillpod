"""RSS feed parser — extracts episodes from podcast feeds."""
import feedparser
import httpx
from datetime import datetime
from models import Episode


async def fetch_episodes(feed_url: str, podcast_id: str, limit: int = 50) -> list[Episode]:
    async with httpx.AsyncClient(follow_redirects=True, timeout=30) as client:
        r = await client.get(feed_url)
        r.raise_for_status()
        content = r.text

    feed = feedparser.parse(content)
    episodes = []

    for entry in feed.entries[:limit]:
        audio_url = None
        for link in entry.get("links", []):
            if link.get("type", "").startswith("audio/"):
                audio_url = link["href"]
                break
        if not audio_url:
            enclosures = entry.get("enclosures", [])
            if enclosures:
                audio_url = enclosures[0].get("url")
        if not audio_url:
            continue

        duration = None
        raw_duration = entry.get("itunes_duration", "")
        if raw_duration:
            parts = str(raw_duration).split(":")
            try:
                if len(parts) == 3:
                    duration = int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
                elif len(parts) == 2:
                    duration = int(parts[0]) * 60 + int(parts[1])
                else:
                    duration = int(parts[0])
            except ValueError:
                pass

        published_at = None
        if hasattr(entry, "published_parsed") and entry.published_parsed:
            published_at = datetime(*entry.published_parsed[:6])

        episodes.append(Episode(
            id=entry.get("id", audio_url),
            podcast_id=podcast_id,
            title=entry.get("title", "Untitled"),
            description=entry.get("summary", ""),
            audio_url=audio_url,
            duration_seconds=duration,
            published_at=published_at,
            image_url=entry.get("image", {}).get("href"),
        ))

    return episodes
