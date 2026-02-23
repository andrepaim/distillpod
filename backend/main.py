from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pathlib import Path
from config import settings
from database import init_db
from routers import podcasts, player, snips

app = FastAPI(title="PodSnip API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=[settings.frontend_origin, "http://localhost:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(podcasts.router)
app.include_router(player.router)
app.include_router(snips.router)

# Serve built frontend (production)
frontend_dist = Path(__file__).parent.parent / "frontend" / "dist"
if frontend_dist.exists():
    app.mount("/", StaticFiles(directory=str(frontend_dist), html=True), name="static")


@app.on_event("startup")
async def startup():
    await init_db()
    settings.media_dir.mkdir(parents=True, exist_ok=True)


@app.get("/health")
async def health():
    return {"status": "ok", "version": "0.1.0"}
