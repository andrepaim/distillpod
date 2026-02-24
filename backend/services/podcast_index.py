"""
Podcast search — uses iTunes Search API (free, no key) as primary.
Podcast Index API (podcastindex.org) used optionally when key is configured.
"""
import hashlib, time
import httpx
from config import settings

PI_BASE = "https://api.podcastindex.org/api/1.0"
ITUNES_BASE = "https://itunes.apple.com"

def _pi_headers() -> dict:
    api_key = settings.podcast_index_api_key
    secret  = settings.podcast_index_secret
    ts = str(int(time.time()))
    auth_hash = hashlib.sha1(f"{api_key}{secret}{ts}".encode()).hexdigest()
    return {
        "X-Auth-Date": ts,
        "X-Auth-Key": api_key,
        "Authorization": auth_hash,
        "User-Agent": "PodSnip/1.0",
    }

def _has_pi_keys() -> bool:
    return (
        settings.podcast_index_api_key and
        settings.podcast_index_api_key != "your_key_here" and
        settings.podcast_index_secret and
        settings.podcast_index_secret != "your_secret_here"
    )

def _normalize_itunes(r: dict) -> dict:
    return {
        "id":          str(r.get("collectionId", "")),
        "title":       r.get("collectionName", ""),
        "author":      r.get("artistName", ""),
        "description": r.get("collectionCensoredName", ""),
        "url":         r.get("feedUrl", ""),
        "image":       r.get("artworkUrl600") or r.get("artworkUrl100", ""),
        "link":        r.get("collectionViewUrl", ""),
        "categories":  {},
    }

async def search_podcasts(query: str, limit: int = 20) -> list[dict]:
    # Try Podcast Index first if configured
    if _has_pi_keys():
        try:
            async with httpx.AsyncClient(follow_redirects=True) as client:
                r = await client.get(
                    f"{PI_BASE}/search/byterm",
                    params={"q": query, "max": limit, "clean": True},
                    headers=_pi_headers(),
                    timeout=10,
                )
                r.raise_for_status()
                return r.json().get("feeds", [])
        except Exception:
            pass  # fall through to iTunes

    # iTunes Search API fallback (always works, no auth)
    async with httpx.AsyncClient() as client:
        r = await client.get(
            f"{ITUNES_BASE}/search",
            params={"media": "podcast", "entity": "podcast", "term": query, "limit": limit},
            timeout=10,
        )
        r.raise_for_status()
        return [_normalize_itunes(x) for x in r.json().get("results", [])]

async def get_podcast_by_feed_url(feed_url: str) -> dict | None:
    if not _has_pi_keys():
        return None
    try:
        async with httpx.AsyncClient(follow_redirects=True) as client:
            r = await client.get(
                f"{PI_BASE}/podcasts/byfeedurl",
                params={"url": feed_url},
                headers=_pi_headers(),
                timeout=10,
            )
            r.raise_for_status()
            return r.json().get("feed")
    except Exception:
        return None
