# DistillPod ⚗️

A minimal, self-hosted podcast app built around one idea: **distill any episode into what actually matters.**

No cloud services. No subscriptions. No per-use API costs. Your VPS does all the heavy lifting.

---

## What is this?

DistillPod is a mobile-first web app for listening to podcasts and capturing distillations from them.

**A distillation** is a moment you flag while listening. Tap the ⚗️ Distill button at any point and DistillPod extracts the last 60 seconds of transcript around that moment — instantly, with no API call. Toggle AI mode and Claude turns that excerpt into a verbatim quote and a 1-2 sentence insight instead.

The core insight: most podcast apps call an LLM API per clip (~$0.01 to $0.05 per call). DistillPod flips this — it **transcribes the whole episode once** using [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (free, runs locally on CPU), and each distillation becomes a near-instant timestamp lookup in the pre-computed word-level transcript. The AI step is free too — see [The OpenClaw Hack](#the-openclaw-hack-how-ai-works-for-free).

You open the app in your phone's browser. Everything else — downloading audio, transcribing, serving — happens on your VPS.

---

## What is a Distillation?

A distillation is the core unit of DistillPod. It has two modes:

### Basic distillation (instant, ~200ms)

Tap ⚗️ **Distill** at any moment while listening. The backend:
1. Reads the current playback position (in seconds)
2. Slices the pre-computed word-level transcript for the window `[now - 60s, now]`
3. Returns the raw transcript text immediately — no API call, no processing

The result is a verbatim excerpt of what was said in the last 60 seconds around your tap. Fast, free, offline-friendly.

### AI distillation (Claude, ~30s)

Toggle **✨ AI** in the player before tapping Distill. The backend:
1. Extracts the same 60-second transcript window
2. Calls `claude --print` as a subprocess with the excerpt and a structured prompt
3. Claude returns `{ "quote": "...", "insight": "..." }`:
   - **quote** — the single most memorable verbatim sentence from the excerpt
   - **insight** — 1-2 sentence takeaway capturing the core idea
4. The distillation card shows the quote (italic, styled) and insight — raw transcript is hidden

**Latency: ~30s.** Uses your Claude Max subscription via the OpenClaw hack — no extra API cost.

The 60-second context window is configurable via `GIST_CONTEXT_SECONDS` in `.env`.

---

## Features

- **📰 Home feed** — unified list of the latest episodes across all subscribed podcasts, sorted by date. Shows whether you've listened and how many distillations you have per episode.
- **🔍 Search** — find podcasts via the iTunes Search API (no key needed). Subscribe with one tap.
- **📚 Library** — browse your subscribed podcasts and their episodes. Transcript status shown per episode.
- **▶️ Player** — stream audio directly from your VPS. Transcription kicks off automatically in the background when you press play.
- **⚗️ Distill** — tap at any moment while listening. Instantly captures the last 60 seconds of transcript. Toggle AI mode for a Claude-powered quote and insight (~30s, zero extra cost).
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

## The OpenClaw Hack (How AI Works for Free)

Most podcast apps with AI features hit an LLM API per request — typically $0.01 to $0.05 per call, which adds up fast.

DistillPod does something different, directly inspired by how [OpenClaw](https://openclaw.ai) works internally.

OpenClaw is a personal AI assistant platform that runs on your VPS. Under the hood, it drives the `claude` CLI — authenticated against a Claude Max subscription ($20/month flat, unlimited usage). The insight: that CLI is just a binary sitting on your server, already logged in, callable by anything.

So DistillPod calls it as a subprocess:

```python
result = subprocess.run(
    ["claude", "--print", prompt],
    capture_output=True, text=True
)
```

No API key. No per-call billing. The AI cost is zero on top of what OpenClaw already pays for. The idea came directly from seeing how OpenClaw piggybacks on the Claude Max subscription — if it works for a full AI assistant, it works for a podcast distillation feature too.

This only works because:
1. You already have OpenClaw running on the same machine
2. The `claude` CLI is authenticated via your Claude Max account
3. DistillPod and OpenClaw share the same VPS process space

It is an unabashed hack. It is also completely free and takes 30 seconds to set up.

If you want to replicate this without OpenClaw, you can install the `claude` CLI directly (`npm install -g @anthropic-ai/claude-code`) and run `claude login` — same result.

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

### Library
Your subscribed podcasts. Tap into any podcast for its episode list with transcript status badges (green = done, yellow = processing, gray = none). Refresh RSS or unsubscribe per podcast.

### Player
- Episode header with cover art and title
- Transcript status indicator (pulsing while transcribing, green when done)
- Native `<audio>` player with full browser controls (seek, speed, etc.)
- **✨ AI toggle** — off by default; when on, distillations return a Claude-powered quote and insight instead of raw transcript
- **⚗️ Distill button** — disabled until transcript is ready; tap at any moment to capture the last 60 seconds
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
| `POST` | `/gists/?summary=` | Create a distillation at current playback position |
| `GET` | `/gists/?episode_id=` | List distillations (optionally filtered by episode) |
| `DELETE` | `/gists/{id}` | Delete a distillation |

---

## Storage

| Path | Contents |
|---|---|
| `distillpod.db` | SQLite database (subscriptions, episodes, transcripts, distillations) |
| `media/` | Downloaded episode MP3s (named by MD5 of episode ID) |

Media files accumulate over time. Cleanup is currently manual — a future improvement would be an LRU cache with a configurable size limit.

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
