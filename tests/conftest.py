"""
Shared fixtures for DistillPod test suite.
"""
import os
import sys
import pytest
import pytest_asyncio
import sqlite3
import tempfile

# Add backend to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "../backend"))

# ── IDs used across tests ─────────────────────────────────────────────────────
PODCAST_ID   = "pod_test_001"
EPISODE_ID_1 = "ep_001"
EPISODE_ID_2 = "ep_002"
EPISODE_ID_3 = "ep_003"
GIST_ID      = "gist_001"
SUG_ID_1     = "sug_001"   # not dismissed
SUG_ID_2     = "sug_002"   # dismissed


def seed_db(db_path: str):
    conn = sqlite3.connect(db_path)
    conn.executescript(f"""
        INSERT INTO subscriptions (podcast_id, feed_url, title, image_url, subscribed_at) VALUES
          ('{PODCAST_ID}', 'https://feeds.example.com/test', 'Test Podcast',
           'https://example.com/img.jpg', '2026-01-01T00:00:00');

        INSERT INTO episodes (id, podcast_id, title, description, audio_url,
                              duration_seconds, published_at, image_url, downloaded, transcript_status) VALUES
          ('{EPISODE_ID_1}', '{PODCAST_ID}', 'Episode One',   'Long desc',   'https://audio.example.com/1.mp3', 3600, '2026-02-01T00:00:00', NULL, 0, 'none'),
          ('{EPISODE_ID_2}', '{PODCAST_ID}', 'Episode Two',   'Another desc','https://audio.example.com/2.mp3', 1800, '2026-02-02T00:00:00', NULL, 0, 'done'),
          ('{EPISODE_ID_3}', '{PODCAST_ID}', 'Episode Three', NULL,           'https://audio.example.com/3.mp3', NULL, '2026-02-03T00:00:00', NULL, 0, 'none');

        INSERT INTO gists (id, episode_id, podcast_id, episode_title, podcast_title,
                           start_seconds, end_seconds, text, summary, created_at) VALUES
          ('{GIST_ID}', '{EPISODE_ID_1}', '{PODCAST_ID}', 'Episode One', 'Test Podcast',
           60.0, 120.0, 'Some transcribed text', 'AI summary', '2026-02-01T01:00:00');

        INSERT INTO suggestions (id, podcast_index_id, title, author, description, image_url,
                                 feed_url, reason, suggested_at, dismissed) VALUES
          ('{SUG_ID_1}', 'pi_001', 'Great AI Podcast', 'Some Host', 'About AI',
           'https://example.com/ai.jpg', 'https://feeds.example.com/ai',
           'Covers LLM research in depth', '2026-03-01T09:00:00', 0),
          ('{SUG_ID_2}', 'pi_002', 'Dismissed Podcast', 'Other Host', 'About stuff',
           NULL, 'https://feeds.example.com/stuff',
           'Not relevant', '2026-03-01T09:00:00', 1);
    """)
    conn.commit()
    conn.close()


@pytest.fixture
def tmp_db():
    """Temporary SQLite DB with schema applied and seed data."""
    from database import SCHEMA
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
        db_path = f.name
    conn = sqlite3.connect(db_path)
    conn.executescript(SCHEMA)
    conn.commit()
    conn.close()
    seed_db(db_path)
    yield db_path
    os.unlink(db_path)


@pytest_asyncio.fixture
async def client(tmp_db):
    """
    httpx AsyncClient pointed at the FastAPI app.
    Patches database.DB_PATH to use the temp DB and enables test_mode.
    """
    import database
    import config

    orig_db_path = database.DB_PATH
    orig_test_mode = config.settings.test_mode

    # Redirect DB and enable test_mode auth bypass
    database.DB_PATH = tmp_db
    config.settings.test_mode = True

    # Import app AFTER patching (or re-use already imported)
    import main
    from httpx import AsyncClient, ASGITransport

    # Init DB schema in the temp file (startup event won't fire in test client)
    from database import init_db
    await init_db()

    async with AsyncClient(
        transport=ASGITransport(app=main.app),
        base_url="http://test",
    ) as c:
        yield c

    # Restore
    database.DB_PATH = orig_db_path
    config.settings.test_mode = orig_test_mode
