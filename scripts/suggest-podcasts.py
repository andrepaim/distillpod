#!/usr/bin/env python3
"""
Daily podcast recommendation engine for DistillPod.
Uses Anthropic API to reason about subscriptions, iTunes API to search,
and stores 4 fresh suggestions in the database.
"""

import json
import os
import sqlite3
import sys
import uuid
import httpx
import anthropic
from datetime import datetime, timezone

DB_PATH    = "/root/distillpod/distillpod.db"
ITUNES_URL = "https://itunes.apple.com/search"
N_SUGGEST  = 4

ANTHROPIC_API_KEY = os.environ.get(
    "ANTHROPIC_API_KEY",
    "REDACTED"
)
_client = anthropic.Anthropic(api_key=ANTHROPIC_API_KEY)


def get_db():
    db = sqlite3.connect(DB_PATH)
    db.row_factory = sqlite3.Row
    return db

def get_subscriptions(db):
    return db.execute("SELECT podcast_id, title, feed_url FROM subscriptions").fetchall()

def get_recent_episode_titles(db, podcast_id, limit=8):
    rows = db.execute(
        "SELECT title FROM episodes WHERE podcast_id = ? ORDER BY published_at DESC LIMIT ?",
        (podcast_id, limit),
    ).fetchall()
    return [r["title"] for r in rows]

def get_existing_suggestion_feed_urls(db):
    return {r["feed_url"] for r in db.execute("SELECT feed_url FROM suggestions").fetchall()}

def get_subscribed_feed_urls(db):
    return {r["feed_url"] for r in db.execute("SELECT feed_url FROM subscriptions").fetchall()}


def claude(prompt: str) -> str:
    message = _client.messages.create(
        model="claude-haiku-4-5",
        max_tokens=512,
        messages=[{"role": "user", "content": prompt}],
    )
    return message.content[0].text.strip()


def get_search_queries(subs_context: str) -> list:
    prompt = f"""You are helping a user discover new podcasts based on what they already listen to.

{subs_context}

Based on the depth, topics, and style of these shows, generate exactly {N_SUGGEST} podcast search queries to find similar high-quality podcasts the user is likely not aware of.

Rules:
- Prefer niche and technical over mainstream
- Do not suggest queries that would return the shows they already follow
- Each query should target a different angle (e.g. AI safety, ML engineering practice, research interviews, one wildcard)
- Return ONLY a JSON array of {N_SUGGEST} strings, nothing else

Example output: ["AI alignment research podcast", "machine learning systems engineering", "LLM interpretability deep dives", "tech founder AI bets"]"""

    out = claude(prompt)
    if out.startswith("```"):
        out = "\n".join(out.split("\n")[1:])
        if out.endswith("```"):
            out = out.rsplit("```", 1)[0]
    queries = json.loads(out.strip())
    if not isinstance(queries, list):
        raise ValueError(f"Expected list, got: {type(queries)}")
    return [str(q) for q in queries[:N_SUGGEST]]


def get_reason(sub_titles: list, podcast: dict) -> str:
    prompt = f"""A user who listens to {', '.join(sub_titles)} was suggested this podcast:

TITLE: {podcast['title']}
AUTHOR: {podcast.get('author', '')}
DESCRIPTION: {(podcast.get('description') or '')[:300]}

In one sentence of maximum 12 words, explain why this is relevant to their interests.
Be specific. Return only the sentence, no punctuation at the end."""
    return claude(prompt).strip().rstrip(".")


def search_itunes(query: str, limit: int = 8) -> list:
    with httpx.Client(timeout=10) as client:
        r = client.get(ITUNES_URL, params={"media": "podcast", "entity": "podcast", "term": query, "limit": limit})
        r.raise_for_status()
    results = []
    for item in r.json().get("results", []):
        feed_url = item.get("feedUrl", "")
        if not feed_url:
            continue
        results.append({
            "id":          str(item.get("collectionId", "")),
            "title":       item.get("collectionName", ""),
            "author":      item.get("artistName", ""),
            "description": item.get("collectionCensoredName", ""),
            "image_url":   item.get("artworkUrl600") or item.get("artworkUrl100", ""),
            "feed_url":    feed_url,
        })
    return results


def main():
    db = get_db()
    subs = get_subscriptions(db)
    if not subs:
        print("[suggest] No subscriptions found.")
        return

    lines, sub_titles = [], []
    for sub in subs:
        titles = get_recent_episode_titles(db, sub["podcast_id"])
        sub_titles.append(sub["title"])
        lines.append(f'SHOW: {sub["title"]}')
        if titles:
            lines.append("RECENT EPISODES:")
            for t in titles:
                lines.append(f"  - {t}")
        lines.append("")
    subs_context = "\n".join(lines)

    excluded_feeds = get_subscribed_feed_urls(db) | get_existing_suggestion_feed_urls(db)

    print(f"[suggest] Subscriptions: {sub_titles}")
    print("[suggest] Asking Claude for search queries...")

    try:
        queries = get_search_queries(subs_context)
    except Exception as e:
        print(f"[suggest] Claude query generation failed: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"[suggest] Queries: {queries}")

    suggestions = []
    for query in queries:
        print(f"[suggest] Searching: {query!r}")
        try:
            results = search_itunes(query)
        except Exception as e:
            print(f"[suggest] iTunes search failed: {e}", file=sys.stderr)
            continue

        pick = next((r for r in results if r["feed_url"] not in excluded_feeds), None)
        if not pick:
            print(f"[suggest] No new results for {query!r}")
            continue
        excluded_feeds.add(pick["feed_url"])

        print(f"[suggest] Pick: {pick['title']} — asking Claude for reason...")
        try:
            reason = get_reason(sub_titles, pick)
        except Exception as e:
            reason = "Similar topics and depth to your current subscriptions"
            print(f"[suggest] Reason fallback ({e})")

        suggestions.append({
            "id": str(uuid.uuid4()),
            "podcast_index_id": pick["id"],
            "title": pick["title"],
            "author": pick["author"],
            "description": pick["description"],
            "image_url": pick["image_url"],
            "feed_url": pick["feed_url"],
            "reason": reason,
            "suggested_at": datetime.now(timezone.utc).isoformat(),
            "dismissed": 0,
        })
        print(f"[suggest] + {pick['title']}: {reason!r}")

    if not suggestions:
        print("[suggest] No suggestions generated.")
        return

    db.execute("DELETE FROM suggestions WHERE dismissed = 0")
    db.executemany(
        """INSERT INTO suggestions
           (id, podcast_index_id, title, author, description, image_url, feed_url, reason, suggested_at, dismissed)
           VALUES (:id, :podcast_index_id, :title, :author, :description, :image_url, :feed_url, :reason, :suggested_at, :dismissed)""",
        suggestions,
    )
    db.commit()
    db.close()
    print(f"[suggest] Done. {len(suggestions)} suggestions stored.")


if __name__ == "__main__":
    main()
