#!/usr/bin/env python3
"""
DistillPod daily sync — runs at 3am BRT.
For each subscription: fetches RSS, inserts new episodes, downloads & transcribes them.
New episodes appear as transcript_status='done' in the app, ready before the morning run.
"""

import asyncio
import json
import logging
import os
import sys
import urllib.request
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


TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
TELEGRAM_CHAT_ID = os.getenv("TELEGRAM_CHAT_ID", "")


def _telegram_notify(message: str) -> None:
    """Send a Telegram message. Best-effort — never raises."""
    try:
        url = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendMessage"
        payload = json.dumps({"chat_id": TELEGRAM_CHAT_ID, "text": message}).encode()
        req = urllib.request.Request(url, data=payload,
                                     headers={"Content-Type": "application/json"})
        urllib.request.urlopen(req, timeout=10)
    except Exception as e:
        log.warning(f"Telegram notify failed: {e}")


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

        # Ad detection + removal (after transcription)
        episodes_for_ads = await db.execute_fetchall(
            '''SELECT id, local_path FROM episodes
               WHERE podcast_id = ? AND transcript_status = 'done'
               AND local_path IS NOT NULL AND adfree_path IS NULL AND ads_detected IS NULL''',
            (podcast_id,)
        )
        for ep_row in episodes_for_ads:
            try:
                from services.ad_detector import detect_ads, remove_ads_from_audio
                transcript_row = await db.execute_fetchone(
                    'SELECT words_json FROM transcripts WHERE episode_id = ?', (ep_row['id'],)
                )
                if not transcript_row:
                    continue
                log.info(f'  Ad detection for: {ep_row["id"]}')
                ads = detect_ads(transcript_row['words_json'])
                log.info(f'  Found {len(ads)} ad segment(s)')
                if ads:
                    adfree_path = ep_row['local_path'].replace('.mp3', '_adfree.mp3').replace('.m4a', '_adfree.m4a')
                    success = remove_ads_from_audio(ep_row['local_path'], ads, adfree_path)
                    if success:
                        await db.execute(
                            'UPDATE episodes SET adfree_path = ?, ads_detected = ? WHERE id = ?',
                            (adfree_path, len(ads), ep_row['id'])
                        )
                    else:
                        await db.execute('UPDATE episodes SET ads_detected = 0 WHERE id = ?', (ep_row['id'],))
                else:
                    await db.execute('UPDATE episodes SET ads_detected = 0 WHERE id = ?', (ep_row['id'],))
                await db.commit()
            except Exception as exc:
                log.warning(f'  Ad detection failed for {ep_row["id"]}: {exc}')
                continue

        # Chapterization (after transcription + ad detection)
        episodes_for_chapters = await db.execute_fetchall(
            '''SELECT id FROM episodes
               WHERE podcast_id = ? AND transcript_status = 'done'
               AND (chapters_status IS NULL OR chapters_status = 'none')''',
            (podcast_id,)
        )
        for ep_row in episodes_for_chapters:
            ep_id = ep_row['id']
            try:
                from services.chapterizer import chapterize
                transcript_row = await db.execute_fetchone(
                    'SELECT words_json FROM transcripts WHERE episode_id = ?', (ep_id,)
                )
                if not transcript_row:
                    continue
                log.info(f'  Chapterizing: {ep_id}')
                await db.execute(
                    "UPDATE episodes SET chapters_status = 'processing' WHERE id = ?", (ep_id,)
                )
                await db.commit()

                loop = asyncio.get_event_loop()
                result = await loop.run_in_executor(
                    None, chapterize, transcript_row['words_json']
                )

                # Delete old chapters if any (re-run safety)
                await db.execute('DELETE FROM chapters WHERE episode_id = ?', (ep_id,))

                # Insert new chapters
                import uuid
                for ch in result['chapters']:
                    await db.execute(
                        'INSERT INTO chapters (id, episode_id, title, start_time) VALUES (?, ?, ?, ?)',
                        (str(uuid.uuid4()), ep_id, ch['title'], ch['start_time'])
                    )

                # Store summary on episode row
                await db.execute(
                    "UPDATE episodes SET summary = ?, chapters_status = 'done' WHERE id = ?",
                    (result['summary'], ep_id)
                )
                await db.commit()
                log.info(f'  ✓ {len(result["chapters"])} chapters, summary saved')

            except Exception as exc:
                log.warning(f'  Chapterization failed for {ep_id}: {exc}')
                await db.execute(
                    "UPDATE episodes SET chapters_status = 'error' WHERE id = ?", (ep_id,)
                )
                await db.commit()
                continue

        # Update last_checked timestamp
        await db.execute(
            "UPDATE subscriptions SET last_checked = ? WHERE podcast_id = ?",
            (datetime.now(timezone.utc).isoformat(), podcast_id),
        )
        await db.commit()

    finally:
        await db.close()

    return stats


async def reset_stale_processing() -> int:
    """
    Reset episodes stuck in 'processing' back to 'none' so they get retried.
    An episode is considered stale if it has been in 'processing' for longer
    than 2 hours — this only happens when the process crashed
    mid-transcription. Also removes the partial local file to avoid feeding
    a corrupted audio to the transcriber on retry.
    Returns the number of episodes reset.
    """
    db = await get_db()
    try:
        # Any episode still in 'processing' when the sync starts is a crash
        # victim — the sync sets this status right before transcribing, so
        # if the process survived it would have moved it to 'done' or 'error'.
        stale = await db.execute_fetchall(
            "SELECT id, title, local_path FROM episodes WHERE transcript_status = 'processing'",
        )
        if not stale:
            return 0
        for ep in stale:
            log.warning(f"  ↩  Resetting stale episode: {ep['title'][:70]}")
            if ep["local_path"] and Path(ep["local_path"]).exists():
                Path(ep["local_path"]).unlink(missing_ok=True)
                log.warning(f"     Deleted partial file: {ep['local_path']}")
            await db.execute(
                "UPDATE episodes SET transcript_status = 'none', downloaded = 0, local_path = NULL WHERE id = ?",
                (ep["id"],),
            )
        await db.commit()
        return len(stale)
    finally:
        await db.close()


async def report_errors() -> None:
    """
    After the sync run, check for episodes in 'error' state and notify via
    Telegram if any are found. Groups them by podcast for readability.
    """
    db = await get_db()
    try:
        errors = await db.execute_fetchall(
            """SELECT e.title, s.title as podcast_title
               FROM episodes e
               JOIN subscriptions s ON s.podcast_id = e.podcast_id
               WHERE e.transcript_status = 'error'
               ORDER BY e.published_at DESC""",
        )
    finally:
        await db.close()

    if not errors:
        log.info("No episodes in error state.")
        return

    log.warning(f"{len(errors)} episode(s) in error state — sending Telegram alert")
    lines = [f"⚠️ DistillPod: {len(errors)} episode(s) failed to process:\n"]
    for ep in errors:
        lines.append(f"• [{ep['podcast_title']}] {ep['title']}")
    lines.append("\nCheck /root/distillpod/sync.log for details.")
    _telegram_notify("\n".join(lines))


async def main() -> None:
    log.info("=" * 60)
    log.info("DistillPod Daily Sync — starting")
    log.info("=" * 60)

    # --- Fix 1: Reset stale processing episodes before doing anything else ---
    log.info("Checking for stale episodes stuck in 'processing'...")
    reset_count = await reset_stale_processing()
    if reset_count:
        log.warning(f"Reset {reset_count} stale episode(s) to 'none' for retry")
    else:
        log.info("No stale episodes found")

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

    # --- Fix 2: Report any episodes in error state via Telegram ---
    await report_errors()


if __name__ == "__main__":
    asyncio.run(main())
