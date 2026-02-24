from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from pathlib import Path
from config import settings
from database import init_db
from routers import podcasts, player, gists

app = FastAPI(title="PodGist API", version="0.1.0")

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

    # Catch-all: return index.html for any unmatched path (SPA client-side routing)
    @app.get("/{full_path:path}")
    async def serve_spa(full_path: str):
        return FileResponse(str(frontend_dist / "index.html"))
