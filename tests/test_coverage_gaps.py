"""
Integration tests covering gaps: audio streaming, chapters, ad-free status,
podcast search, episodes list, research endpoints, and auth endpoints.
"""
import json
import sqlite3
import uuid
from pathlib import Path
from unittest.mock import AsyncMock, patch, MagicMock

import pytest
from conftest import PODCAST_ID, EPISODE_ID_1, EPISODE_ID_2, EPISODE_ID_3, GIST_ID

pytestmark = pytest.mark.asyncio


# ── Helpers ──────────────────────────────────────────────────────────────────

def seed_downloaded_episode(db_path: str, episode_id: str, local_path: str, adfree_path: str = None, ads_detected: int = 0):
    """Mark an episode as downloaded with a local path."""
    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE episodes SET downloaded = 1, local_path = ?, adfree_path = ?, ads_detected = ? WHERE id = ?",
        (local_path, adfree_path, ads_detected, episode_id),
    )
    conn.commit()
    conn.close()


def seed_chapters(db_path: str, episode_id: str, chapters: list[dict]):
    """Insert chapter rows for an episode."""
    conn = sqlite3.connect(db_path)
    for ch in chapters:
        conn.execute(
            "INSERT INTO chapters (id, episode_id, title, start_time) VALUES (?, ?, ?, ?)",
            (str(uuid.uuid4()), episode_id, ch["title"], ch["start_time"]),
        )
    conn.execute(
        "UPDATE episodes SET chapters_status = 'done', summary = 'Episode summary' WHERE id = ?",
        (episode_id,),
    )
    conn.commit()
    conn.close()


def seed_research(db_path: str, gist_id: str, episode_id: str, status: str = "done", public_url: str = None):
    """Insert a research record."""
    conn = sqlite3.connect(db_path)
    conn.execute(
        "INSERT INTO researches (id, gist_id, episode_id, status, public_url, created_at) VALUES (?, ?, ?, ?, ?, ?)",
        (str(uuid.uuid4()), gist_id, episode_id, status, public_url, "2026-03-01T00:00:00"),
    )
    conn.commit()
    conn.close()


# ── 1. Audio streaming: GET /player/audio/{episode_id} ──────────────────────

class TestAudioStreaming:

    async def test_audio_not_downloaded_returns_404(self, client):
        """GET /player/audio/{id} for non-downloaded episode returns 404."""
        r = await client.get(f"/player/audio/{EPISODE_ID_1}")
        assert r.status_code == 404

    async def test_audio_downloaded_returns_file(self, client, tmp_db, tmp_path):
        """GET /player/audio/{id} for downloaded episode returns audio file."""
        import config
        # Create a temporary audio file inside media_dir
        orig_media = config.settings.media_dir
        config.settings.media_dir = str(tmp_path)
        try:
            audio_file = tmp_path / "test_audio.mp3"
            audio_file.write_bytes(b"\xff\xfb\x90\x00" * 100)  # fake mp3 bytes

            seed_downloaded_episode(tmp_db, EPISODE_ID_1, str(audio_file))

            r = await client.get(f"/player/audio/{EPISODE_ID_1}")
            assert r.status_code == 200
            assert "audio" in r.headers.get("content-type", "")
        finally:
            config.settings.media_dir = orig_media

    async def test_audio_unknown_episode_returns_404(self, client):
        """GET /player/audio/{id} for unknown episode returns 404."""
        r = await client.get("/player/audio/nonexistent_episode")
        assert r.status_code == 404


# ── 2. Ad-free audio: GET /player/audio-adfree/{episode_id} ─────────────────

class TestAudioAdFree:

    async def test_adfree_not_available_returns_404(self, client):
        """GET /player/audio-adfree/{id} without adfree file returns 404."""
        r = await client.get(f"/player/audio-adfree/{EPISODE_ID_1}")
        assert r.status_code == 404

    async def test_adfree_with_file_returns_audio(self, client, tmp_db, tmp_path):
        """GET /player/audio-adfree/{id} with valid adfree file returns audio."""
        import config
        orig_media = config.settings.media_dir
        config.settings.media_dir = str(tmp_path)
        try:
            adfree_file = tmp_path / "adfree_audio.mp3"
            adfree_file.write_bytes(b"\xff\xfb\x90\x00" * 100)

            seed_downloaded_episode(tmp_db, EPISODE_ID_1, str(tmp_path / "original.mp3"), str(adfree_file), 3)

            r = await client.get(f"/player/audio-adfree/{EPISODE_ID_1}")
            assert r.status_code == 200
            assert "audio" in r.headers.get("content-type", "")
        finally:
            config.settings.media_dir = orig_media

    async def test_adfree_unknown_episode_returns_404(self, client):
        """GET /player/audio-adfree/{id} for unknown episode returns 404."""
        r = await client.get("/player/audio-adfree/nonexistent_episode")
        assert r.status_code == 404


# ── 3. Ad-free status: GET /player/adfree-status/{episode_id} ───────────────

class TestAdFreeStatus:

    async def test_adfree_status_no_adfree(self, client):
        """GET /player/adfree-status/{id} without adfree returns false."""
        r = await client.get(f"/player/adfree-status/{EPISODE_ID_1}")
        assert r.status_code == 200
        data = r.json()
        assert data["has_adfree"] is False
        assert data["ads_count"] == 0

    async def test_adfree_status_with_adfree(self, client, tmp_db, tmp_path):
        """GET /player/adfree-status/{id} with adfree file returns true and count."""
        adfree_file = tmp_path / "adfree.mp3"
        adfree_file.write_bytes(b"\xff\xfb\x90\x00" * 10)
        seed_downloaded_episode(tmp_db, EPISODE_ID_1, str(tmp_path / "orig.mp3"), str(adfree_file), 5)

        r = await client.get(f"/player/adfree-status/{EPISODE_ID_1}")
        assert r.status_code == 200
        data = r.json()
        assert data["has_adfree"] is True
        assert data["ads_count"] == 5

    async def test_adfree_status_unknown_episode(self, client):
        """GET /player/adfree-status/{id} for unknown episode returns default."""
        r = await client.get("/player/adfree-status/nonexistent_episode")
        assert r.status_code == 200
        data = r.json()
        assert data["has_adfree"] is False


# ── 4. Chapters: GET /player/chapters/{episode_id} ──────────────────────────

class TestChapters:

    async def test_chapters_empty(self, client):
        """GET /player/chapters/{id} with no chapters returns empty list."""
        r = await client.get(f"/player/chapters/{EPISODE_ID_1}")
        assert r.status_code == 200
        data = r.json()
        assert data["episode_id"] == EPISODE_ID_1
        assert data["chapters"] == []

    async def test_chapters_with_data(self, client, tmp_db):
        """GET /player/chapters/{id} with chapters returns them sorted."""
        seed_chapters(tmp_db, EPISODE_ID_1, [
            {"title": "Introduction", "start_time": 0.0},
            {"title": "Main Topic", "start_time": 120.5},
            {"title": "Conclusion", "start_time": 300.0},
        ])

        r = await client.get(f"/player/chapters/{EPISODE_ID_1}")
        assert r.status_code == 200
        data = r.json()
        assert data["chapters_status"] == "done"
        assert data["summary"] == "Episode summary"
        assert len(data["chapters"]) == 3
        assert data["chapters"][0]["title"] == "Introduction"
        assert data["chapters"][1]["start_time"] == 120.5

    async def test_chapters_unknown_episode(self, client):
        """GET /player/chapters/{id} for unknown episode returns 404."""
        r = await client.get("/player/chapters/nonexistent_episode")
        assert r.status_code == 404


# ── 5. Podcast search: GET /podcasts/search ──────────────────────────────────

class TestPodcastSearch:

    async def test_search_returns_results(self, client):
        """GET /podcasts/search?q=test returns search results."""
        mock_results = [
            {
                "id": "12345",
                "title": "Test Podcast",
                "author": "Author",
                "description": "A test podcast",
                "image": "https://example.com/img.jpg",
                "url": "https://feeds.example.com/test",
                "link": "https://example.com",
            }
        ]

        with patch("routers.podcasts.podcast_index.search_podcasts", new_callable=AsyncMock, return_value=mock_results):
            r = await client.get("/podcasts/search", params={"q": "test"})

        assert r.status_code == 200
        data = r.json()
        assert len(data) == 1
        assert data[0]["title"] == "Test Podcast"
        assert data[0]["id"] == "12345"

    async def test_search_empty_results(self, client):
        """GET /podcasts/search?q=xyz returns empty list when no results."""
        with patch("routers.podcasts.podcast_index.search_podcasts", new_callable=AsyncMock, return_value=[]):
            r = await client.get("/podcasts/search", params={"q": "xyz_nothing"})

        assert r.status_code == 200
        assert r.json() == []

    async def test_search_without_query_returns_422(self, client):
        """GET /podcasts/search without q param returns 422 validation error."""
        r = await client.get("/podcasts/search")
        assert r.status_code == 422


# ── 6. Episodes list: GET /podcasts/{podcast_id}/episodes ────────────────────

class TestEpisodesList:

    async def test_episodes_list(self, client):
        """GET /podcasts/{podcast_id}/episodes returns seeded episodes."""
        r = await client.get(f"/podcasts/{PODCAST_ID}/episodes")
        assert r.status_code == 200
        data = r.json()
        assert len(data) == 3
        titles = [ep["title"] for ep in data]
        assert "Episode One" in titles

    async def test_episodes_list_empty_podcast(self, client):
        """GET /podcasts/{podcast_id}/episodes for podcast with no episodes returns []."""
        r = await client.get("/podcasts/nonexistent_podcast/episodes")
        assert r.status_code == 200
        assert r.json() == []

    async def test_episodes_list_with_refresh(self, client, tmp_db):
        """GET /podcasts/{podcast_id}/episodes?refresh=true fetches from RSS."""
        from models import Episode
        from datetime import datetime, timezone

        mock_episodes = [
            Episode(
                id="ep_rss_new_001",
                podcast_id=PODCAST_ID,
                title="New RSS Episode",
                audio_url="https://example.com/new.mp3",
                duration_seconds=600,
                published_at=datetime(2026, 3, 15, tzinfo=timezone.utc),
            )
        ]

        with patch("routers.podcasts.rss.fetch_episodes", new_callable=AsyncMock, return_value=mock_episodes):
            r = await client.get(f"/podcasts/{PODCAST_ID}/episodes", params={"refresh": "true"})

        assert r.status_code == 200
        data = r.json()
        titles = [ep["title"] for ep in data]
        assert "New RSS Episode" in titles

    async def test_episodes_refresh_unsubscribed_returns_404(self, client):
        """GET /podcasts/{id}/episodes?refresh=true for unsubscribed podcast returns 404."""
        r = await client.get("/podcasts/not_subscribed/episodes", params={"refresh": "true"})
        assert r.status_code == 404


# ── 7. Research: POST /research/{gist_id} & GET /research/{gist_id} ─────────

class TestResearch:

    async def test_trigger_research_gist_not_found(self, client):
        """POST /research/{gist_id} with unknown gist returns 404."""
        r = await client.post("/research/nonexistent_gist")
        assert r.status_code == 404

    async def test_trigger_research_creates_pending(self, client, tmp_db):
        """POST /research/{gist_id} creates a pending research record."""
        with patch("routers.research.run_research_sync") as mock_sync:
            r = await client.post(f"/research/{GIST_ID}")

        assert r.status_code == 200
        data = r.json()
        assert data["status"] == "pending"
        assert "id" in data

    async def test_trigger_research_returns_existing(self, client, tmp_db):
        """POST /research/{gist_id} when research exists returns existing."""
        seed_research(tmp_db, GIST_ID, EPISODE_ID_1, status="done", public_url="https://example.com/report")

        r = await client.post(f"/research/{GIST_ID}")
        assert r.status_code == 200
        data = r.json()
        assert data["status"] == "done"
        assert data["public_url"] == "https://example.com/report"

    async def test_get_research_none(self, client):
        """GET /research/{gist_id} with no research returns status 'none'."""
        r = await client.get(f"/research/{GIST_ID}")
        assert r.status_code == 200
        assert r.json()["status"] == "none"

    async def test_get_research_existing(self, client, tmp_db):
        """GET /research/{gist_id} with existing research returns record."""
        seed_research(tmp_db, GIST_ID, EPISODE_ID_1, status="done", public_url="https://example.com/report")

        r = await client.get(f"/research/{GIST_ID}")
        assert r.status_code == 200
        data = r.json()
        assert data["status"] == "done"
        assert data["public_url"] == "https://example.com/report"

    async def test_get_research_nonexistent_gist(self, client):
        """GET /research/{gist_id} for nonexistent gist returns 'none'."""
        r = await client.get("/research/nonexistent_gist")
        assert r.status_code == 200
        assert r.json()["status"] == "none"


# ── 8. Auth: POST /auth/logout & GET /auth/me ───────────────────────────────

class TestAuthEndpoints:

    async def test_logout_clears_cookie(self, client):
        """POST /auth/logout returns ok and clears session cookie."""
        r = await client.post("/auth/logout")
        assert r.status_code == 200
        assert r.json()["ok"] is True
        # Check that set-cookie header clears the session
        set_cookie = r.headers.get("set-cookie", "")
        assert "distillpod_session" in set_cookie

    async def test_me_without_session_returns_401(self):
        """GET /auth/me without session cookie returns 401."""
        import config, main
        from httpx import AsyncClient, ASGITransport

        config.settings.test_mode = False
        try:
            async with AsyncClient(
                transport=ASGITransport(app=main.app),
                base_url="http://test",
            ) as c:
                r = await c.get("/auth/me")
            assert r.status_code == 401
            assert r.json()["detail"] == "Unauthorized"
        finally:
            config.settings.test_mode = True

    async def test_me_with_valid_session(self):
        """GET /auth/me with valid session returns user info."""
        import config, main
        from httpx import AsyncClient, ASGITransport
        from middleware.auth import create_session_token

        config.settings.test_mode = False
        try:
            token = create_session_token({
                "email": "user@example.com",
                "name": "Test User",
                "picture": "https://example.com/pic.jpg",
            })
            async with AsyncClient(
                transport=ASGITransport(app=main.app),
                base_url="http://test",
                cookies={"distillpod_session": token},
            ) as c:
                r = await c.get("/auth/me")
            assert r.status_code == 200
            data = r.json()
            assert data["email"] == "user@example.com"
            assert data["name"] == "Test User"
            assert data["picture"] == "https://example.com/pic.jpg"
        finally:
            config.settings.test_mode = True

    async def test_me_with_invalid_session_returns_401(self):
        """GET /auth/me with invalid token returns 401."""
        import config, main
        from httpx import AsyncClient, ASGITransport

        config.settings.test_mode = False
        try:
            async with AsyncClient(
                transport=ASGITransport(app=main.app),
                base_url="http://test",
                cookies={"distillpod_session": "garbage.token.here"},
            ) as c:
                r = await c.get("/auth/me")
            assert r.status_code == 401
        finally:
            config.settings.test_mode = True
