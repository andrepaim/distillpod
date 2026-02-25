from pydantic_settings import BaseSettings
from pathlib import Path

class Settings(BaseSettings):
    # Podcast Index API (https://podcastindex.org/developer)
    podcast_index_api_key: str = ""
    podcast_index_secret: str = ""

    # Snip summaries use Claude CLI (claude --print) — no API key needed
    # Requires: `claude` CLI installed and authenticated via `claude login`

    # Storage
    media_dir: Path = Path("/root/distillpod/media")
    db_path: Path = Path("/root/distillpod/distillpod.db")

    # Server
    host: str = "127.0.0.1"
    port: int = 8124
    frontend_origin: str = "http://localhost:5173"

    # Transcription
    whisper_model: str = "base"           # base / small / medium / large-v3
    whisper_device: str = "cpu"
    gist_context_seconds: int = 60        # seconds of audio captured per shot

    # Auth — Google OAuth
    google_client_id: str = ""
    google_client_secret: str = ""
    allowed_emails: str = "andrepaim@gmail.com"     # comma-separated allowlist
    session_secret: str = "change-me-in-production"
    session_max_age: int = 30 * 24 * 3600           # 30 days in seconds

    # Test mode — bypass auth for E2E tests. NEVER true in prod.
    test_mode: bool = False

    class Config:
        env_file = ".env"

settings = Settings()
