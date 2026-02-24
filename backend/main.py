from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pathlib import Path
from config import settings
from database import init_db
from routers import podcasts, player, shots

app = FastAPI(title="EarShot API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(podcasts.router)
app.include_router(player.router)
app.include_router(shots.router)


@app.on_event("startup")
async def startup():
    await init_db()
    settings.media_dir.mkdir(parents=True, exist_ok=True)


@app.get("/health")
async def health():
    return {"status": "ok", "version": "0.1.0"}


# Serve built frontend — mount LAST so API routes take priority
frontend_dist = Path(__file__).parent.parent / "frontend" / "dist"
if frontend_dist.exists():
    app.mount("/", StaticFiles(directory=str(frontend_dist), html=True), name="static")
