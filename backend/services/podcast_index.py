"""
Podcast Index API client (https://podcastindex.org/developer)
Free, open, no rate limits for reasonable use.
"""
import hashlib
import time
import httpx
from config import settings

BASE_URL = "https://api.podcastindex.com/api/1.0"

def _auth_headers() -> dict:
    api_key = settings.podcast_index_api_key
    secret = settings.podcast_index_secret
    ts = str(int(time.time()))
    auth_hash = hashlib.sha1(f"{api_key}{secret}{ts}".encode()).hexdigest()
    return {
        "X-Auth-Date": ts,
        "X-Auth-Key": api_key,
        "Authorization": auth_hash,
        "User-Agent": "PodSnip/1.0",
    }

async def search_podcasts(query: str, limit: int = 20) -> list[dict]:
    async with httpx.AsyncClient() as client:
        r = await client.get(
            f"{BASE_URL}/search/byterm",
            params={"q": query, "max": limit, "clean": True},
            headers=_auth_headers(),
        )
        r.raise_for_status()
        return r.json().get("feeds", [])

async def get_podcast_by_feed_url(feed_url: str) -> dict | None:
    async with httpx.AsyncClient() as client:
        r = await client.get(
            f"{BASE_URL}/podcasts/byfeedurl",
            params={"url": feed_url},
            headers=_auth_headers(),
        )
        r.raise_for_status()
        return r.json().get("feed")
