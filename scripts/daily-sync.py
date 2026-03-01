#!/usr/bin/env python3
"""
DistillPod daily sync — runs at 3am BRT.
For each subscription: fetches RSS, inserts new episodes, downloads & transcribes them.
New episodes appear as transcript_status='done' in the app, ready before the morning run.
"""

import asyncio
import json
import logging
import sys
from datetime import datetime, timezone, timedelta
from pathlib import Path

# Add backend to Python path
sys.path.insert(0, "/root/distillpod/backend")

from database import get_db
from services.rss import fetch_episodes
from services.downloader import download_episode
from services.transcriber import _transcribe_sync

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("distillpod-sync")

# Only transcribe episodes published within this window (avoid transcribing old backlog)
RECENCY_HOURS = 48


async def process_subscription(podcast_id: str, feed_url: str, title: str) -> dict:
    log.info(f"▶ {title}")
    stats = {"new": 0, "downloaded": 0, "transcribed": 0, "skipped": 0}

    # Fetch latest episodes from RSS
    try:
        episodes = await fetch_episodes(feed_url, podcast_id, limit=5)
    except Exception as e:
        log.error(f"  RSS fetch failed: {e}")
        return stats

    db = await get_db()
    try:
        cutoff = datetime.now(timezone.utc) - timedelta(hours=RECENCY_HOURS)

        for ep in episodes:
            # Check existing DB state
            row = await db.execute_fetchone(
                "SELECT transcript_status, downloaded FROM episodes WHERE id = ?",
                (ep.id,),
            )

            if row is None:
                # New episode — insert it
                pub = ep.published_at.isoformat() if ep.published_at else None
                await db.execute(
                    """INSERT INTO episodes
                       (id, podcast_id, title, description, audio_url,
                        duration_seconds, published_at, image_url,
                        downloaded, local_path, transcript_status)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, 'none')""",
                    (ep.id, podcast_id, ep.title, ep.description, ep.audio_url,
                     ep.duration_seconds, pub, ep.image_url),
                )
                await db.commit()
                stats["new"] += 1
                transcript_status = "none"
                log.info(f"  + New episode: {ep.title[:70]}")
            else:
                transcript_status = row["transcript_status"]

            # Skip if already transcribed
            if transcript_status == "done":
                stats["skipped"] += 1
                continue

            # Skip old episodes — don't transcribe the entire backlog
            if ep.published_at and ep.published_at.replace(tzinfo=timezone.utc) < cutoff:
                log.info(f"  ⏩ Skipping old episode: {ep.title[:70]}")
                stats["skipped"] += 1
                continue

            # --- Download ---
            log.info(f"  ⬇  Downloading: {ep.title[:70]}")
            await db.execute(
                "UPDATE episodes SET transcript_status = 'queued' WHERE id = ?",
                (ep.id,),
            )
            await db.commit()

            try:
                local_path = await download_episode(ep.id, ep.audio_url)
            except Exception as e:
                log.error(f"  Download failed: {e}")
                await db.execute(
                    "UPDATE episodes SET transcript_status = 'error' WHERE id = ?",
                    (ep.id,),
                )
                await db.commit()
                continue

            await db.execute(
                "UPDATE episodes SET downloaded = 1, local_path = ?, transcript_status = 'processing' WHERE id = ?",
                (str(local_path), ep.id),
            )
            await db.commit()
            stats["downloaded"] += 1

            # --- Transcribe ---
            log.info(f"  🎙  Transcribing: {ep.title[:70]}")
            try:
                loop = asyncio.get_event_loop()
                words = await loop.run_in_executor(None, _transcribe_sync, str(local_path))

                await db.execute(
                    """INSERT OR REPLACE INTO transcripts
                       (episode_id, words_json, language, created_at)
                       VALUES (?, ?, 'auto', ?)""",
                    (ep.id, json.dumps(words), datetime.now(timezone.utc).isoformat()),
                )
                await db.execute(
                    "UPDATE episodes SET transcript_status = 'done' WHERE id = ?",
                    (ep.id,),
                )
                await db.commit()
                stats["transcribed"] += 1
                log.info(f"  ✓  Done: {ep.title[:70]}")

            except Exception as e:
                log.error(f"  Transcription failed: {e}")
                await db.execute(
                    "UPDATE episodes SET transcript_status = 'error' WHERE id = ?",
                    (ep.id,),
                )
                await db.commit()

        # Update last_checked timestamp
        await db.execute(
            "UPDATE subscriptions SET last_checked = ? WHERE podcast_id = ?",
            (datetime.now(timezone.utc).isoformat(), podcast_id),
        )
        await db.commit()

    finally:
        await db.close()

    return stats


async def main() -> None:
    log.info("=" * 60)
    log.info("DistillPod Daily Sync — starting")
    log.info("=" * 60)

    db = await get_db()
    subs = await db.execute_fetchall(
        "SELECT podcast_id, feed_url, title FROM subscriptions"
    )
    await db.close()

    if not subs:
        log.info("No subscriptions found — nothing to do.")
        return

    log.info(f"{len(subs)} subscription(s) found")

    total = {"new": 0, "downloaded": 0, "transcribed": 0, "skipped": 0}
    for sub in subs:
        result = await process_subscription(
            sub["podcast_id"], sub["feed_url"], sub["title"]
        )
        for k in total:
            total[k] += result[k]

    log.info("=" * 60)
    log.info(
        f"Done — {total['new']} new episodes, "
        f"{total['transcribed']} transcribed, "
        f"{total['skipped']} skipped"
    )
    log.info("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
