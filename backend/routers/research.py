import uuid
import asyncio
from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException

from database import get_db
from services.researcher import run_research_sync

router = APIRouter(prefix="/research", tags=["research"])


@router.post("/{gist_id}")
async def trigger_research(gist_id: str):
    db = await get_db()
    try:
        # Check if gist exists
        gist = await (
            await db.execute(
                "SELECT id, episode_id, episode_title, text, summary FROM gists WHERE id = ?",
                (gist_id,),
            )
        ).fetchone()
        if not gist:
            raise HTTPException(status_code=404, detail="Gist not found")

        # Check if research already exists
        existing = await (
            await db.execute(
                "SELECT id, status, public_url FROM researches WHERE gist_id = ?",
                (gist_id,),
            )
        ).fetchone()
        if existing:
            return dict(existing)

        # Create research record
        research_id = str(uuid.uuid4())
        now = datetime.now(timezone.utc).isoformat()
        await db.execute(
            "INSERT INTO researches (id, gist_id, episode_id, status, created_at) VALUES (?, ?, ?, ?, ?)",
            (research_id, gist_id, gist["episode_id"], "pending", now),
        )
        await db.commit()

        # Run in background
        asyncio.create_task(
            asyncio.to_thread(
                run_research_sync,
                research_id,
                gist_id,
                gist["text"],
                gist["summary"] or "",
                gist["episode_title"],
            )
        )

        return {"id": research_id, "status": "pending"}
    finally:
        await db.close()


@router.get("/{gist_id}")
async def get_research(gist_id: str):
    db = await get_db()
    try:
        row = await (
            await db.execute(
                "SELECT id, status, public_url, error, created_at, finished_at "
                "FROM researches WHERE gist_id = ? ORDER BY created_at DESC LIMIT 1",
                (gist_id,),
            )
        ).fetchone()
        if not row:
            return {"status": "none"}
        return dict(row)
    finally:
        await db.close()
