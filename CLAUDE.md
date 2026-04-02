# DistillPod

AI-powered podcast player with transcription, AI chat, gist extraction, ad removal, and deep-research reports.

## Tech Stack

- **Backend:** Python 3 / FastAPI / uvicorn / aiosqlite (SQLite with WAL)
- **Frontend:** React 18 / TypeScript / Vite / Tailwind CSS / Zustand
- **AI:** Claude CLI (`claude --print`) called via subprocess; faster-whisper for transcription
- **Auth:** Google OAuth2 with JWT session cookies (authlib + python-jose)
- **External API:** Podcast Index API for search/discovery

## Local Development

### Backend

```bash
cd backend
pip install -r requirements.txt
# Create .env or export env vars (see below)
uvicorn main:app --host 127.0.0.1 --port 8124 --reload
```

### Frontend

```bash
cd frontend
npm install
npm run dev          # Vite dev server on :5173
npm run build        # Production build -> frontend/dist/
```

The backend serves the built frontend from `frontend/dist/` (SPA catch-all). In dev, the Vite dev server runs separately on port 5173.

### Tests

```bash
# Backend unit tests
pytest                          # asyncio_mode = strict

# Frontend E2E
cd frontend && npx playwright test
```

## Deployment

- **Service:** `distillpod.service` (systemd)
- **Port:** 8124 (localhost only; reverse-proxy expected)
- **Restart:** `sudo systemctl restart distillpod`
- **Logs:** `journalctl -u distillpod -f` and `logs/` directory
- **Build step:** Run `cd frontend && npm run build` before deploying frontend changes
- **DB file:** `distillpod.db` in project root (SQLite, auto-migrated on startup)

## Environment Variables

Env file: `/etc/distillpod.env` (mode 600, owned by root, loaded via `EnvironmentFile=` in systemd)

| Variable | Purpose |
|---|---|
| `PODCAST_INDEX_API_KEY` | Podcast Index API key |
| `PODCAST_INDEX_SECRET` | Podcast Index API secret |
| `CLAUDE_BIN` | Path to Claude CLI binary (optional, falls back to PATH) |
| `PUBLIC_URL` | Public-facing domain (CORS, OAuth redirect, report URLs) |
| `GOOGLE_CLIENT_ID` | Google OAuth2 client ID |
| `GOOGLE_CLIENT_SECRET` | Google OAuth2 client secret |
| `ALLOWED_EMAILS` | Comma-separated email allowlist |
| `SESSION_SECRET` | JWT signing secret (`openssl rand -hex 32`) |
| `WHISPER_MODEL` | Whisper model size (default: `medium`) |
| `WHISPER_DEVICE` | Whisper device (default: `cpu`) |
| `TELEGRAM_BOT_TOKEN` | Telegram notifications (optional) |
| `TELEGRAM_CHAT_ID` | Telegram chat ID (optional) |

## Architecture

```
backend/
  main.py              # FastAPI app, middleware, SPA serving
  config.py            # pydantic-settings (Settings)
  database.py          # aiosqlite connection + schema + migrations
  models.py            # Pydantic models
  middleware/auth.py   # JWT session auth middleware
  routers/
    podcasts.py        # Search, subscribe, feed, suggestions
    player.py          # Download, stream audio, transcription status, chapters
    gists.py           # Create/list/delete AI gists (distillations)
    chat.py            # Per-episode AI chat (Claude subprocess)
    research.py        # Deep research reports from gists
    auth.py            # Google OAuth2 login/logout
  services/
    podcast_index.py   # Podcast Index API client
    rss.py             # RSS feed parser
    downloader.py      # Episode audio downloader
    transcriber.py     # faster-whisper transcription
    snip_engine.py     # Gist extraction from transcript
    ad_detector.py     # Ad segment detection
    chapterizer.py     # Auto-chapter generation
    researcher.py      # Deep research report generation
frontend/
  src/                 # React SPA (pages, components, stores, api)
scripts/
  daily-sync.py        # Cron job: sync feeds
  suggest-podcasts.py  # Cron job: AI podcast suggestions
```

## Key Notes

- The backend serves the built frontend as a SPA catch-all -- no separate web server needed.
- Protected API routes: `/gists`, `/podcasts`, `/player`. Auth routes and frontend assets are public.
- `TEST_MODE=true` bypasses auth entirely -- never set in production.
- Claude is invoked as a CLI subprocess (`claude --print <prompt>`), not via API.
- Background tasks (transcription, research) run via `asyncio.create_task` -- they do not survive restarts.
- Git remote: `git@github.com:andrepaim/distillpod.git`
