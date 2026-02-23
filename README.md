# PodSnip 🎙✂️

A minimal self-hosted podcast app with AI-powered snips. Botler is the backend.

## Features
- Search podcasts (Podcast Index API)
- Subscribe & get new episodes via RSS
- Listen in browser (audio served from VPS)
- Snip any moment → instant transcript extract + optional GPT summary

## Quick Start
```bash
# 1. Configure
cp .env.example .env && nano .env

# 2. Backend
cd backend && pip install -r requirements.txt
uvicorn main:app --host 127.0.0.1 --port 8124 --reload

# 3. Frontend (dev)
cd frontend && npm install && npm run dev
```

Open http://localhost:5173

## Design
See [docs/design.md](docs/design.md) for the full architecture and design decisions.
