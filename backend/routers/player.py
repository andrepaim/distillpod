import asyncio
from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse
from models import PlayRequest, TranscriptStatus, Episode
from services.downloader import download_episode, episode_local_path
from services.transcriber import transcribe_episode
from database import get_db
from config import settings
from pathlib import Path

router = APIRouter(prefix="/player", tags=["player"])

# In-memory set to avoid duplicate transcription jobs
_transcribing: set[str] = set()


@router.post("/play")
async def play(req: PlayRequest):
    """
    Trigger download + transcription for an episode.
    Returns immediately; transcription runs in background.
    Audio is streamed from /player/audio/{episode_id} once downloaded.
    """
    db = await get_db()
    row = await db.execute_fetchone(
        "SELECT downloaded, local_path, transcript_status FROM episodes WHERE id = ?",
        (req.episode_id,),
    )
    await db.close()

    if not row:
        raise HTTPException(404, "Episode not found. Fetch episodes first.")

    local_path = Path(row["local_path"]) if row["local_path"] else None

    # Download if needed
    if not row["downloaded"] or not (local_path and local_path.exists()):
        local_path = await download_episode(req.episode_id, req.audio_url)
        db = await get_db()
        await db.execute(
            "UPDATE episodes SET downloaded = 1, local_path = ? WHERE id = ?",
            (str(local_path), req.episode_id),
        )
        await db.commit()
        await db.close()

    # Start transcription in background if not already done/running
    if row["transcript_status"] not in ("done", "processing") and req.episode_id not in _transcribing:
        _transcribing.add(req.episode_id)

        async def _bg_transcribe():
            try:
                await transcribe_episode(req.episode_id, local_path)
            finally:
                _transcribing.discard(req.episode_id)

        asyncio.create_task(_bg_transcribe())

    return {
        "episode_id": req.episode_id,
        "audio_url": f"/player/audio/{req.episode_id}",
        "transcript_status": row["transcript_status"],
    }


@router.get("/audio/{episode_id}")
async def stream_audio(episode_id: str):
    """Serve the downloaded audio file to the browser."""
    db = await get_db()
    row = await db.execute_fetchone(
        "SELECT local_path FROM episodes WHERE id = ? AND downloaded = 1", (episode_id,)
    )
    await db.close()
    if not row or not row["local_path"]:
        raise HTTPException(404, "Audio not downloaded yet")
    return FileResponse(row["local_path"], media_type="audio/mpeg")


@router.get("/episode/{episode_id}")
async def get_episode(episode_id: str) -> Episode:
    """Fetch a single episode by ID from the DB."""
    db = await get_db()
    row = await db.execute_fetchone(
        "SELECT * FROM episodes WHERE id = ?", (episode_id,)
    )
    await db.close()
    if not row:
        raise HTTPException(404, "Episode not found")
    return Episode(**dict(row))


@router.get("/transcript-status/{episode_id}")
async def transcript_status(episode_id: str) -> TranscriptStatus:
    db = await get_db()
    row = await db.execute_fetchone(
        "SELECT transcript_status FROM episodes WHERE id = ?", (episode_id,)
    )
    await db.close()
    if not row:
        raise HTTPException(404, "Episode not found")
    return TranscriptStatus(episode_id=episode_id, status=row["transcript_status"])


@router.get("/adfree-status/{episode_id}")
async def adfree_status(episode_id: str):
    db = await get_db()
    try:
        row = await db.execute_fetchone(
            'SELECT adfree_path, ads_detected FROM episodes WHERE id = ?', (episode_id,)
        )
        if not row:
            return {'has_adfree': False, 'ads_count': 0}
        has = bool(row['adfree_path']) and Path(row['adfree_path']).exists()
        return {'has_adfree': has, 'ads_count': row['ads_detected'] or 0}
    finally:
        await db.close()


@router.get("/audio-adfree/{episode_id}")
async def stream_adfree(episode_id: str):
    db = await get_db()
    try:
        row = await db.execute_fetchone(
            'SELECT adfree_path FROM episodes WHERE id = ?', (episode_id,)
        )
        if not row or not row['adfree_path'] or not Path(row['adfree_path']).exists():
            raise HTTPException(status_code=404, detail='Ad-free version not found')
        return FileResponse(row['adfree_path'], media_type='audio/mpeg')
    finally:
        await db.close()


@router.get("/chapters/{episode_id}")
async def get_chapters(episode_id: str):
    """Return chapters and summary for an episode."""
    db = await get_db()
    try:
        ep = await db.execute_fetchone(
            "SELECT summary, chapters_status FROM episodes WHERE id = ?", (episode_id,)
        )
        if not ep:
            raise HTTPException(status_code=404, detail="Episode not found")

        chapters = await db.execute_fetchall(
            "SELECT title, start_time FROM chapters WHERE episode_id = ? ORDER BY start_time",
            (episode_id,)
        )
        return {
            "episode_id": episode_id,
            "chapters_status": ep["chapters_status"] or "none",
            "summary": ep["summary"],
            "chapters": [{"title": r["title"], "start_time": r["start_time"]} for r in chapters],
        }
    finally:
        await db.close()
