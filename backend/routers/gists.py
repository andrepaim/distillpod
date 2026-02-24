from fastapi import APIRouter, HTTPException
from database import get_db
from models import Gist, GistRequest
from services.snip_engine import create_gist

router = APIRouter(prefix="/gists", tags=["gists"])


@router.post("/")
async def make_gist(req: GistRequest, summary: bool = False) -> Gist:
    """
    Create a gist at the current playback position.
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

    shot = await create_gist(
        episode_id=req.episode_id,
        podcast_id=row["podcast_id"],
        episode_title=row["title"],
        podcast_title=row["podcast_title"],
        current_seconds=req.current_seconds,
        with_summary=summary,
    )

    # Persist shot
    db = await get_db()
    await db.execute(
        """INSERT INTO gists
           (id, episode_id, podcast_id, episode_title, podcast_title,
            start_seconds, end_seconds, text, summary, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (shot.id, shot.episode_id, shot.podcast_id, shot.episode_title,
         shot.podcast_title, shot.start_seconds, shot.end_seconds,
         shot.text, shot.summary, shot.created_at.isoformat()),
    )
    await db.commit()
    await db.close()
    return shot


@router.get("/")
async def list_gists(episode_id: str = None) -> list[Gist]:
    db = await get_db()
    if episode_id:
        rows = await db.execute_fetchall(
            "SELECT * FROM gists WHERE episode_id = ? ORDER BY created_at DESC", (episode_id,)
        )
    else:
        rows = await db.execute_fetchall("SELECT * FROM gists ORDER BY created_at DESC")
    await db.close()
    return [Gist(**dict(r)) for r in rows]


@router.delete("/{shot_id}")
async def delete_gist(shot_id: str):
    db = await get_db()
    await db.execute("DELETE FROM gists WHERE id = ?", (shot_id,))
    await db.commit()
    await db.close()
    return {"status": "deleted"}
