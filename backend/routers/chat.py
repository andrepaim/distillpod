import json
import uuid
import subprocess
import asyncio
from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from database import get_db

router = APIRouter(prefix="/chat", tags=["chat"])

CLAUDE_BIN = "/root/.local/bin/claude"


def _claude_subprocess(prompt: str) -> str:
    """Synchronous Claude call — run in thread pool to avoid blocking event loop."""
    result = subprocess.run(
        [CLAUDE_BIN, "--print", prompt],
        capture_output=True, text=True, timeout=120
    )
    if result.returncode != 0:
        raise RuntimeError(f"Claude error: {result.stderr[:200]}")
    return result.stdout.strip()


async def claude_call(prompt: str) -> str:
    """Non-blocking wrapper using asyncio.to_thread."""
    try:
        return await asyncio.to_thread(_claude_subprocess, prompt)
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))


async def get_transcript(episode_id: str) -> str:
    db = await get_db()
    try:
        cursor = await db.execute(
            "SELECT words_json FROM transcripts WHERE episode_id = ?", (episode_id,)
        )
        row = await cursor.fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Transcript not found")
        words = json.loads(row["words_json"])
        return " ".join(w["word"] for w in words)
    finally:
        await db.close()


async def get_episode_title(episode_id: str) -> str:
    db = await get_db()
    try:
        cursor = await db.execute(
            "SELECT title FROM episodes WHERE id = ?", (episode_id,)
        )
        row = await cursor.fetchone()
        return row["title"] if row else "this episode"
    finally:
        await db.close()


@router.get("/{episode_id}")
async def get_chat(episode_id: str):
    db = await get_db()
    try:
        cursor = await db.execute(
            "SELECT id, role, content, created_at FROM episode_chats "
            "WHERE episode_id = ? ORDER BY created_at ASC",
            (episode_id,),
        )
        rows = await cursor.fetchall()
        return [dict(r) for r in rows]
    finally:
        await db.close()


@router.post("/{episode_id}/init")
async def init_chat(episode_id: str):
    db = await get_db()
    try:
        cursor = await db.execute(
            "SELECT id, role, content, created_at FROM episode_chats "
            "WHERE episode_id = ? ORDER BY created_at ASC LIMIT 1",
            (episode_id,),
        )
        existing = await cursor.fetchone()
        if existing:
            return dict(existing)

        transcript = await get_transcript(episode_id)
        title = await get_episode_title(episode_id)

        prompt = (
            f'You are a helpful podcast assistant for the episode "{title}". '
            f'Summarize the 3-4 key insights from this episode as bullet points, '
            f'then invite the user to ask questions. Be concise.\n\n'
            f'Full transcript:\n{transcript}'
        )
        content = await claude_call(prompt)

        now = datetime.now(timezone.utc).isoformat()
        msg_id = str(uuid.uuid4())
        await db.execute(
            "INSERT INTO episode_chats (id, episode_id, role, content, created_at) VALUES (?, ?, ?, ?, ?)",
            (msg_id, episode_id, "assistant", content, now),
        )
        await db.commit()
        return {"id": msg_id, "role": "assistant", "content": content, "created_at": now}
    finally:
        await db.close()


class MessageBody(BaseModel):
    message: str


@router.post("/{episode_id}/message")
async def send_message(episode_id: str, body: MessageBody):
    db = await get_db()
    try:
        transcript = await get_transcript(episode_id)
        cursor = await db.execute(
            "SELECT role, content FROM episode_chats "
            "WHERE episode_id = ? ORDER BY created_at ASC",
            (episode_id,),
        )
        history = await cursor.fetchall()
        turns = [{"role": r["role"], "content": r["content"]} for r in history][-10:]

        history_text = ""
        for t in turns:
            label = "User" if t["role"] == "user" else "Assistant"
            history_text += f"{label}: {t['content']}\n\n"

        prompt = (
            f"You are a helpful podcast assistant. Answer questions based on the transcript below. "
            f"Be concise and conversational. If something is not covered in the transcript, say so.\n\n"
            f"Full transcript:\n{transcript}\n\n"
            f"Conversation so far:\n{history_text}"
            f"User: {body.message}\n\nAssistant:"
        )
        reply_content = await claude_call(prompt)

        now = datetime.now(timezone.utc).isoformat()
        user_id = str(uuid.uuid4())
        await db.execute(
            "INSERT INTO episode_chats (id, episode_id, role, content, created_at) VALUES (?, ?, ?, ?, ?)",
            (user_id, episode_id, "user", body.message, now),
        )
        asst_id = str(uuid.uuid4())
        await db.execute(
            "INSERT INTO episode_chats (id, episode_id, role, content, created_at) VALUES (?, ?, ?, ?, ?)",
            (asst_id, episode_id, "assistant", reply_content, now),
        )
        await db.commit()
        return {"id": asst_id, "role": "assistant", "content": reply_content, "created_at": now}
    finally:
        await db.close()
