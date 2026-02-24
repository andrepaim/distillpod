from pydantic_settings import BaseSettings
from pathlib import Path

class Settings(BaseSettings):
    # Podcast Index API (https://podcastindex.org/developer)
    podcast_index_api_key: str = ""
    podcast_index_secret: str = ""

    # Snip summaries use Claude CLI (claude --print) — no API key needed
    # Requires: `claude` CLI installed and authenticated via `claude login`

    # Storage
    media_dir: Path = Path("/root/podsnip/media")
    db_path: Path = Path("/root/podsnip/podsnip.db")

    # Server
    host: str = "127.0.0.1"
    port: int = 8124
    frontend_origin: str = "http://localhost:5173"

    # Transcription
    whisper_model: str = "base"           # base / small / medium / large-v3
    whisper_device: str = "cpu"
    snip_context_seconds: int = 60        # seconds of audio captured per snip

    class Config:
        env_file = ".env"

settings = Settings()
