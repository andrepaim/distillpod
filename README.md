# PodSnip 🎙✂️

A minimal, self-hosted podcast app with instant transcript snipping. No cloud services, no subscriptions, no per-use API costs. Your VPS does all the heavy lifting.

---

## What is this?

PodSnip is a mobile-first web app that lets you listen to podcasts and **capture moments** from them — a feature popularized by [Snipd](https://www.snipd.com/), but self-hosted and free.

The core insight: Snipd calls the Whisper API for each clip (~$0.01/snip + latency). PodSnip flips this — it **transcribes the whole episode once** using [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (free, runs locally on CPU), and each snip becomes a zero-cost, near-instant timestamp lookup in the pre-computed word-level transcript.

You open the app in your phone's browser. Everything else — downloading audio, transcribing, serving — happens on your VPS.

---

## Features

- **📰 Home feed** — unified list of the latest episodes across all your subscribed podcasts, sorted by date. Shows whether you've listened and whether you have snips.
- **🔍 Search** — find podcasts via the iTunes Search API (no key needed). Subscribe with one tap.
- **📚 Library** — browse your subscribed podcasts and their episodes. Transcript status shown per episode.
- **▶️ Player** — stream audio directly from your VPS. Transcription kicks off automatically in the background when you press play.
- **✂️ Snip** — tap the Snip button at any moment while listening. Instantly extracts the last 60 seconds of transcript. No spinner, no wait.
- **📋 Snips library** — browse all your snips grouped by episode. Copy to clipboard, delete, or jump back to the episode.
- **⚡ Stale-while-revalidate caching** — the app feels instant on return visits. Data is cached in localStorage with a 30-minute TTL and refreshed silently in the background.

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  Browser (React + Vite + Tailwind CSS)               │
│                                                       │
│  Home · Search · Library · Player · Snips            │
│                                                       │
│  localStorage cache (stale-while-revalidate, 30min)  │
└───────────────────────┬─────────────────────────────┘
                        │ HTTP (SSH tunnel or direct IP)
┌───────────────────────▼─────────────────────────────┐
│  FastAPI backend (port 8124)                         │
│                                                       │
│  /podcasts   /player   /snips                        │
│                                                       │
│  ┌──────────────────────────────────────────────┐   │
│  │  Services                                     │   │
│  │  podcast_index · rss · downloader             │   │
│  │  transcriber · snip_engine                    │   │
│  └──────────────────────────────────────────────┘   │
│                                                       │
│  SQLite (aiosqlite) — subscriptions, episodes,       │
│                        transcripts, snips            │
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
| `backend/routers/snips.py` | Create, list, delete snips |
| `backend/services/podcast_index.py` | iTunes Search API + optional Podcast Index |
| `backend/services/rss.py` | RSS feed parsing via feedparser |
| `backend/services/downloader.py` | Async MP3 download to `/media/` |
| `backend/services/transcriber.py` | faster-whisper, word-level timestamps, async background task |
| `backend/services/snip_engine.py` | Timestamp window lookup in transcript |
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
5. When status is `done`, the ✂️ Snip button unlocks

Transcription runs on CPU. With the `base` model, a 1-hour podcast takes roughly 15–25 minutes — typically finishing before you're halfway through the episode.

### Model trade-offs

| Model | Speed (CPU) | Accuracy | Best for |
|---|---|---|---|
| `base` | Fastest | Good | English podcasts (default) |
| `small` | Fast | Better | Accents, multilingual |
| `medium` | Moderate | Very good | High accuracy |
| `large-v3` | Slow | Best | Maximum quality |

Set via `WHISPER_MODEL` in `.env`.

---

## How Snipping Works

When you tap ✂️ Snip:

1. The frontend reads `audioRef.current.currentTime` (current playback position in seconds)
2. Sends `POST /snips/ { episode_id, current_seconds }`
3. The backend scans the pre-computed word array for words in the window `[current_seconds - 60, current_seconds]`
4. Returns the extracted text immediately (no API call, no processing — just an array slice)
5. A new snip card appears at the top of the list

**Total latency: < 200ms.** No spinner needed.

The 60-second context window is configurable via `SNIP_CONTEXT_SECONDS` in `.env`.

Optionally, pass `?summary=true` to get a Claude summary of the snip. Uses `claude --print` as a subprocess — no API key required, routes through your existing Claude Max subscription session (`claude login`).

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
git clone https://github.com/andrepaim/podsnip.git
cd podsnip
```

### 2. Configure

```bash
cp .env.example .env
```

Edit `.env`:

```env
# Required — iTunes Search API needs no key.
# Optional: Podcast Index for richer metadata
PODCAST_INDEX_API_KEY=your_key_here
PODCAST_INDEX_API_SECRET=your_secret_here

# Summaries use Claude CLI (claude --print) — no API key needed
# Requires: claude CLI installed + authenticated via `claude login`

# Whisper model: base | small | medium | large-v3
WHISPER_MODEL=base

# Snip context window in seconds (default 60)
SNIP_CONTEXT_SECONDS=60

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

### 4. Frontend (production build)

```bash
cd frontend
npm install
npm run build   # outputs to frontend/dist/
```

The FastAPI backend automatically serves `frontend/dist/` at `/` when it exists, so no separate web server is needed.

---

## Running in Production

### systemd service

Create `/etc/systemd/system/podsnip.service`:

```ini
[Unit]
Description=PodSnip — self-hosted podcast app
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/path/to/podsnip/backend
ExecStart=/usr/bin/python3 -m uvicorn main:app --host 127.0.0.1 --port 8124
Restart=always
RestartSec=5
EnvironmentFile=/path/to/podsnip/.env

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable podsnip
systemctl start podsnip
```

### Firewall

PodSnip binds to `127.0.0.1` by default — not accessible from the outside. There are two ways to access it remotely:

**Option A — SSH tunnel (most secure):**
```bash
ssh -L 8124:127.0.0.1:8124 user@your-vps
# Then open http://localhost:8124 on your local machine
```

**Option B — IP allowlist (convenient for mobile):**
```bash
# Allow only your IP
ufw allow from YOUR.IP.ADDRESS to any port 8124 proto tcp
# Change backend to bind to 0.0.0.0
# ExecStart: uvicorn main:app --host 0.0.0.0 --port 8124
```

Then open `http://your-vps-ip:8124` directly on your phone.

---

## App Tour

### Home — Latest Episodes
The default screen. Shows a unified feed of the most recent episodes across all your subscribed podcasts, newest first (up to 50).

Each card shows:
- Podcast cover art
- Podcast name + episode title
- Relative date ("Today", "Yesterday", "3d ago") + duration
- ✂️ N badge if you have snips for that episode
- Circle button to mark as played/unplayed (stored in localStorage)

Tap any episode to open the Player.

### Search
Type a podcast name and press Search or Enter. Uses the iTunes Search API — no API key needed.

Results show cover art, title, author, and description. The Subscribe button turns green and stays there after subscribing (pre-checks your existing subscriptions on load).

### Library
Your subscribed podcasts. Tap a podcast to drill into its episode list.

In the episode list:
- Episodes sorted newest first
- Transcript status badge per episode (green = done, yellow = processing, gray = none)
- ↻ refresh button to pull fresh episodes from RSS
- 🗑 trash icon to unsubscribe

### Player
- Episode cover art + title header
- Animated transcript status badge (pulsing while transcribing, green checkmark when done)
- Native `<audio>` element with full browser controls (seek, speed, etc.)
- ✂️ Snip button — disabled until transcript is ready, flashes green when a snip is created
- All snips for this episode listed below

### Snips
All your snips, grouped by episode. Each episode group shows:
- Podcast cover art
- Podcast name + episode title
- Number of snips (indigo pill)
- Date of last snip
- Preview of the first snip text

Tap an episode to see all its snips with copy-to-clipboard and delete per snip. ▶ Play button jumps back to the Player.

---

## Caching

### Backend (SQLite)
Episodes are stored in SQLite after the first RSS fetch. Subsequent loads (`refresh=false`) read from the DB — fast and offline-friendly. Use the ↻ button to force a fresh RSS fetch.

### Frontend (localStorage)
The Home feed and episode lists are cached in localStorage with a 30-minute TTL using a stale-while-revalidate strategy:

- **Cache hit**: data renders instantly, fresh fetch runs in background silently
- **Cache miss**: skeleton loaders shown, then data rendered on arrival
- **Manual refresh**: ↻ button in both Home and Library to force fresh data immediately

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
| `POST` | `/snips/?summary=` | Create a snip at current playback position |
| `GET` | `/snips/?episode_id=` | List snips (optionally filtered) |
| `DELETE` | `/snips/{snip_id}` | Delete a snip |

---

## Storage

| Path | Contents |
|---|---|
| `podsnip.db` | SQLite database (subscriptions, episodes, transcripts, snips) |
| `media/` | Downloaded episode MP3s (named by MD5 of episode ID) |

Media files accumulate over time. For MVP, cleanup is manual. A future improvement would be an LRU cache with a configurable size limit.

---

## Future Ideas

- **Full-text search** in transcripts (SQLite FTS5)
- **Auto-discovery** of new episodes via cron (poll RSS feeds periodically)
- **Export snips** to Markdown / Notion / Obsidian
- **Speaker diarization** (pyannote.audio) — know who said what
- **Chapter navigation** from faster-whisper segments
- **Progressive transcription** — stream words as they come
- **PWA / offline support** — manifest.json + service worker
- **Snip sharing** — image cards for social sharing

---

## License

MIT
