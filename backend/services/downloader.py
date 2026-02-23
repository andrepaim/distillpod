"""Downloads podcast episode audio to local VPS storage."""
import asyncio
import hashlib
import httpx
from pathlib import Path
from config import settings


def episode_local_path(episode_id: str, audio_url: str) -> Path:
    ext = Path(audio_url.split("?")[0]).suffix or ".mp3"
    safe_id = hashlib.md5(episode_id.encode()).hexdigest()
    return settings.media_dir / f"{safe_id}{ext}"


async def download_episode(episode_id: str, audio_url: str) -> Path:
    """Download audio file to media_dir. Returns local path."""
    settings.media_dir.mkdir(parents=True, exist_ok=True)
    dest = episode_local_path(episode_id, audio_url)

    if dest.exists():
        return dest  # Already downloaded

    async with httpx.AsyncClient(follow_redirects=True, timeout=300) as client:
        async with client.stream("GET", audio_url) as r:
            r.raise_for_status()
            with open(dest, "wb") as f:
                async for chunk in r.aiter_bytes(chunk_size=65536):
                    f.write(chunk)

    return dest


def delete_episode(episode_id: str, audio_url: str) -> None:
    path = episode_local_path(episode_id, audio_url)
    if path.exists():
        path.unlink()
