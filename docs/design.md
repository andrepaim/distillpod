# EarShot — Design Document
_Version 0.1 · February 2026_

---

## 1. Overview

EarShot is a minimal, self-hosted podcast app where **Botler acts as the backend**. Instead of relying on cloud services or a mobile app, the user opens a React web app in their browser, and all heavy lifting — downloading audio, transcribing, extracting snips — happens on the VPS running Botler.

### Core insight
The core insight: instead of calling an API for each clip, **transcribe the whole episode once** (free, using faster-whisper already installed on VPS), and each snip becomes a zero-cost, zero-latency timestamp lookup in the pre-computed word-level transcript.

### Feature scope (MVP)
1. **Search** — find podcasts by keyword
2. **Subscribe** — follow a podcast and get new episodes via RSS
3. **Listen** — play episodes in the browser (audio served from VPS)
4. **Snip** — tap a button at any moment → instantly get the last 60s of transcript + optional Claude summary (via CLI subprocess, free with Max subscription)

Everything else is out of scope for MVP.

---

## 2. Architecture

```
┌─────────────────────────────────────────────┐
│  Browser (React + Vite)                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────────┐ │
│  │  Search  │ │ Library  │ │   Player     │ │
│  │  Page    │ │  Page    │ │   Page       │ │
│  └────┬─────┘ └────┬─────┘ └──────┬───────┘ │
│       │            │              │          │
│       └────────────┴──────┬───────┘          │
│                    API Client (fetch)         │
└────────────────────────┬────────────────────┘
                         │ HTTP (localhost or nginx proxy)
┌────────────────────────▼────────────────────┐
│  FastAPI Backend (port 8124)                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────────┐ │
│  │/podcasts │ │ /player  │ │   /snips     │ │
│  └──────────┘ └──────────┘ └──────────────┘ │
│  ┌──────────────────────────────────────┐    │
│  │  Services                            │    │
│  │  podcast_index · rss · downloader    │    │
│  │  transcriber · snip_engine           │    │
│  └──────────────────────────────────────┘    │
│  ┌──────────────────────────────────────┐    │
│  │  SQLite DB (aiosqlite)               │    │
│  │  subscriptions · episodes            │    │
│  │  transcripts · snips                 │    │
│  └──────────────────────────────────────┘    │
└─────────────────────────────────────────────┘
         │                        │
    Podcast Index API         faster-whisper
    RSS feeds                 (local, CPU)
    Claude CLI (Max subscription, subprocess)
```

### Component responsibilities

| Component | Responsibility |
|---|---|
| React frontend | UI only — no business logic, all state on backend |
| FastAPI backend | API, orchestration, state management |
| SQLite | Persistent storage (subscriptions, episodes, transcripts, snips) |
| podcast_index.py | Podcast search via Podcast Index API |
| rss.py | RSS feed parsing to extract episode list + metadata |
| downloader.py | Async MP3 download to VPS `/media/` directory |
| transcriber.py | faster-whisper integration, word-level timestamps, background processing |
| snip_engine.py | Timestamp window lookup in transcript + optional Claude summary (via CLI subprocess) |

---

## 3. API Design

### `GET /podcasts/search?q={query}`
Search podcasts via Podcast Index API.
```json
// Response: array of Podcast
[{ "id": "...", "title": "...", "author": "...", "feed_url": "...", "image_url": "..." }]
```

### `GET /podcasts/subscriptions`
List all subscribed podcasts.

### `POST /podcasts/subscriptions/{podcast_id}?feed_url=...&title=...`
Subscribe to a podcast (saves to DB).

### `DELETE /podcasts/subscriptions/{podcast_id}`
Unsubscribe.

### `GET /podcasts/{podcast_id}/episodes?refresh=true`
List episodes for a subscribed podcast. `refresh=true` fetches fresh RSS data and upserts new episodes.

### `POST /player/play`
```json
// Request
{ "episode_id": "...", "audio_url": "..." }

// Response
{ "audio_url": "/player/audio/{episode_id}", "transcript_status": "queued" }
```
Triggers download + background transcription. Returns immediately.

### `GET /player/audio/{episode_id}`
Streams the downloaded MP3 to the browser. Used as `<audio src>`.

### `GET /player/transcript-status/{episode_id}`
```json
{ "episode_id": "...", "status": "none|queued|processing|done|error" }
```
Polled by the frontend every 5s until `done`.

### `POST /snips/?summary=false`
```json
// Request
{ "episode_id": "...", "current_seconds": 342.7 }

// Response: Snip object
{ "id": "...", "text": "...", "summary": null, "start_seconds": 282.7, "end_seconds": 342.7 }
```
Fails with `409` if transcript not yet ready.

### `GET /snips/?episode_id={id}`
List snips, optionally filtered by episode.

### `DELETE /snips/{snip_id}`
Delete a snip.

---

## 4. Data Models

### Podcast
| Field | Type | Description |
|---|---|---|
| id | string | Podcast Index feed ID |
| title | string | Show title |
| author | string | Creator/publisher |
| description | string | Show description |
| image_url | string? | Cover art URL |
| feed_url | string | RSS feed URL |
| episode_count | int? | Total episodes |

### Episode
| Field | Type | Description |
|---|---|---|
| id | string | RSS guid (unique per episode) |
| podcast_id | string | FK → subscriptions |
| title | string | Episode title |
| audio_url | string | Original RSS audio URL |
| duration_seconds | int? | Duration from RSS |
| published_at | datetime? | Publication date |
| downloaded | bool | Whether MP3 is cached locally |
| local_path | string? | VPS path to cached MP3 |
| transcript_status | string | none / queued / processing / done / error |

### Transcript
| Field | Type | Description |
|---|---|---|
| episode_id | string | PK + FK → episodes |
| words_json | string | JSON array of `{word, start, end}` (seconds) |
| language | string? | Detected language |
| created_at | datetime | When transcription completed |

### Snip
| Field | Type | Description |
|---|---|---|
| id | string | UUID |
| episode_id | string | FK → episodes |
| podcast_id | string | For display |
| episode_title | string | Denormalized for easy display |
| podcast_title | string | Denormalized |
| start_seconds | float | Start of snip window |
| end_seconds | float | End of snip window (= playback position when tapped) |
| text | string | Extracted transcript text |
| summary | string? | Claude summary (optional, via CLI subprocess) |
| created_at | datetime | Creation time |

---

## 5. Transcription Strategy

### Tool: faster-whisper
Already installed on VPS. Uses CTranslate2 backend, significantly faster than original Whisper. With `int8` quantization on CPU, processes ~1 hour of audio in ~20-40 minutes (depending on model size).

### Model selection trade-off
| Model | Speed (CPU) | Accuracy | Recommended for |
|---|---|---|---|
| `base` | Fastest | Good | English podcasts, quick iteration |
| `small` | Fast | Better | Multilingual, accents |
| `medium` | Moderate | Very good | High accuracy needs |
| `large-v3` | Slow | Best | Maximum quality |

**Default: `base`** — good enough for extracting conversational podcast content.

### Word-level timestamps
faster-whisper with `word_timestamps=True` returns every word with `{word, start, end}` in seconds. This is the core enabling feature — snips are a direct O(n) scan over this array.

### Background processing flow
```
POST /player/play
  → Download MP3 (async, httpx streaming)
  → asyncio.create_task(transcribe_episode(...))  ← non-blocking
  → Return immediately to client

transcribe_episode():
  → Set transcript_status = 'processing'
  → loop.run_in_executor(None, _transcribe_sync, path)  ← thread pool
  → _transcribe_sync: WhisperModel.transcribe(word_timestamps=True)
  → Save words_json to DB
  → Set transcript_status = 'done'
```

### Race condition prevention
In-memory set `_transcribing` tracks active jobs. `POST /player/play` checks this before spawning a new task. Survives concurrent requests; does NOT survive server restart (acceptable for MVP).

### Transcription ahead of playback
faster-whisper typically processes audio faster than real-time at `base` model. A 1-hour podcast at `base` takes ~15-25 minutes on a modern VPS CPU. The user starts listening → transcription finishes before they're halfway through → snip is available for the entire episode.

---

## 6. Snip Engine

### Algorithm
```python
def extract_snip(words, current_seconds, context_seconds=60):
    start = max(0, current_seconds - context_seconds)
    end = current_seconds

    # O(n) scan — words are chronologically ordered
    snip_words = [w for w in words if w.start >= start and w.end <= end + 1.0]

    return " ".join(w.word.strip() for w in snip_words)
```

The `+1.0` buffer on `end` catches words that started just before the current position but whose end timestamp slightly exceeds it (common with faster-whisper segmentation).

### Context window
Default: **60 seconds**. Configurable via `SNIP_CONTEXT_SECONDS` env var. Enough for a complete thought in a podcast conversation. Can be adjusted per-snip in future.

### Optional summary (Claude via CLI subprocess)
```
Cost: $0 — uses Claude Max subscription already authenticated on VPS
Latency: ~2-4 seconds (CLI startup ~1s + inference)
```
Called only if `?summary=true` is passed. Uses `claude --print` as a subprocess — no API key required, routes through the existing Max subscription session. The prompt asks for a 1-2 sentence capture of the core insight.

```python
import subprocess

def claude_summarize(text: str) -> str:
    prompt = (
        "In 1-2 sentences, capture the core insight from this podcast excerpt. "
        "Be direct, no fluff.\n\n"
        f"Excerpt:\n{text}"
    )
    result = subprocess.run(
        ["claude", "--print", prompt],
        capture_output=True, text=True, timeout=60
    )
    return result.stdout.strip() if result.returncode == 0 else None
```

**Why not OpenAI:** Claude Max is already paid for and authenticated on the VPS. OpenAI would require a separate API key and separate billing.

### Why not real-time transcription per snip?
- **Whisper API cost:** $0.006/min → $0.006 per 60s snip. Small but cumulative.
- **Latency:** 3-8 seconds to get transcript back per snip.
- **Complexity:** Need to extract audio clip via HTTP Range or local seek.

Upfront full transcription wins on all three: $0 cost, ~0ms latency, no audio manipulation needed.

---

## 7. Audio Pipeline

### Download → Cache → Serve
```
1. Browser calls POST /player/play with episode's audio_url
2. Backend downloads MP3 to /media/{md5(episode_id)}.mp3
3. Backend returns /player/audio/{episode_id} as the src URL
4. Browser plays from that URL (FastAPI FileResponse with Range support)
5. Transcription starts in parallel with step 2-3
```

### Filename strategy
`md5(episode_id)` — avoids path injection, handles long/special-character episode IDs, deterministic (idempotent downloads).

### Storage considerations
| Podcast length | File size (typical MP3) |
|---|---|
| 30 min | ~30 MB |
| 1 hour | ~55 MB |
| 2 hours | ~110 MB |

VPS has 146GB disk. With ~30 episodes cached, that's ~1.5GB. Cleanup strategy for MVP: manual. Future: LRU cache with configurable limit.

### Audio streaming
FastAPI's `FileResponse` handles HTTP Range requests automatically (needed for browser `<audio>` seek to work correctly).

---

## 8. Frontend UX

### Pages

**Search (`/`)**
- Search bar → calls `/podcasts/search` on Enter
- Results: podcast card with cover, title, author, subscribe button
- Subscribe: POST → confirmed with button state change

**Library (`/subscriptions`)**
- Left: list of subscribed podcasts (click to expand)
- Right: episode list sorted newest first (refresh = fetch RSS)
- Episode row shows: title, date, transcript status badge
- Click episode → navigate to `/player/{episodeId}`

**Player (`/player/:episodeId`)**
- Episode title header
- Transcript status indicator (polls every 5s if not done)
- Native `<audio>` element (src = `/player/audio/{id}`)
- **✂️ Shot button** — full width, disabled until transcript ready
- Snip list below player: each card shows timestamp range + transcript text + summary

### Snip interaction
```
User clicks ✂️ Snip
  → Frontend reads audioRef.current.currentTime
  → POST /snips/ { episode_id, current_seconds }
  → Backend extracts last 60s of transcript
  → Returns Snip object instantly
  → New SnipCard appears at top of list
```

The whole interaction takes < 200ms (DB read + array slice). No spinner needed.

---

## 9. Deployment

### Prerequisites
```bash
# Backend deps
cd /root/earshot/backend
pip install -r requirements.txt

# Frontend deps
cd /root/earshot/frontend
npm install
npm run build  # outputs to frontend/dist/
```

### Environment
```bash
cp .env.example .env
# Edit .env with your Podcast Index API key
# Summaries use Claude CLI (claude --print) — no extra key needed
# Requires: claude CLI installed + authenticated via `claude login`
```

Get free Podcast Index API credentials at: https://api.podcastindex.com/

### Running (development)
```bash
# Terminal 1 — backend
cd /root/earshot/backend
uvicorn main:app --host 127.0.0.1 --port 8124 --reload

# Terminal 2 — frontend dev server
cd /root/earshot/frontend
npm run dev  # http://localhost:5173
```

### Running (production)
```bash
# Build frontend
cd /root/earshot/frontend && npm run build

# Run backend (serves built frontend at /)
cd /root/earshot/backend
uvicorn main:app --host 127.0.0.1 --port 8124
```

### nginx config
```nginx
server {
    listen 8125;  # or behind 443 with SSL
    server_name _;

    location /api/ {
        proxy_pass http://127.0.0.1:8124/;
        proxy_set_header Host $host;
    }

    location / {
        root /root/earshot/frontend/dist;
        try_files $uri $uri/ /index.html;
    }
}
```

Or simpler: FastAPI serves the built frontend directly (already configured in `main.py` when `frontend/dist/` exists).

### Systemd service
```ini
[Unit]
Description=EarShot Backend
After=network.target

[Service]
WorkingDirectory=/root/earshot/backend
ExecStart=/usr/bin/uvicorn main:app --host 127.0.0.1 --port 8124
Restart=on-failure
EnvironmentFile=/root/earshot/.env

[Install]
WantedBy=multi-user.target
```

---

## 10. Future Extensions

| Feature | Effort | Notes |
|---|---|---|
| Full-text search in transcripts | Low | SQLite FTS5, already possible |
| Export snips to Notion/Obsidian | Medium | Markdown export, API integrations |
| Snip collections / tags | Low | Add tags table, filter UI |
| Chapter navigation | Medium | faster-whisper segments → chapters |
| Snip sharing (image card) | Medium | Server-side canvas rendering |
| Auto-discovery of new episodes | Low | Cron job to poll RSS feeds |
| Progressive transcription | High | Stream words as they're transcribed |
| Speaker diarization | High | pyannote.audio integration |
| Mobile-optimized PWA | Medium | Add manifest.json, offline support |
| Botler-initiated snip | Fun | "Snip that last bit" via Telegram → auto-snip current episode |

---

## 11. Key Decisions Log

| Decision | Rationale |
|---|---|
| FastAPI over Node.js | faster-whisper is Python; same process = easier integration |
| SQLite over PostgreSQL | Single-user app, VPS-local, zero config |
| faster-whisper `base` as default | Fastest model, transcribes ahead of playback for most podcasts |
| Upfront full-episode transcription | Eliminates per-snip cost/latency; the core architectural advantage |
| Word timestamps (not segment) | Enables precise snip window extraction without audio manipulation |
| No auth | Single-user, VPS-local, behind SSH tunnel if sensitive |
| Native `<audio>` element | FileResponse + Range headers = browser handles seeking natively |
| Podcast Index API over iTunes | Better data quality, open ecosystem, more fields |
| Claude CLI subprocess for summaries | Claude Max already paid + authenticated on VPS; $0 marginal cost vs OpenAI API billing; `claude --print` is officially supported for scripting |
