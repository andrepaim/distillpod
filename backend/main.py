from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from pathlib import Path
from config import settings
from database import init_db
from routers import podcasts, player, gists

app = FastAPI(title="DistillPod API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(podcasts.router)
app.include_router(player.router)
app.include_router(gists.router)


@app.on_event("startup")
async def startup():
    await init_db()
    settings.media_dir.mkdir(parents=True, exist_ok=True)


@app.get("/health")
async def health():
    return {"status": "ok", "version": "0.1.0"}


# Serve built frontend
frontend_dist = Path(__file__).parent.parent / "frontend" / "dist"
if frontend_dist.exists():
    # Serve hashed JS/CSS/image assets directly
    app.mount("/assets", StaticFiles(directory=str(frontend_dist / "assets")), name="assets")

    # Catch-all: serve real files from dist/ (manifest, icons, etc.) or fall back to index.html
    import mimetypes
    _EXTRA_TYPES = {
        ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
        ".svg": "image/svg+xml", ".ico": "image/x-icon",
        ".json": "application/json", ".webmanifest": "application/manifest+json",
        ".woff2": "font/woff2", ".woff": "font/woff",
    }
    @app.get("/{full_path:path}")
    async def serve_spa(full_path: str):
        candidate = frontend_dist / full_path
        if candidate.exists() and candidate.is_file():
            mime = _EXTRA_TYPES.get(candidate.suffix.lower()) or mimetypes.guess_type(str(candidate))[0]
            return FileResponse(str(candidate), media_type=mime or "application/octet-stream")
        return FileResponse(str(frontend_dist / "index.html"), media_type="text/html")
