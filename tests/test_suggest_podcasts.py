"""
Unit tests for suggest-podcasts.py helper functions.
"""
import importlib.util
import sqlite3
import tempfile
import os
from pathlib import Path
from unittest.mock import patch, MagicMock

SCRIPT = str(Path(__file__).resolve().parent.parent / "scripts" / "suggest-podcasts.py")
spec = importlib.util.spec_from_file_location("suggest_podcasts", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


# ── claude() ─────────────────────────────────────────────────────────────────

class TestClaude:

    def _run(self, stdout: str):
        mock = MagicMock()
        mock.returncode = 0
        mock.stdout = stdout
        with patch("subprocess.run", return_value=mock) as p:
            result = mod.claude("any prompt")
        return result

    def test_returns_clean_json(self):
        raw = '["query one", "query two"]'
        assert self._run(raw) == raw

    def test_preserves_markdown_fences(self):
        """claude() only strips whitespace; fence stripping is in get_search_queries()"""
        raw = '```json\n["query one"]\n```'
        result = self._run(raw)
        assert result == '```json\n["query one"]\n```'

    def test_preserves_fences_no_lang(self):
        """claude() only strips whitespace; fence stripping is in get_search_queries()"""
        raw = '```\n["q1", "q2"]\n```'
        result = self._run(raw)
        assert result == '```\n["q1", "q2"]\n```'

    def test_strips_leading_trailing_whitespace(self):
        raw = '  \n["q1"]\n  '
        result = self._run(raw)
        assert result == '["q1"]'

    def test_raises_on_nonzero_exit(self):
        mock = MagicMock()
        mock.returncode = 1
        mock.stderr = "error"
        with patch("subprocess.run", return_value=mock):
            import pytest
            with pytest.raises(RuntimeError):
                mod.claude("prompt")


# ── get_search_queries() fence stripping ──────────────────────────────────────

class TestGetSearchQueries:

    def _run(self, claude_output: str):
        with patch.object(mod, "claude", return_value=claude_output):
            return mod.get_search_queries("some context")

    def test_parses_clean_json(self):
        result = self._run('["q1", "q2", "q3", "q4"]')
        assert result == ["q1", "q2", "q3", "q4"]

    def test_strips_markdown_fences(self):
        result = self._run('```json\n["q1", "q2", "q3", "q4"]\n```')
        assert result == ["q1", "q2", "q3", "q4"]

    def test_strips_fences_no_lang(self):
        result = self._run('```\n["q1", "q2"]\n```')
        assert result == ["q1", "q2"]


# ── Deduplication / filtering ─────────────────────────────────────────────────

SCHEMA = """
CREATE TABLE subscriptions (podcast_id TEXT PRIMARY KEY, feed_url TEXT, title TEXT, subscribed_at TEXT);
CREATE TABLE suggestions   (id TEXT PRIMARY KEY, feed_url TEXT, dismissed INTEGER DEFAULT 0);
"""

def _make_db(seed_sql=""):
    f = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
    f.close()
    conn = sqlite3.connect(f.name)
    conn.executescript(SCHEMA)
    if seed_sql:
        conn.executescript(seed_sql)
    conn.commit()
    conn.close()
    return f.name


class TestFiltering:

    def test_subscribed_feed_excluded(self):
        db_path = _make_db(
            "INSERT INTO subscriptions VALUES ('p1','https://feeds.example.com/subscribed','My Show','2026-01-01');"
        )
        db = sqlite3.connect(db_path)
        db.row_factory = sqlite3.Row
        urls = mod.get_subscribed_feed_urls(db)
        db.close()
        os.unlink(db_path)
        assert "https://feeds.example.com/subscribed" in urls

    def test_existing_suggestion_excluded(self):
        db_path = _make_db(
            "INSERT INTO suggestions VALUES ('s1','https://feeds.example.com/already', 0);"
        )
        db = sqlite3.connect(db_path)
        db.row_factory = sqlite3.Row
        urls = mod.get_existing_suggestion_feed_urls(db)
        db.close()
        os.unlink(db_path)
        assert "https://feeds.example.com/already" in urls

    def test_dismissed_suggestion_still_excluded(self):
        # dismissed=1 should still be in the exclusion set (avoid re-suggesting dismissed)
        db_path = _make_db(
            "INSERT INTO suggestions VALUES ('s1','https://feeds.example.com/dismissed', 1);"
        )
        db = sqlite3.connect(db_path)
        db.row_factory = sqlite3.Row
        urls = mod.get_existing_suggestion_feed_urls(db)
        db.close()
        os.unlink(db_path)
        assert "https://feeds.example.com/dismissed" in urls

    def test_empty_db_returns_empty_sets(self):
        db_path = _make_db()
        db = sqlite3.connect(db_path)
        db.row_factory = sqlite3.Row
        assert mod.get_subscribed_feed_urls(db) == set()
        assert mod.get_existing_suggestion_feed_urls(db) == set()
        db.close()
        os.unlink(db_path)

    def test_itunes_search_skips_no_feed_url(self):
        """search_itunes should skip results with no feedUrl."""
        fake_response = {
            "results": [
                {"collectionId": 1, "collectionName": "No Feed", "artistName": "X",
                 "collectionCensoredName": "desc"},  # missing feedUrl
                {"collectionId": 2, "collectionName": "Has Feed", "artistName": "Y",
                 "collectionCensoredName": "desc2", "feedUrl": "https://feeds.example.com/ok",
                 "artworkUrl600": "https://img.example.com/art.jpg"},
            ]
        }
        mock_resp = MagicMock()
        mock_resp.json.return_value = fake_response
        mock_resp.raise_for_status = MagicMock()

        with patch("httpx.Client") as MockClient:
            MockClient.return_value.__enter__.return_value.get.return_value = mock_resp
            results = mod.search_itunes("test query", limit=5)

        assert len(results) == 1
        assert results[0]["title"] == "Has Feed"
        assert results[0]["feed_url"] == "https://feeds.example.com/ok"
