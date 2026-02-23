from fastapi import APIRouter, HTTPException
from datetime import datetime, timezone
from database import get_db
from models import Snip, SnipRequest
from services.snip_engine import create_snip

router = APIRouter(prefix="/snips", tags=["snips"])


@router.post("/")
async def make_snip(req: SnipRequest, summary: bool = False) -> Snip:
    """
    Create a snip at the current playback position.
    Looks up episode + podcast metadata, extracts transcript segment.
    """
    db = await get_db()
    row = await db.execute_fetchone(
        """SELECT e.id, e.title, e.podcast_id, e.transcript_status, s.title as podcast_title
           FROM episodes e
           JOIN subscriptions s ON e.podcast_id = s.podcast_id
           WHERE e.id = ?""",
        (req.episode_id,),
    )
    await db.close()

    if not row:
        raise HTTPException(404, "Episode not found")
    if row["transcript_status"] != "done":
        raise HTTPException(409, f"Transcript not ready (status: {row['transcript_status']})")

    snip = await create_snip(
        episode_id=req.episode_id,
        podcast_id=row["podcast_id"],
        episode_title=row["title"],
        podcast_title=row["podcast_title"],
        current_seconds=req.current_seconds,
        with_summary=summary,
    )

    # Persist snip
    db = await get_db()
    await db.execute(
        """INSERT INTO snips
           (id, episode_id, podcast_id, episode_title, podcast_title,
            start_seconds, end_seconds, text, summary, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (snip.id, snip.episode_id, snip.podcast_id, snip.episode_title,
         snip.podcast_title, snip.start_seconds, snip.end_seconds,
         snip.text, snip.summary, snip.created_at.isoformat()),
    )
    await db.commit()
    await db.close()
    return snip


@router.get("/")
async def list_snips(episode_id: str = None) -> list[Snip]:
    db = await get_db()
    if episode_id:
        rows = await db.execute_fetchall(
            "SELECT * FROM snips WHERE episode_id = ? ORDER BY created_at DESC", (episode_id,)
        )
    else:
        rows = await db.execute_fetchall("SELECT * FROM snips ORDER BY created_at DESC")
    await db.close()
    return [Snip(**dict(r)) for r in rows]


@router.delete("/{snip_id}")
async def delete_snip(snip_id: str):
    db = await get_db()
    await db.execute("DELETE FROM snips WHERE id = ?", (snip_id,))
    await db.commit()
    await db.close()
    return {"status": "deleted"}
