from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from pathlib import Path
from config import settings
from database import init_db
from routers import podcasts, player, gists
from routers import auth as auth_router
from routers.chat import router as chat_router
from routers.research import router as research_router
import httpx
import os
from middleware.auth import AuthMiddleware

app = FastAPI(title="DistillPod API", version="0.1.0")


# Middleware order matters: added last = runs first (LIFO in Starlette)
# AuthMiddleware added last so it wraps all requests after CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        settings.frontend_origin,
        "https://distillpod.duckdns.org",
        "http://localhost:8124",
        "http://127.0.0.1:8124",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.add_middleware(AuthMiddleware)

app.include_router(auth_router.router)
app.include_router(podcasts.router)
app.include_router(player.router)
app.include_router(gists.router)
app.include_router(chat_router)
app.include_router(research_router)

# Research reports — explicit route before catch-all SPA
@app.get("/reports/{filename}")
async def serve_report(filename: str):
    report_path = Path("/root/distillpod/reports") / filename
    if report_path.exists() and report_path.suffix == ".html":
        return FileResponse(str(report_path), media_type="text/html")
    from fastapi import HTTPException
    raise HTTPException(status_code=404, detail="Report not found")


@app.on_event("startup")
async def startup():
    await init_db()
    settings.media_dir.mkdir(parents=True, exist_ok=True)
    os.makedirs("/root/distillpod/reports", exist_ok=True)


@app.get("/proxy/image")
async def proxy_image(url: str):
    """Proxy external podcast artwork so Media Session API can load it cross-origin."""
    from fastapi.responses import Response as FastAPIResponse
    try:
        async with httpx.AsyncClient(follow_redirects=True, timeout=10) as client:
            r = await client.get(url, headers={"User-Agent": "DistillPod/1.0"})
            r.raise_for_status()
        content_type = r.headers.get("content-type", "image/jpeg")
        return FastAPIResponse(
            content=r.content,
            media_type=content_type,
            headers={"Cache-Control": "public, max-age=86400"},
        )
    except Exception:
        return FastAPIResponse(status_code=404)


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
