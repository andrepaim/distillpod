"""
Backend API tests using httpx AsyncClient against in-memory test DB.
"""
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
