"""
Backend API tests using httpx AsyncClient against in-memory test DB.
"""
import json
import sqlite3
import uuid
from unittest.mock import AsyncMock, patch

import pytest
from conftest import (
    PODCAST_ID, EPISODE_ID_1, EPISODE_ID_2, EPISODE_ID_3,
    GIST_ID, SUG_ID_1, SUG_ID_2
)

pytestmark = pytest.mark.asyncio


# ── GET /podcasts/feed ────────────────────────────────────────────────────────

class TestFeed:

    async def test_returns_episodes(self, client):
        r = await client.get("/podcasts/feed")
        assert r.status_code == 200
        data = r.json()
        assert isinstance(data, list)
        assert len(data) == 3

    async def test_includes_podcast_metadata(self, client):
        r = await client.get("/podcasts/feed")
        ep = next(e for e in r.json() if e["id"] == EPISODE_ID_1)
        assert ep["podcast_title"] == "Test Podcast"
        assert ep["podcast_image"] == "https://example.com/img.jpg"

    async def test_distill_count_correct(self, client):
        r = await client.get("/podcasts/feed")
        data = r.json()
        ep1 = next(e for e in data if e["id"] == EPISODE_ID_1)
        ep2 = next(e for e in data if e["id"] == EPISODE_ID_2)
        assert ep1["distill_count"] == 1   # has a gist
        assert ep2["distill_count"] == 0   # no gist

    async def test_no_description_field(self, client):
        r = await client.get("/podcasts/feed")
        for ep in r.json():
            assert "description" not in ep

    async def test_ordered_by_published_at_desc(self, client):
        r = await client.get("/podcasts/feed")
        dates = [ep["published_at"] for ep in r.json() if ep["published_at"]]
        assert dates == sorted(dates, reverse=True)

    async def test_empty_when_no_subscriptions(self, client, tmp_db):
        import sqlite3
        conn = sqlite3.connect(tmp_db)
        conn.execute("DELETE FROM subscriptions")
        conn.commit()
        conn.close()
        r = await client.get("/podcasts/feed")
        assert r.status_code == 200
        assert r.json() == []


# ── GET /podcasts/suggestions ─────────────────────────────────────────────────

class TestSuggestions:

    async def test_returns_only_active(self, client):
        r = await client.get("/podcasts/suggestions")
        assert r.status_code == 200
        data = r.json()
        ids = [s["id"] for s in data]
        assert SUG_ID_1 in ids
        assert SUG_ID_2 not in ids   # dismissed

    async def test_suggestion_has_reason(self, client):
        r = await client.get("/podcasts/suggestions")
        s = next(s for s in r.json() if s["id"] == SUG_ID_1)
        assert s["reason"] == "Covers LLM research in depth"

    async def test_empty_when_all_dismissed(self, client, tmp_db):
        import sqlite3
        conn = sqlite3.connect(tmp_db)
        conn.execute("UPDATE suggestions SET dismissed = 1")
        conn.commit()
        conn.close()
        r = await client.get("/podcasts/suggestions")
        assert r.json() == []


# ── POST /podcasts/suggestions/{id}/dismiss ───────────────────────────────────

class TestDismiss:

    async def test_dismiss_removes_from_active(self, client):
        r = await client.post(f"/podcasts/suggestions/{SUG_ID_1}/dismiss")
        assert r.status_code == 200
        assert r.json()["status"] == "dismissed"

        r2 = await client.get("/podcasts/suggestions")
        ids = [s["id"] for s in r2.json()]
        assert SUG_ID_1 not in ids

    async def test_dismiss_unknown_id_graceful(self, client):
        r = await client.post("/podcasts/suggestions/nonexistent_id/dismiss")
        # Should not 500 — 200 or 404 both acceptable
        assert r.status_code in (200, 404)


# ── GET /podcasts/subscriptions ───────────────────────────────────────────────

class TestSubscriptions:

    async def test_returns_subscriptions(self, client):
        r = await client.get("/podcasts/subscriptions")
        assert r.status_code == 200
        data = r.json()
        assert len(data) == 1
        assert data[0]["podcast_id"] == PODCAST_ID
        assert data[0]["title"] == "Test Podcast"


# ── Auth middleware ───────────────────────────────────────────────────────────

class TestAuth:

    async def test_browser_without_session_redirects(self):
        """Browser request to protected route without session → 302 to /unauthorized."""
        import database, config, main
        from httpx import AsyncClient, ASGITransport

        # Temporarily disable test_mode
        config.settings.test_mode = False
        try:
            async with AsyncClient(
                transport=ASGITransport(app=main.app),
                base_url="http://test",
                follow_redirects=False,
            ) as c:
                r = await c.get(
                    "/podcasts/feed",
                    headers={"Accept": "text/html,application/xhtml+xml"},
                )
            assert r.status_code == 302
            assert r.headers["location"] == "/unauthorized"
        finally:
            config.settings.test_mode = True

    async def test_api_without_session_returns_401(self):
        """API client without session cookie → 401 JSON."""
        import database, config, main
        from httpx import AsyncClient, ASGITransport

        config.settings.test_mode = False
        try:
            async with AsyncClient(
                transport=ASGITransport(app=main.app),
                base_url="http://test",
            ) as c:
                r = await c.get("/podcasts/feed")   # no Accept: text/html
            assert r.status_code == 401
            assert r.json()["detail"] == "Unauthorized"
        finally:
            config.settings.test_mode = True


# ── /chat endpoints ───────────────────────────────────────────────────────────

CHAT_EPISODE_ID = "ep_chat_001"
FAKE_TRANSCRIPT = json.dumps([{"word": w} for w in "This is a test transcript about AI".split()])


def seed_transcript(db_path: str):
    conn = sqlite3.connect(db_path)
    conn.execute(
        "INSERT OR IGNORE INTO episodes (id, podcast_id, title, audio_url, transcript_status) VALUES (?, ?, ?, ?, ?)",
        (CHAT_EPISODE_ID, PODCAST_ID, "Chat Test Episode", "https://audio.example.com/chat.mp3", "done"),
    )
    conn.execute(
        "INSERT OR IGNORE INTO transcripts (episode_id, words_json, language, created_at) VALUES (?, ?, ?, ?)",
        (CHAT_EPISODE_ID, FAKE_TRANSCRIPT, "en", "2026-03-01T00:00:00"),
    )
    conn.commit()
    conn.close()


class TestChat:

    async def test_get_chat_empty(self, client):
        """GET /chat/{episode_id} on unknown episode returns []."""
        r = await client.get("/chat/nonexistent-episode")
        assert r.status_code == 200
        assert r.json() == []

    async def test_init_chat_no_transcript(self, client):
        """POST /chat/{episode_id}/init with no transcript returns 404."""
        r = await client.post("/chat/no-transcript-episode/init")
        assert r.status_code == 404

    async def test_get_chat_returns_history(self, client, tmp_db):
        """Inserting a message directly then GET returns it."""
        msg_id = str(uuid.uuid4())
        conn = sqlite3.connect(tmp_db)
        conn.execute(
            "INSERT INTO episode_chats (id, episode_id, role, content, created_at) VALUES (?, ?, ?, ?, ?)",
            (msg_id, CHAT_EPISODE_ID, "assistant", "Hello!", "2026-03-01T00:00:00"),
        )
        conn.commit()
        conn.close()

        r = await client.get(f"/chat/{CHAT_EPISODE_ID}")
        assert r.status_code == 200
        data = r.json()
        assert len(data) == 1
        assert data[0]["id"] == msg_id
        assert data[0]["role"] == "assistant"
        assert data[0]["content"] == "Hello!"

    async def test_init_chat_idempotent(self, client, tmp_db):
        """POST /chat/init when history exists returns existing message, no duplicate."""
        msg_id = str(uuid.uuid4())
        conn = sqlite3.connect(tmp_db)
        conn.execute(
            "INSERT INTO episode_chats (id, episode_id, role, content, created_at) VALUES (?, ?, ?, ?, ?)",
            (msg_id, CHAT_EPISODE_ID, "assistant", "Existing summary", "2026-03-01T00:00:00"),
        )
        conn.commit()
        conn.close()

        with patch("routers.chat.claude_call") as mock_claude:
            r = await client.post(f"/chat/{CHAT_EPISODE_ID}/init")

        assert r.status_code == 200
        assert r.json()["id"] == msg_id
        mock_claude.assert_not_called()  # Claude not called since message already exists

        # Confirm no duplicate inserted
        r2 = await client.get(f"/chat/{CHAT_EPISODE_ID}")
        assert len(r2.json()) == 1

    async def test_send_message_no_transcript(self, client):
        """POST /chat/{episode_id}/message with no transcript returns 404."""
        r = await client.post(
            "/chat/no-transcript-episode/message",
            json={"message": "What was this about?"},
        )
        assert r.status_code == 404

    async def test_chat_full_flow(self, client, tmp_db):
        """Full flow: init → get → message → get with mocked Claude."""
        seed_transcript(tmp_db)

        async def mock_claude(prompt: str) -> str:
            if "Summarize" in prompt or "bullet" in prompt.lower():
                return "• Key insight 1\n• Key insight 2\n\nWhat would you like to explore?"
            return "The main topic was AI and its implications."

        with patch("routers.chat.claude_call", side_effect=mock_claude):
            # 1. Init chat
            r_init = await client.post(f"/chat/{CHAT_EPISODE_ID}/init")
            assert r_init.status_code == 200
            init_msg = r_init.json()
            assert init_msg["role"] == "assistant"
            assert "Key insight" in init_msg["content"]

            # 2. GET history — 1 message
            r_get1 = await client.get(f"/chat/{CHAT_EPISODE_ID}")
            assert len(r_get1.json()) == 1

            # 3. Send user message
            r_msg = await client.post(
                f"/chat/{CHAT_EPISODE_ID}/message",
                json={"message": "What was the main topic?"},
            )
            assert r_msg.status_code == 200
            reply = r_msg.json()
            assert reply["role"] == "assistant"
            assert "AI" in reply["content"]

            # 4. GET history — 3 messages (init + user + assistant)
            r_get2 = await client.get(f"/chat/{CHAT_EPISODE_ID}")
            assert len(r_get2.json()) == 3
            roles = [m["role"] for m in r_get2.json()]
            assert roles == ["assistant", "user", "assistant"]
