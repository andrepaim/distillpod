# DistillPod — Architecture Reference

> **Last updated:** 2026-03-07  
> **URL:** https://distillpod.duckdns.org  
> **Service:** `distillpod.service` (systemd)  
> **Root:** `/root/distillpod/`

---

## 1. High-Level Overview

DistillPod is a self-hosted podcast client with AI-powered features: on-device transcription, AI distillations ("gists"), ad detection, chapter generation, episode chat, and deep research reports.

```
┌─────────────────────────────────────────────────────────────┐
│                         User (browser)                       │
│              https://distillpod.duckdns.org                  │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTPS (Apache reverse proxy)
┌──────────────────────────▼──────────────────────────────────┐
│              FastAPI app  — port 8124 (localhost)            │
│                                                             │
│  ┌────────────────┐  ┌───────────────────────────────────┐  │
│  │  Static files  │  │           API Routers             │  │
│  │  (React SPA)   │  │  /auth  /podcasts  /player        │  │
│  │  /assets/**    │  │  /gists  /chat  /research         │  │
│  └────────────────┘  └──────────────┬────────────────────┘  │
└─────────────────────────────────────┼───────────────────────┘
                                      │
              ┌───────────────────────┼──────────────────────┐
              │                       │                       │
   ┌──────────▼──────┐   ┌───────────▼──────┐   ┌──────────▼──────┐
   │   SQLite DB      │   │   Media files     │   │  Claude CLI     │
   │ distillpod.db    │   │  /media/*.mp3     │   │ claude --print  │
   └─────────────────┘   └──────────────────┘   └────────────────┘
```

---

## 2. Deployment & Infrastructure

| Component | Value |
|---|---|
| **Server** | VPS, Germany (Europe/Berlin) |
| **OS** | Ubuntu 22.04.5 LTS |
| **Port** | `127.0.0.1:8124` (internal only) |
| **Reverse proxy** | Apache (HTTPS termination, forwards to 8124) |
| **Domain** | `distillpod.duckdns.org` |
| **Process manager** | systemd (`distillpod.service`) |
| **Python runtime** | `/root/distillpod/backend/` (uvicorn) |
| **Frontend dist** | `/root/distillpod/frontend/dist/` |
| **Media storage** | `/root/distillpod/media/` |
| **Database** | `/root/distillpod/distillpod.db` (SQLite) |
| **Reports** | `/root/distillpod/reports/*.html` |

### Environment variables (`backend/.env`)

| Variable | Purpose |
|---|---|
| `PODCAST_INDEX_API_KEY` / `PODCAST_INDEX_SECRET` | PodcastIndex search API |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google OAuth |
| `ALLOWED_EMAILS` | Comma-separated allowlist (`<your-email@gmail.com>`) |
| `SESSION_SECRET` | HMAC key for session cookies |
| `WHISPER_MODEL` | `medium` (faster-whisper model size) |
| `WHISPER_DEVICE` | `cpu` |
| `TELEGRAM_BOT_TOKEN` | Tia do Zap bot token (notifications) |
| `TELEGRAM_CHAT_ID` | `<your-telegram-chat-id>` |
| `TEST_MODE` | `true` bypasses Google OAuth for Playwright E2E |

---

## 3. Backend — FastAPI Application

**Entry point:** `backend/main.py`

### Middleware stack (LIFO order)
1. `CORSMiddleware` — allows frontend origin + duckdns domain
2. `AuthMiddleware` — validates `distillpod_session` cookie on every request; exempts `/auth/*`, `/health`, `/assets/*`, and static files

### Routers

#### `/auth` — Authentication (`routers/auth.py`)
Google OAuth2 flow (PKCE-less, server-side):

```
GET  /auth/google           → redirect to Google consent screen
GET  /auth/google/callback  → exchange code → validate email → set cookie
GET  /auth/me               → return current user from session cookie
POST /auth/logout           → delete session cookie
POST /auth/test-session     → TEST_MODE only — bypasses Google for E2E tests
```

**Session cookie:** `distillpod_session` — HS256-signed JWT, 30-day lifetime, `httponly + secure + samesite=lax`.  
**Email allowlist:** only `<your-email@gmail.com>` can log in (configurable via env).

---

#### `/podcasts` — Discovery & Subscriptions (`routers/podcasts.py`)

```
GET    /podcasts/search?q=...         → PodcastIndex API search
GET    /podcasts/subscriptions        → list subscribed podcasts
POST   /podcasts/subscriptions/{id}   → subscribe
DELETE /podcasts/subscriptions/{id}   → unsubscribe
GET    /podcasts/suggestions          → AI-generated discovery suggestions (undismissed)
POST   /podcasts/suggestions/{id}/dismiss → mark suggestion dismissed
GET    /podcasts/feed                 → combined home feed (50 episodes, with distill counts)
GET    /podcasts/{podcast_id}/episodes?refresh=true → episodes list, optional RSS refresh
```

**Home feed query** joins `episodes + subscriptions + gists` in a single SQL query, returns title, audio URL, duration, transcript status, podcast image, and distill count. `description` is deliberately excluded (large, not needed in list view).

---

#### `/player` — Audio & Transcription (`routers/player.py`)

```
POST /player/play                        → trigger download + background transcription
GET  /player/audio/{episode_id}          → stream original mp3
GET  /player/audio-adfree/{episode_id}   → stream ad-free mp3 (if available)
GET  /player/episode/{episode_id}        → fetch single episode metadata
GET  /player/transcript-status/{id}      → poll transcription progress
GET  /player/adfree-status/{id}          → check if ad-free version exists
GET  /player/chapters/{id}               → chapters + episode summary
```

**`POST /player/play` flow:**
1. Check DB — is episode already downloaded?
2. If not: call `download_episode()` (streaming HTTP download)
3. If `transcript_status` is not `done` or `processing`, spawn background task → `transcribe_episode()`
4. Return immediately with `audio_url` pointing to `/player/audio/{id}`

**Concurrency guard:** `_transcribing: set[str]` in-memory set prevents duplicate transcription tasks for the same episode.

---

#### `/gists` — AI Distillations (`routers/gists.py`)

```
POST   /gists/            → create a gist at current playback position
GET    /gists/?episode_id=  → list gists (all or per-episode)
DELETE /gists/{shot_id}   → delete a gist
```

Creating a gist requires `transcript_status == done`. Delegates to `snip_engine.create_gist()`.

---

#### `/chat` — Episode Q&A (`routers/chat.py`)

```
GET    /chat/{episode_id}         → fetch conversation history
POST   /chat/{episode_id}/init    → first-time AI summary + invitation to ask questions
POST   /chat/{episode_id}/message → send a user message, get AI reply
```

Each message calls Claude via `claude --print` subprocess. History is kept in the `episode_chats` table (last 10 turns passed as context). Full transcript is included in every prompt — high token cost but ensures accurate answers.

---

#### `/research` — Deep Research Reports (`routers/research.py`)

```
POST /research/{gist_id}  → trigger background research job
GET  /research/{gist_id}  → poll status + get report URL
```

Research runs as a multi-turn Claude pipeline (see §5). Generates an HTML report saved to `/root/distillpod/reports/{id}.html`, served at `/reports/{filename}`.

---

## 4. Database Schema (SQLite)

**File:** `/root/distillpod/distillpod.db`

### `subscriptions`
| Column | Type | Description |
|---|---|---|
| `podcast_id` | TEXT PK | PodcastIndex feed ID |
| `feed_url` | TEXT | RSS feed URL |
| `title` | TEXT | Podcast name |
| `image_url` | TEXT | Artwork |
| `last_checked` | TEXT | ISO timestamp of last RSS refresh |
| `subscribed_at` | TEXT | ISO timestamp |

### `episodes`
| Column | Type | Description |
|---|---|---|
| `id` | TEXT PK | GUID from RSS |
| `podcast_id` | TEXT FK | → subscriptions |
| `title` | TEXT | |
| `audio_url` | TEXT | Original RSS audio URL |
| `duration_seconds` | INTEGER | |
| `published_at` | TEXT | ISO timestamp |
| `image_url` | TEXT | Episode artwork (falls back to podcast artwork in UI) |
| `downloaded` | INTEGER | 0/1 |
| `local_path` | TEXT | Absolute path to downloaded mp3 |
| `transcript_status` | TEXT | `none` / `queued` / `processing` / `done` / `error` |
| `adfree_path` | TEXT | Absolute path to ad-free mp3 (null if none) |
| `ads_detected` | INTEGER | Count of ad segments found (null = not run yet) |
| `summary` | TEXT | AI-generated episode summary (from chapterizer) |
| `chapters_status` | TEXT | `none` / `processing` / `done` / `error` |

### `transcripts`
| Column | Type | Description |
|---|---|---|
| `episode_id` | TEXT PK | |
| `words_json` | TEXT | `[{word, start, end}, ...]` — word-level timestamps |
| `language` | TEXT | `"auto"` (faster-whisper detects) |
| `created_at` | TEXT | ISO timestamp |

### `gists`
| Column | Type | Description |
|---|---|---|
| `id` | TEXT PK | UUID |
| `episode_id` | TEXT | |
| `podcast_id` | TEXT | |
| `episode_title` | TEXT | Denormalized for display |
| `podcast_title` | TEXT | Denormalized |
| `start_seconds` | REAL | Window start (current_pos − 60s) |
| `end_seconds` | REAL | Window end (current_pos) |
| `text` | TEXT | Verbatim transcript text |
| `summary` | TEXT | JSON `{quote, insight}` from Claude |
| `created_at` | TEXT | |

### `episode_chats`
| Column | Type | Description |
|---|---|---|
| `id` | TEXT PK | UUID |
| `episode_id` | TEXT | |
| `role` | TEXT | `user` / `assistant` |
| `content` | TEXT | Message body |
| `created_at` | TEXT | |

### `chapters`
| Column | Type | Description |
|---|---|---|
| `id` | TEXT PK | UUID |
| `episode_id` | TEXT FK | |
| `title` | TEXT | Chapter name |
| `start_time` | REAL | Seconds from episode start (original audio timestamps) |

### `suggestions`
| Column | Type | Description |
|---|---|---|
| `id` | TEXT PK | UUID |
| `podcast_index_id` | TEXT | PodcastIndex ID if found |
| `title`, `author`, `description`, `image_url`, `feed_url` | TEXT | Metadata |
| `reason` | TEXT | Why Claude suggested it |
| `suggested_at` | TEXT | |
| `dismissed` | INTEGER | 0/1 |

### `researches`
| Column | Type | Description |
|---|---|---|
| `id` | TEXT PK | UUID |
| `gist_id` | TEXT | Source gist |
| `episode_id` | TEXT | |
| `status` | TEXT | `pending` / `running` / `done` / `error` |
| `file_path` | TEXT | Local path to HTML report |
| `public_url` | TEXT | `https://distillpod.duckdns.org/reports/{id}.html` |
| `error` | TEXT | Error message if failed |
| `created_at` / `finished_at` | TEXT | |

---

## 5. Core Services

### 5.1 Downloader (`services/downloader.py`)

Downloads episode audio via streaming HTTP (`httpx`).  
**Filename strategy:** `MD5(episode_id)` as filename, preserving original extension (`.mp3`).  
File stored under `/root/distillpod/media/`.  
Idempotent — skips if file already exists.

### 5.2 Transcriber (`services/transcriber.py`)

Uses **faster-whisper** (OpenAI Whisper, CTranslate2 backend) running on CPU with `int8` quantization.  
Model lazy-loaded on first use and cached globally (`_model` singleton).  
Configured: `medium` model (reasonable accuracy/speed tradeoff on CPU).

**Async flow:**
```
POST /player/play
  └→ asyncio.create_task(_bg_transcribe)
       └→ loop.run_in_executor(None, _transcribe_sync, audio_path)
            └→ WhisperModel.transcribe(word_timestamps=True)
            └→ Save words_json to DB
            └→ Mark transcript_status = 'done'
            └→ [Non-fatal] ad_detector.detect_ads()
            └→ [Non-fatal] ad_detector.remove_ads_from_audio()
```

**Word format:** `{word: str, start: float, end: float}` — seconds from episode start.

### 5.3 Snip Engine (`services/snip_engine.py`) — Gist creation

1. Reads word-level transcript from DB
2. Filters words in window `[current_pos − 60s, current_pos]`
3. Joins words into plain text
4. Calls Claude subprocess: `claude --print` with prompt requesting `{quote, insight}` JSON
5. Returns `Gist` object

**Claude prompt structure:** Extract the most quotable sentence + 1–2 sentence insight. Responds with raw JSON.

### 5.4 Ad Detector (`services/ad_detector.py`)

**Step 1 — Segmentation:** Groups transcript words into ~30s chunks with `[MM:SS–MM:SS]` timestamps.

**Step 2 — Claude classification:** Sends full segmented transcript to Claude asking for ad segments. Returns `[{start, end, reason}]` or `[]`.

**Step 3 — Validation:** Filters segments < 5s, clamps to episode duration.

**Step 4 — Audio surgery (`remove_ads_from_audio`):**
- Calculates inverse (keep) segments (1s buffers around each ad)
- Uses `ffmpeg` to cut keep segments individually into `/tmp/`
- Concatenates with `ffmpeg -f concat`
- Saves `{episode_id}_adfree.mp3` to media dir

**Non-fatal design:** entire ad detection + removal is wrapped in `try/except pass` — transcript result is never blocked by this.

### 5.5 Chapterizer (`services/chapterizer.py`)

1. Groups words into ~120s dense segments (vs 30s for ad detector)
2. Truncates to 12,000 chars max, sampled evenly across the episode
3. Claude prompt asks for 4–10 chapters with start times + 2–3 sentence summary
4. Validates: first chapter forced to `start_time=0.0`, sorted, deduped
5. Returns `{summary: str, chapters: [{title, start_time}]}`

**Note:** Chapter timestamps are always relative to the **original audio**, not the ad-free cut. The player UI is responsible for adjusting playback position when showing chapters in ad-free mode.

### 5.6 Researcher (`services/researcher.py`)

Multi-turn research pipeline triggered from a gist. Runs synchronously in a thread pool.

**Pipeline:**
1. **Topic extraction** — Claude extracts 3–5 search queries from the gist text + summary
2. **Tavily search** — 3 searches via Tavily API, collects URLs + snippets
3. **Synthesis** — Claude synthesizes findings into structured research (definitions, context, implications, further reading)
4. **HTML report generation** — Markdown → HTML with inline CSS, saved to `/root/distillpod/reports/{id}.html`
5. **Telegram notify** — sends report URL to `TG_CHAT_ID` if configured
6. **DB update** — sets `status=done`, `public_url`, `finished_at`

Uses `Tavily API` key + `claude --print` for all AI steps.

### 5.7 Podcast Index (`services/podcast_index.py`)

Wraps the [PodcastIndex API](https://podcastindex.org/developer) with HMAC-SHA1 auth (key + secret + timestamp). Used for search only.

### 5.8 RSS Parser (`services/rss.py`)

Fetches and parses podcast RSS feeds. Returns `list[Episode]` with GUID-based IDs. Used by both the API (`/podcasts/{id}/episodes?refresh=true`) and the daily sync script.

---

## 6. Frontend — React SPA

**Stack:** Vite + React + TypeScript + Tailwind CSS  
**Build output:** `/root/distillpod/frontend/dist/` (served by FastAPI)  
**Auth:** handled via `distillpod_session` cookie (set by backend OAuth flow)

### Pages

| Route | Component | Description |
|---|---|---|
| `/` | `Home.tsx` | Episode feed (50 most recent from subscriptions) |
| `/search` | `Search.tsx` | Podcast search via PodcastIndex |
| `/subscriptions` | `Subscriptions.tsx` | Subscribed podcasts list |
| `/subscriptions/:podcastId` | `Subscriptions.tsx` (PodcastEpisodes) | Episodes for a specific podcast |
| `/player/:episodeId` | `Player.tsx` | Episode Info Page (hero, action row, distillations, chat) |
| `/gists` | `Gists.tsx` | All-time distillations library |
| `/queue` | `Queue.tsx` | Playback queue |
| `/login` | `Login.tsx` | Google OAuth entry point |

### App-Shell Components (always rendered)

- **`MiniPlayer`** — fixed bottom bar. Tap → opens FullscreenPlayer (does NOT navigate)
- **`FullscreenPlayer`** — slide-up sheet (Spotify-style). Self-contained: fetches its own gists/transcript/chapters/ad-free status by watching `episode.id` from AudioContext. Swipe-down or browser back gesture closes it.

### State Management

**`AudioContext`** (`context/AudioContext.tsx`) — global audio state:
- `episode` — currently loaded episode
- `isPlaying`, `currentTime`, `duration`
- `playerExpanded` / `setPlayerExpanded` — controls FullscreenPlayer visibility
- Audio element management (play/pause/seek/progress)

**`queueStore`** (Zustand) — playback queue: `[Episode]`, `currentIndex`, `addToQueue`, `playNext`, `skipTo`.

**`cache.ts`** — in-memory TTL cache for API responses (feed, episode metadata). Reduces redundant fetches when navigating between pages.

### API Client (`api/client.ts`)

Thin fetch wrapper around the FastAPI backend. All requests go to relative paths (same origin). Auth is cookie-based — no bearer tokens in client code.

Key calls:
- `fetchFeed()` → `GET /podcasts/feed`
- `play(episodeId, audioUrl)` → `POST /player/play`
- `pollTranscriptStatus(episodeId)` → `GET /player/transcript-status/{id}`
- `createGist(episodeId, currentSeconds)` → `POST /gists/`
- `fetchChapters(episodeId)` → `GET /player/chapters/{id}`
- `fetchAdFreeStatus(episodeId)` → `GET /player/adfree-status/{id}`

### Back Gesture / History API

FullscreenPlayer uses the History API to integrate with browser/OS back gesture:
- On open: `history.pushState({ distillpodPlayer: true }, '')`
- `popstate` listener: closes player when back is triggered
- On manual close: checks `window.history.state?.distillpodPlayer` → calls `history.back()` to clean up sentinel entry

---

## 7. Authentication

**Mechanism:** Server-side Google OAuth2, cookie-based session.

**Flow:**
```
User → GET /auth/google
  → redirect to Google consent
  → Google callback → POST /auth/google/callback?code=...
  → exchange code for token → fetch userinfo
  → validate email against ALLOWED_EMAILS
  → create HS256 JWT (30d expiry) → set as httponly cookie
  → redirect to /
```

**AuthMiddleware** (`middleware/auth.py`):
- Checks every request for `distillpod_session` cookie
- Verifies JWT signature + expiry
- Attaches user info to `request.state.user`
- Exempt paths: `/auth/*`, `/health`, `/assets/*`, `/`, SPA routes

**Test mode** (`TEST_MODE=true`):
- `POST /auth/test-session` creates a session cookie without Google
- Used exclusively by Playwright E2E global setup
- Returns 404 in production

---

## 8. Scheduled Jobs (Crons)

All crons run as isolated OpenClaw sessions, deliver output to **Tia do Zap** (`telegram:notifications`, chat ID `<your-telegram-chat-id>`).

### 8.1 `distillpod-daily-sync` — 03:00 BRT daily

**Script:** `scripts/daily-sync.py`  
**Timeout:** 3600s (1 hour)

Full pipeline per subscription:
1. **Stale reset** — any episode stuck in `processing` gets reset to `none` + partial file deleted
2. **RSS fetch** — latest 5 episodes per subscription
3. **New episode insert** — `INSERT OR IGNORE` into `episodes`
4. **Download** — streaming HTTP download for recent episodes (≤48h old)
5. **Transcription** — faster-whisper in thread pool, word-level timestamps saved
6. **Ad detection** — Claude classifies transcript → ffmpeg cuts ad-free version
7. **Chapterization** — Claude generates chapters + episode summary
8. **Error report** — Telegram alert if any episodes ended in `error` state
9. **`last_checked` update** on subscription

**Recency gate:** Only episodes published within 48 hours get downloaded+transcribed. Prevents transcribing entire historical backlogs when first subscribing.

### 8.2 `distillpod-suggest` — 09:00 BRT daily

**Script:** `scripts/suggest-podcasts.py`  
**Timeout:** 180s

1. Reads all subscriptions + recent episode titles from DB
2. Calls Claude Haiku (`claude-haiku-4-5`) to generate 4 search queries based on listening habits
3. Searches iTunes API for each query
4. Filters out already-subscribed + already-suggested podcasts
5. Claude ranks and selects top 4
6. Inserts into `suggestions` table
7. Announces results via cron deliver (Tia do Zap)

**Model:** Uses Anthropic Python SDK (`anthropic.Anthropic`) directly (not Claude CLI subprocess). Key hardcoded in script — should be moved to env.

---

## 9. AI Usage Summary

| Feature | Method | Model | Trigger |
|---|---|---|---|
| Gist summary | `claude --print` subprocess | Claude (default, CLI) | User taps "Distill this moment" |
| Episode chat | `claude --print` subprocess | Claude (default, CLI) | User opens chat or sends message |
| Ad detection | `claude --print` subprocess | Claude (default, CLI) | After transcription completes |
| Chapterization | `claude --print` subprocess | Claude (default, CLI) | Daily sync or on-demand |
| Research report | `claude --print` subprocess | Claude (default, CLI) | User triggers from gist |
| Podcast suggestions | Anthropic Python SDK | `claude-haiku-4-5` | Daily cron 09:00 BRT |

All Claude CLI calls run in a thread pool (`asyncio.to_thread` or `loop.run_in_executor`) to avoid blocking the async FastAPI event loop.

Claude is authenticated via `claude login` on the VPS (Max subscription — no per-call cost).  
Anthropic SDK calls (suggest-podcasts) use a hardcoded API key and consume paid tokens.

---

## 10. E2E Testing

**Framework:** Playwright  
**Config:** `frontend/playwright.config.ts`  
**Viewport:** 390×844 (iPhone 15 / mobile)  
**Base URL:** `http://localhost:8124`  
**Auth state:** `frontend/e2e/.auth/user.json` (cookie persisted by global setup)

**Global setup** (`e2e/global.setup.ts`):  
Calls `POST /auth/test-session` (requires `TEST_MODE=true`) → saves session cookie to storage state.

**Test files:**
| File | Scope |
|---|---|
| `01-navigation.spec.ts` | Tab routing, bottom nav active states |
| `02-home.spec.ts` | Feed display, subscription cards |
| `03-search.spec.ts` | Podcast search, subscribe flow |
| `04-library.spec.ts` | Subscriptions page |
| `05-player.spec.ts` | Episode page, fullscreen player, gists, chapters, ad-free, chat |
| `06-gists.spec.ts` | Gists library page |
| `07-caching.spec.ts` | TTL cache behavior |
| `08-spa-routing.spec.ts` | Direct URL access, 404 fallback to SPA |

**Test episodes (hardcoded):**
- `EPISODE_DONE_ID` — has transcript, used for player/gist tests
- `EPISODE_PROC_ID` — transcript in progress, used for "Transcribing…" state tests

**Run:** `cd /root/distillpod/frontend && npx playwright test`

---

## 11. Known State & Technical Debt

| Issue | Status |
|---|---|
| `TEST_MODE=true` left on in prod `.env` | Harmless but should be removed after E2E tests pass |
| Anthropic API key hardcoded in `suggest-podcasts.py` | Should move to `.env` |
| Chapterizer not run on-demand from UI (only daily sync) | User must wait for next sync or manual script run |
| No retry logic for failed downloads | Episodes in `error` state require manual intervention |
| `episode_chats` table has no size limit | Long conversations accumulate indefinitely |
| Auth is single-user (email allowlist, no multi-tenant) | Intentional for personal use |
| Researcher Telegram notify uses env var `TG_BOT_TOKEN` | Same Tia do Zap token set in `.env` |
| SQLite has no WAL mode set | Concurrent writes during sync + API could theoretically deadlock |

---

## 12. Data Flow Diagram — Episode Lifecycle

```
RSS Feed
  │
  ▼
[daily-sync or API refresh]
  │
  ├─→ episodes table (transcript_status='none')
  │
  ▼
POST /player/play  (user taps Play)
  │
  ├─→ download_episode() → media/{md5}.mp3
  │
  ├─→ UPDATE episodes SET downloaded=1, local_path=...
  │
  └─→ background task: transcribe_episode()
        │
        ├─→ WhisperModel.transcribe() [CPU, ~1–5 min]
        │
        ├─→ INSERT transcripts (words_json)
        │
        ├─→ UPDATE episodes SET transcript_status='done'
        │
        └─→ [non-fatal] ad_detector.detect_ads()
              │
              ├─→ Claude: classify segments as ads
              │
              ├─→ ffmpeg: cut + concat ad-free audio
              │
              └─→ UPDATE episodes SET adfree_path, ads_detected

[daily-sync only]
  └─→ chapterizer.chapterize()
        │
        ├─→ Claude: identify chapters + summary
        │
        ├─→ INSERT chapters (4–10 rows)
        │
        └─→ UPDATE episodes SET summary, chapters_status='done'

User taps "Distill this moment"
  └─→ POST /gists/
        │
        ├─→ slice transcript [pos-60s, pos]
        │
        ├─→ Claude: extract {quote, insight}
        │
        └─→ INSERT gists

User opens Chat
  └─→ POST /chat/{id}/init or /message
        │
        └─→ Claude: answer using full transcript context

User triggers Research
  └─→ POST /research/{gist_id}
        │
        ├─→ Claude: generate search queries
        ├─→ Tavily: 3 web searches
        ├─→ Claude: synthesize findings
        ├─→ Generate HTML report
        └─→ Telegram notify with URL
```
