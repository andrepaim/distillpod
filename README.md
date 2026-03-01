# DistillPod ⚗️

A minimal, self-hosted podcast app built around one idea: **distill any episode into what actually matters.**

No cloud services. No subscriptions. No per-use API costs. Your VPS does all the heavy lifting.

---

## What is this?

DistillPod is a mobile-first web app for listening to podcasts and capturing distillations from them.

**A distillation** is a moment you flag while listening. Tap the ⚗️ Distill button at any point and DistillPod extracts the last 60 seconds of transcript around that moment, then passes it to the Claude Code CLI, which returns a verbatim quote and a 1-2 sentence insight. Every distillation is AI-powered — no toggles, no modes.

The core insight: most podcast apps call an LLM API per clip (~$0.01 to $0.05 per call). DistillPod flips this — it **transcribes the whole episode once** using [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (free, runs locally on CPU), and each distillation becomes a near-instant timestamp lookup in the pre-computed word-level transcript. The AI step is free too — see [How AI Works for Free](#how-ai-works-for-free).

You open the app in your phone's browser. Everything else — downloading audio, transcribing, serving — happens on your VPS.

---

## What is a Distillation?

A distillation is the core unit of DistillPod. Tap ⚗️ **Distill** at any moment while listening and the backend:

1. Reads the current playback position (in seconds)
2. Slices the pre-computed word-level transcript for the window `[now - 60s, now]`
3. Calls `claude --print` as a subprocess with the excerpt and a structured prompt
4. The Claude Code CLI returns `{ "quote": "...", "insight": "..." }`:
   - **quote** — the single most memorable verbatim sentence from the excerpt
   - **insight** — 1-2 sentence takeaway capturing the core idea
5. The distillation card shows the quote (italic, styled) and insight

Every distillation is AI-powered. There are no modes or toggles — tap and get a distillation.

**Latency: ~30s** (Claude CLI startup + inference). Uses your Claude Max subscription — no extra API cost.

The 60-second context window is configurable via `GIST_CONTEXT_SECONDS` in `.env`.

---

## Features

- **📰 Home feed** — unified list of the latest episodes across all subscribed podcasts, sorted by date. Shows whether you've listened and how many distillations you have per episode.
- **🔍 Search** — find podcasts via the iTunes Search API (no key needed). Subscribe with one tap. When the search box is empty, a **🤖 Suggested for you** section surfaces daily AI-generated recommendations based on your listening history — dismiss any you're not interested in.
- **📚 Library** — browse your subscribed podcasts and their episodes. Transcript status shown per episode.
- **▶️ Player** — stream audio directly from your VPS. Transcription kicks off automatically in the background when you press play.
- **⚗️ Distill** — tap at any moment while listening. Captures the last 60 seconds of transcript, passes it to the Claude Code CLI, and returns a verbatim quote and insight (~30s, zero extra API cost).
- **📋 Distillations library** — browse all your distillations grouped by episode. Copy to clipboard, delete, or jump back to the episode.
- **⚡ Stale-while-revalidate caching** — the app feels instant on return visits. Data is cached in localStorage with a 30-minute TTL and refreshed silently in the background.

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  Browser (React + Vite + Tailwind CSS)               │
│                                                       │
│  Home · Search · Library · Player · Distillations   │
│                                                       │
│  localStorage cache (stale-while-revalidate, 30min)  │
└───────────────────────┬─────────────────────────────┘
                        │ HTTP (SSH tunnel or direct IP)
┌───────────────────────▼─────────────────────────────┐
│  FastAPI backend (port 8124)                         │
│                                                       │
│  /podcasts   /player   /gists                        │
│                                                       │
│  ┌──────────────────────────────────────────────┐   │
│  │  Services                                     │   │
│  │  podcast_index · rss · downloader             │   │
│  │  transcriber · gist_engine                    │   │
│  └──────────────────────────────────────────────┘   │
│                                                       │
│  SQLite — subscriptions, episodes,                   │
│            transcripts, distillations                │
└──────────┬─────────────────────────┬────────────────┘
           │                         │
     iTunes Search API         faster-whisper (local)
     RSS feeds                 Claude CLI (Max subscription)
     Podcast Index API (opt.)
```

### Component breakdown

| Component | Responsibility |
|---|---|
| `frontend/` | React SPA — UI only, all state on backend |
| `backend/main.py` | FastAPI app entry point, serves built frontend |
| `backend/routers/podcasts.py` | Search, subscribe, episode listing |
| `backend/routers/player.py` | Play trigger, audio streaming, transcript status |
| `backend/routers/gists.py` | Create, list, delete distillations |
| `backend/services/podcast_index.py` | iTunes Search API + optional Podcast Index |
| `backend/services/rss.py` | RSS feed parsing via feedparser |
| `backend/services/downloader.py` | Async MP3 download to `/media/` |
| `backend/services/transcriber.py` | faster-whisper, word-level timestamps, async background task |
| `backend/services/gist_engine.py` | Timestamp window lookup in transcript |
| `backend/database.py` | SQLite connection + schema init |

---

## Tech Stack

**Backend**
- [FastAPI](https://fastapi.tiangolo.com/) + [uvicorn](https://www.uvicorn.org/)
- [aiosqlite](https://aiosqlite.omnilib.dev/) — async SQLite
- [faster-whisper](https://github.com/SYSTRAN/faster-whisper) — local speech-to-text (CTranslate2 backend, int8 quantization)
- [feedparser](https://feedparser.readthedocs.io/) — RSS parsing
- [httpx](https://www.python-httpx.org/) — async HTTP client for downloads

**Frontend**
- [React 18](https://react.dev/) + [TypeScript](https://www.typescriptlang.org/)
- [Vite](https://vitejs.dev/) — build tool
- [Tailwind CSS v3](https://tailwindcss.com/) — styling
- [React Router v6](https://reactrouter.com/) — client-side routing

---

## How Transcription Works

When you press play on an episode:

1. FastAPI receives `POST /player/play` with the episode's audio URL
2. The episode MP3 is downloaded to `/media/` on the VPS (if not already cached)
3. A background asyncio task starts transcribing using `faster-whisper` with `word_timestamps=True`
4. The frontend polls `GET /player/transcript-status/{episode_id}` every 5 seconds
5. When status is `done`, the ⚗️ Distill button unlocks

Transcription runs on CPU. With the `base` model, a 1-hour podcast takes roughly 15-25 minutes — typically finishing before you're halfway through the episode.

### Model trade-offs

| Model | Speed (CPU) | Accuracy | Best for |
|---|---|---|---|
| `base` | Fastest | Good | English podcasts (default) |
| `small` | Fast | Better | Accents, multilingual |
| `medium` | Moderate | Very good | High accuracy |
| `large-v3` | Slow | Best | Maximum quality |

Set via `WHISPER_MODEL` in `.env`.

---

## How AI Works for Free

Most podcast apps with AI features hit an LLM API per request — typically $0.01 to $0.05 per call, which adds up fast.

DistillPod calls the `claude` CLI as a subprocess instead:

```python
result = subprocess.run(
    ["claude", "--print", prompt],
    capture_output=True, text=True
)
```

The CLI authenticates through your Claude Max subscription ($20/month flat, unlimited usage) — no API key, no per-call billing.

**Setup:** install the Claude CLI and log in once:

```bash
npm install -g @anthropic-ai/claude-code
claude login
```

That's it. Every distillation and recommendation is free on top of your existing subscription.

---

## Prerequisites

- Python 3.10+
- Node.js 18+
- `pip`
- A machine with a few GB of RAM (faster-whisper `base` model uses ~500MB)

For production: a Linux VPS (tested on Ubuntu 22.04).

---

## Installation

### 1. Clone

```bash
git clone https://github.com/andrepaim/distillpod.git
cd distillpod
```

### 2. Configure

```bash
cp .env.example .env
```

Edit `.env`:

```env
# Optional: Podcast Index for richer metadata (iTunes Search API needs no key)
PODCAST_INDEX_API_KEY=your_key_here
PODCAST_INDEX_API_SECRET=your_secret_here

# AI distillations use Claude CLI (claude --print) — no API key needed
# Requires: claude CLI installed + authenticated via `claude login`

# Whisper model: base | small | medium | large-v3
WHISPER_MODEL=base

# Distillation context window in seconds (default 60)
GIST_CONTEXT_SECONDS=60

# Media storage path
MEDIA_DIR=../media
```

### 3. Backend

```bash
cd backend
pip install -r requirements.txt
uvicorn main:app --host 127.0.0.1 --port 8124 --reload
```

### 4. Frontend (development)

```bash
cd frontend
npm install
npm run dev   # http://localhost:5173
```

### 5. Frontend (production build)

```bash
cd frontend
npm install
npm run build   # outputs to frontend/dist/
```

The FastAPI backend automatically serves `frontend/dist/` at `/` when it exists, so no separate web server is needed.

---

## Running in Production

### systemd service

Create `/etc/systemd/system/distillpod.service`:

```ini
[Unit]
Description=DistillPod — self-hosted podcast app
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/path/to/distillpod/backend
ExecStart=/usr/bin/python3 -m uvicorn main:app --host 127.0.0.1 --port 8124
Restart=always
RestartSec=5
EnvironmentFile=/path/to/distillpod/.env

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable distillpod
systemctl start distillpod
```

### Firewall

DistillPod binds to `127.0.0.1` by default — not accessible from the outside. Two options for remote access:

**Option A — SSH tunnel (most secure):**
```bash
ssh -L 8124:127.0.0.1:8124 user@your-vps
# Then open http://localhost:8124 on your local machine
```

**Option B — IP allowlist (convenient for mobile):**
```bash
ufw allow from YOUR.IP.ADDRESS to any port 8124 proto tcp
# Change backend to bind to 0.0.0.0 in the service ExecStart
```

---

## App Tour

### Home — Latest Episodes
Unified feed of the most recent episodes across all subscribed podcasts, newest first (up to 50). Each card shows cover art, podcast name, episode title, relative date, duration, and a distillation count badge if you have any.

### Search
Type a podcast name and press Search or Enter. Uses the iTunes Search API — no key needed. Subscribe with one tap.

When the search box is empty, a **🤖 Suggested for you** section appears with up to 4 daily AI recommendations. These are generated overnight by a background cron job (see [Podcast Recommendations](#podcast-recommendations)) and ranked by relevance to your subscriptions. Tap a card to subscribe, or dismiss suggestions you're not interested in — they won't reappear.

### Library
Your subscribed podcasts. Tap into any podcast for its episode list with transcript status badges (green = done, yellow = processing, gray = none). Refresh RSS or unsubscribe per podcast.

### Player
- Episode header with cover art and title
- Transcript status indicator (pulsing while transcribing, green when done)
- Native `<audio>` player with full browser controls (seek, speed, etc.)
- **⚗️ Distill button** — disabled until transcript is ready; tap at any moment to capture the last 60 seconds and get a Claude-powered quote and insight
- All distillations for this episode listed below the player

### Distillations
All your distillations, grouped by episode. Each group shows cover art, podcast and episode title, distillation count, date of last distillation, and a preview of the first one. Tap to see all with copy and delete per entry. ▶ jumps back to the player.

---

## Caching

### Backend (SQLite)
Episodes are stored after the first RSS fetch. Subsequent loads read from the DB — fast and offline-friendly. Use the ↻ button to force a fresh RSS fetch.

### Frontend (localStorage)
Home feed and episode lists are cached with a 30-minute TTL using stale-while-revalidate:

- **Cache hit** — data renders instantly, fresh fetch runs silently in background
- **Cache miss** — skeleton loaders shown, data rendered on arrival
- **Manual refresh** — ↻ button forces fresh data immediately

---

## API Reference

| Method | Path | Description |
|---|---|---|
| `GET` | `/podcasts/search?q=` | Search podcasts (iTunes API) |
| `GET` | `/podcasts/subscriptions` | List subscriptions |
| `POST` | `/podcasts/subscriptions/{id}?feed_url=&title=` | Subscribe |
| `DELETE` | `/podcasts/subscriptions/{id}` | Unsubscribe |
| `GET` | `/podcasts/{id}/episodes?refresh=` | List episodes |
| `POST` | `/player/play` | Trigger download + transcription |
| `GET` | `/player/audio/{episode_id}` | Stream MP3 (Range-request capable) |
| `GET` | `/player/transcript-status/{episode_id}` | Poll transcription progress |
| `GET` | `/podcasts/suggestions` | List undismissed podcast suggestions |
| `POST` | `/podcasts/suggestions/{id}/dismiss` | Dismiss a suggestion |
| `POST` | `/gists/` | Create an AI distillation at current playback position |
| `GET` | `/gists/?episode_id=` | List distillations (optionally filtered by episode) |
| `DELETE` | `/gists/{id}` | Delete a distillation |

---

## Storage

| Path | Contents |
|---|---|
| `distillpod.db` | SQLite database (subscriptions, episodes, transcripts, distillations, suggestions) |
| `media/` | Downloaded episode MP3s (named by MD5 of episode ID) |

Media files accumulate over time. Cleanup is currently manual — a future improvement would be an LRU cache with a configurable size limit.

---

## Testing

DistillPod has a backend test suite covering the API layer and key script logic.

### Backend tests (pytest)

```bash
cd /root/distillpod
python3 -m pytest tests/ -v
```

24 tests across two files:

| File | What it covers |
|---|---|
| `tests/test_api.py` | `GET /podcasts/feed` (distill counts, metadata, no description field), suggestions endpoints (list, dismiss, unknown ID), subscriptions list, auth middleware (browser → 302, API client → 401) |
| `tests/test_suggest_podcasts.py` | `claude()` subprocess wrapper (JSON output, markdown fence stripping, error handling), deduplication filtering (subscribed feeds excluded, dismissed suggestions excluded, missing feed URLs skipped) |

Tests run against an in-memory SQLite database seeded in `tests/conftest.py` — no production DB is touched. Auth is bypassed via a test session cookie injected by the `client` fixture.

### Dependencies

```bash
pip install pytest pytest-asyncio httpx
```

Already listed in `backend/requirements.txt`.

---

## Podcast Recommendations

DistillPod generates daily podcast suggestions tailored to your library, surfaced in the Search tab when no query is typed.

### How it works

A background script (`scripts/suggest-podcasts.py`) runs once a day via cron:

1. **Reads your context** — fetches your subscriptions and the last 8 episode titles per show from the SQLite DB
2. **First Claude Code CLI call — query generation** — passes the context to the Claude Code CLI and asks for 4 iTunes search queries, each targeting a different angle (safety, engineering, research, a wildcard). Zero API cost.
3. **Searches iTunes** — runs each query against the iTunes Search API and collects candidate shows
4. **Deduplicates** — filters out shows already subscribed, already suggested (including dismissed), or missing a feed URL
5. **Second Claude Code CLI call — reason writing** — passes the real show metadata (title, author, description) back to the Claude Code CLI and gets a ≤12 word personalised reason per pick. Again, zero API cost.
6. **Stores up to 4 suggestions** in the `suggestions` table

The frontend reads `GET /podcasts/suggestions` on Search mount and renders the results as interactive cards. Tapping a card subscribes immediately; tapping "Not interested" calls `POST /podcasts/suggestions/{id}/dismiss` and removes the card optimistically — dismissed suggestions are excluded from future runs.

### Running the script manually

```bash
cd /root/distillpod
python3 scripts/suggest-podcasts.py
```

### Scheduling

The script is designed to run as a daily cron job. Example (3 AM UTC):

```cron
0 3 * * * cd /root/distillpod && python3 scripts/suggest-podcasts.py >> logs/suggest.log 2>&1
```

Both Claude Code CLI calls go through the same `claude --print` subprocess used for distillations. No API key, no per-call billing.

---

## Future Ideas

- **Full-text search** across distillations and transcripts (SQLite FTS5)
- **Auto-sync** — poll RSS feeds on a schedule and transcribe new episodes automatically
- **Export** distillations to Markdown, Notion, or Obsidian
- **Speaker diarization** (pyannote.audio) — know who said what
- **Chapter navigation** from faster-whisper segments
- **Progressive transcription** — stream words as they come in

---

## License

MIT
