from fastapi import APIRouter, HTTPException
from datetime import datetime, timezone
from database import get_db
from models import Podcast, Subscription, Episode
from services import podcast_index, rss

router = APIRouter(prefix="/podcasts", tags=["podcasts"])


@router.get("/search")
async def search_podcasts(q: str, limit: int = 20) -> list[Podcast]:
    """Search podcasts via Podcast Index API."""
    results = await podcast_index.search_podcasts(q, limit)
    return [
        Podcast(
            id=str(r["id"]),
            title=r.get("title", ""),
            author=r.get("author", ""),
            description=r.get("description", ""),
            image_url=r.get("image"),
            feed_url=r.get("url", ""),
            website_url=r.get("link"),
            episode_count=r.get("episodeCount"),
        )
        for r in results
    ]


@router.get("/subscriptions")
async def list_subscriptions() -> list[Subscription]:
    db = await get_db()
    rows = await db.execute_fetchall("SELECT * FROM subscriptions ORDER BY subscribed_at DESC")
    await db.close()
    return [Subscription(**dict(r)) for r in rows]


@router.post("/subscriptions/{podcast_id}")
async def subscribe(podcast_id: str, feed_url: str, title: str, image_url: str = None):
    db = await get_db()
    await db.execute(
        """INSERT OR IGNORE INTO subscriptions (podcast_id, feed_url, title, image_url, subscribed_at)
           VALUES (?, ?, ?, ?, ?)""",
        (podcast_id, feed_url, title, image_url, datetime.now(timezone.utc).isoformat()),
    )
    await db.commit()
    await db.close()
    return {"status": "subscribed"}


@router.delete("/subscriptions/{podcast_id}")
async def unsubscribe(podcast_id: str):
    db = await get_db()
    await db.execute("DELETE FROM subscriptions WHERE podcast_id = ?", (podcast_id,))
    await db.commit()
    await db.close()
    return {"status": "unsubscribed"}


@router.get("/{podcast_id}/episodes")
async def get_episodes(podcast_id: str, refresh: bool = False) -> list[Episode]:
    db = await get_db()

    if refresh:
        row = await db.execute_fetchone(
            "SELECT feed_url FROM subscriptions WHERE podcast_id = ?", (podcast_id,)
        )
        if not row:
            raise HTTPException(404, "Podcast not subscribed")
        episodes = await rss.fetch_episodes(row["feed_url"], podcast_id)
        for ep in episodes:
            await db.execute(
                """INSERT OR IGNORE INTO episodes
                   (id, podcast_id, title, description, audio_url, duration_seconds, published_at, image_url)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                (ep.id, ep.podcast_id, ep.title, ep.description, ep.audio_url,
                 ep.duration_seconds, ep.published_at.isoformat() if ep.published_at else None, ep.image_url),
            )
        await db.commit()

    rows = await db.execute_fetchall(
        "SELECT * FROM episodes WHERE podcast_id = ? ORDER BY published_at DESC",
        (podcast_id,),
    )
    await db.close()
    return [Episode(**dict(r)) for r in rows]
