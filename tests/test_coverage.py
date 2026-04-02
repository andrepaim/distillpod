"""
Coverage-boost tests for gists, player, RSS, auth, and podcasts routers.
"""
import json
import sqlite3
import uuid
from unittest.mock import AsyncMock, patch, MagicMock

import pytest
from conftest import PODCAST_ID, EPISODE_ID_1, EPISODE_ID_2, EPISODE_ID_3, GIST_ID

pytestmark = pytest.mark.asyncio


# ── Gists (routers/gists.py) ────────────────────────────────────────────────

class TestGistsListEmpty:

    async def test_list_gists_empty(self, client, tmp_db):
        """GET /gists/ with no gists returns []."""
        conn = sqlite3.connect(tmp_db)
        conn.execute("DELETE FROM gists")
        conn.commit()
        conn.close()
        r = await client.get("/gists/")
        assert r.status_code == 200
        assert r.json() == []


class TestGistsListForEpisode:

    async def test_list_gists_for_episode(self, client):
        """GET /gists/?episode_id=ep returns gists for that episode."""
        r = await client.get(f"/gists/?episode_id={EPISODE_ID_1}")
        assert r.status_code == 200
        data = r.json()
        assert len(data) == 1
        assert data[0]["episode_id"] == EPISODE_ID_1
        assert data[0]["id"] == GIST_ID

    async def test_list_gists_for_episode_no_match(self, client):
        """GET /gists/?episode_id=unknown returns []."""
        r = await client.get("/gists/?episode_id=nonexistent")
        assert r.status_code == 200
        assert r.json() == []


class TestGistCreateNoTranscript:

    async def test_create_gist_no_transcript(self, client):
        """POST /gists/ with episode that has transcript_status != done returns 409."""
        r = await client.post("/gists/", json={
            "episode_id": EPISODE_ID_1,  # transcript_status is 'none'
            "current_seconds": 60.0,
        })
        assert r.status_code == 409

    async def test_create_gist_unknown_episode(self, client):
        """POST /gists/ with unknown episode returns 404."""
        r = await client.post("/gists/", json={
            "episode_id": "nonexistent_ep",
            "current_seconds": 30.0,
        })
        assert r.status_code == 404


class TestGistCreateWithTranscript:

    async def test_create_gist_with_transcript(self, client, tmp_db):
        """POST /gists/ with valid transcript mocks create_gist and returns gist."""
        from datetime import datetime, timezone

        fake_gist_id = str(uuid.uuid4())
        fake_gist = MagicMock()
        fake_gist.id = fake_gist_id
        fake_gist.episode_id = EPISODE_ID_2
        fake_gist.podcast_id = PODCAST_ID
        fake_gist.episode_title = "Episode Two"
        fake_gist.podcast_title = "Test Podcast"
        fake_gist.start_seconds = 0.0
        fake_gist.end_seconds = 60.0
        fake_gist.text = "Some transcript text"
        fake_gist.summary = '{"quote": "Great insight", "insight": "AI is amazing"}'
        fake_gist.created_at = datetime.now(timezone.utc)

        with patch("routers.gists.create_gist", new_callable=AsyncMock, return_value=fake_gist):
            r = await client.post("/gists/", json={
                "episode_id": EPISODE_ID_2,  # transcript_status is 'done'
                "current_seconds": 60.0,
            })

        assert r.status_code == 200
        data = r.json()
        assert data["id"] == fake_gist_id
        assert data["episode_id"] == EPISODE_ID_2
        assert data["summary"] is not None


class TestGistDelete:

    async def test_delete_gist(self, client):
        """DELETE /gists/{id} removes it."""
        r = await client.delete(f"/gists/{GIST_ID}")
        assert r.status_code == 200
        assert r.json()["status"] == "deleted"

        # Verify it's gone
        r2 = await client.get("/gists/")
        ids = [g["id"] for g in r2.json()]
        assert GIST_ID not in ids

    async def test_delete_gist_nonexistent(self, client):
        """DELETE /gists/{id} for nonexistent id still returns 200."""
        r = await client.delete("/gists/nonexistent_gist")
        assert r.status_code == 200


# ── Player (routers/player.py) ──────────────────────────────────────────────

class TestTranscriptStatus:

    async def test_transcript_status_none(self, client):
        """GET /player/transcript-status/{id} for episode with status 'none'."""
        r = await client.get(f"/player/transcript-status/{EPISODE_ID_1}")
        assert r.status_code == 200
        data = r.json()
        assert data["episode_id"] == EPISODE_ID_1
        assert data["status"] == "none"

    async def test_transcript_status_done(self, client):
        """GET /player/transcript-status/{id} for episode with status 'done'."""
        r = await client.get(f"/player/transcript-status/{EPISODE_ID_2}")
        assert r.status_code == 200
        data = r.json()
        assert data["status"] == "done"

    async def test_transcript_status_unknown_episode(self, client):
        """GET /player/transcript-status/{id} for unknown episode returns 404."""
        r = await client.get("/player/transcript-status/nonexistent")
        assert r.status_code == 404


class TestPlayEpisode:

    async def test_play_episode_not_found(self, client):
        """POST /player/play with unknown episode_id returns 404."""
        r = await client.post("/player/play", json={
            "episode_id": "nonexistent_ep",
            "audio_url": "https://example.com/audio.mp3",
        })
        assert r.status_code == 404

    async def test_play_episode_success(self, client, tmp_db):
        """POST /player/play with valid episode downloads and returns audio URL."""
        with patch("routers.player.download_episode", new_callable=AsyncMock) as mock_dl, \
             patch("routers.player.transcribe_episode", new_callable=AsyncMock):
            mock_dl.return_value = "/tmp/fake_audio.mp3"
            r = await client.post("/player/play", json={
                "episode_id": EPISODE_ID_1,
                "audio_url": "https://audio.example.com/1.mp3",
            })

        assert r.status_code == 200
        data = r.json()
        assert data["episode_id"] == EPISODE_ID_1
        assert "audio_url" in data


class TestGetEpisode:

    async def test_get_episode(self, client):
        """GET /player/episode/{id} returns episode details."""
        r = await client.get(f"/player/episode/{EPISODE_ID_1}")
        assert r.status_code == 200
        data = r.json()
        assert data["id"] == EPISODE_ID_1
        assert data["title"] == "Episode One"

    async def test_get_episode_not_found(self, client):
        """GET /player/episode/{id} for unknown episode returns 404."""
        r = await client.get("/player/episode/nonexistent")
        assert r.status_code == 404


# ── RSS (services/rss.py) ───────────────────────────────────────────────────

MINIMAL_RSS = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel>
    <title>Test Feed</title>
    <item>
      <title>Test Episode</title>
      <guid>ep-rss-001</guid>
      <summary>A test episode</summary>
      <pubDate>Mon, 01 Jan 2026 00:00:00 GMT</pubDate>
      <itunes:duration>01:30:00</itunes:duration>
      <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg" length="12345"/>
    </item>
    <item>
      <title>Second Episode</title>
      <guid>ep-rss-002</guid>
      <enclosure url="https://example.com/ep2.mp3" type="audio/mpeg" length="6789"/>
      <itunes:duration>45:30</itunes:duration>
    </item>
  </channel>
</rss>"""

RSS_MISSING_FIELDS = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel>
    <title>Sparse Feed</title>
    <item>
      <title>Minimal Episode</title>
      <enclosure url="https://example.com/minimal.mp3" type="audio/mpeg" length="100"/>
    </item>
  </channel>
</rss>"""


class TestParseRssFeed:

    async def test_parse_rss_feed(self):
        """fetch_episodes parses minimal RSS and returns episode dicts."""
        import httpx
        from services.rss import fetch_episodes

        mock_response = MagicMock()
        mock_response.text = MINIMAL_RSS
        mock_response.raise_for_status = MagicMock()

        async def fake_get(url, **kwargs):
            return mock_response

        with patch("services.rss.httpx.AsyncClient") as mock_client_cls:
            mock_ctx = AsyncMock()
            mock_ctx.get = fake_get
            mock_client_cls.return_value.__aenter__ = AsyncMock(return_value=mock_ctx)
            mock_client_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            episodes = await fetch_episodes("https://feeds.example.com/test", "pod_rss_001")

        assert len(episodes) == 2
        ep1 = episodes[0]
        assert ep1.title == "Test Episode"
        assert ep1.audio_url == "https://example.com/ep1.mp3"
        assert ep1.duration_seconds == 5400  # 1:30:00
        assert ep1.podcast_id == "pod_rss_001"

        ep2 = episodes[1]
        assert ep2.title == "Second Episode"
        assert ep2.duration_seconds == 2730  # 45:30

    async def test_parse_rss_handles_missing_fields(self):
        """RSS with missing duration/image returns defaults gracefully."""
        from services.rss import fetch_episodes

        mock_response = MagicMock()
        mock_response.text = RSS_MISSING_FIELDS
        mock_response.raise_for_status = MagicMock()

        async def fake_get(url, **kwargs):
            return mock_response

        with patch("services.rss.httpx.AsyncClient") as mock_client_cls:
            mock_ctx = AsyncMock()
            mock_ctx.get = fake_get
            mock_client_cls.return_value.__aenter__ = AsyncMock(return_value=mock_ctx)
            mock_client_cls.return_value.__aexit__ = AsyncMock(return_value=False)

            episodes = await fetch_episodes("https://feeds.example.com/sparse", "pod_rss_002")

        assert len(episodes) == 1
        ep = episodes[0]
        assert ep.title == "Minimal Episode"
        assert ep.duration_seconds is None
        assert ep.image_url is None
        assert ep.published_at is None


# ── Auth middleware (middleware/auth.py) ─────────────────────────────────────

class TestAuthMiddleware:

    async def test_valid_session_cookie_passes(self):
        """Request with valid session cookie passes through auth."""
        import config, main, database
        from httpx import AsyncClient, ASGITransport
        from middleware.auth import create_session_token

        await database.init_db()
        config.settings.test_mode = False
        try:
            token = create_session_token({
                "email": "user@example.com",
                "name": "Test User",
                "picture": "",
            })
            async with AsyncClient(
                transport=ASGITransport(app=main.app),
                base_url="http://test",
                cookies={"distillpod_session": token},
            ) as c:
                r = await c.get("/podcasts/subscriptions")
            assert r.status_code == 200
        finally:
            config.settings.test_mode = True

    async def test_invalid_token_returns_401(self):
        """Request with invalid session token returns 401."""
        import config, main, database
        from httpx import AsyncClient, ASGITransport

        config.settings.test_mode = False
        try:
            async with AsyncClient(
                transport=ASGITransport(app=main.app),
                base_url="http://test",
                cookies={"distillpod_session": "invalid.jwt.token"},
            ) as c:
                r = await c.get("/podcasts/subscriptions")
            assert r.status_code == 401
        finally:
            config.settings.test_mode = True

    async def test_invalid_token_browser_redirects(self):
        """Browser request with invalid token redirects to /unauthorized."""
        import config, main, database
        from httpx import AsyncClient, ASGITransport

        config.settings.test_mode = False
        try:
            async with AsyncClient(
                transport=ASGITransport(app=main.app),
                base_url="http://test",
                follow_redirects=False,
                cookies={"distillpod_session": "bad.token.here"},
            ) as c:
                r = await c.get(
                    "/podcasts/feed",
                    headers={"Accept": "text/html,application/xhtml+xml"},
                )
            assert r.status_code == 302
            assert r.headers["location"] == "/unauthorized"
        finally:
            config.settings.test_mode = True

    async def test_unprotected_route_passes_without_auth(self):
        """Non-protected routes pass through without auth."""
        import config, main, database
        from httpx import AsyncClient, ASGITransport

        config.settings.test_mode = False
        try:
            async with AsyncClient(
                transport=ASGITransport(app=main.app),
                base_url="http://test",
            ) as c:
                r = await c.get("/auth/status")
            # Should not be 401 — auth routes are unprotected
            assert r.status_code != 401
        finally:
            config.settings.test_mode = True


class TestAuthTokenFunctions:

    def test_create_and_verify_token(self):
        """create_session_token + verify_session_token round-trips."""
        from middleware.auth import create_session_token, verify_session_token

        user = {"email": "test@test.com", "name": "Tester", "picture": ""}
        token = create_session_token(user)
        payload = verify_session_token(token)

        assert payload is not None
        assert payload["email"] == "test@test.com"
        assert payload["name"] == "Tester"

    def test_verify_bad_token_returns_none(self):
        """verify_session_token with garbage returns None."""
        from middleware.auth import verify_session_token

        assert verify_session_token("not.a.real.token") is None
        assert verify_session_token("") is None


# ── Podcasts (routers/podcasts.py) ──────────────────────────────────────────

class TestSubscribeNewPodcast:

    async def test_subscribe_new_podcast(self, client):
        """POST /podcasts/subscriptions/{podcast_id} subscribes."""
        r = await client.post(
            "/podcasts/subscriptions/pod_new_001",
            params={
                "feed_url": "https://feeds.example.com/new",
                "title": "New Podcast",
                "image_url": "https://example.com/new.jpg",
            },
        )
        assert r.status_code == 200
        assert r.json()["status"] == "subscribed"

        # Verify it shows up in subscriptions
        r2 = await client.get("/podcasts/subscriptions")
        ids = [s["podcast_id"] for s in r2.json()]
        assert "pod_new_001" in ids


class TestUnsubscribe:

    async def test_unsubscribe(self, client):
        """DELETE /podcasts/subscriptions/{podcast_id} removes subscription."""
        r = await client.delete(f"/podcasts/subscriptions/{PODCAST_ID}")
        assert r.status_code == 200
        assert r.json()["status"] == "unsubscribed"

        # Verify it's gone
        r2 = await client.get("/podcasts/subscriptions")
        ids = [s["podcast_id"] for s in r2.json()]
        assert PODCAST_ID not in ids

    async def test_unsubscribe_nonexistent(self, client):
        """DELETE /podcasts/subscriptions/{id} for nonexistent podcast still returns 200."""
        r = await client.delete("/podcasts/subscriptions/nonexistent_pod")
        assert r.status_code == 200
