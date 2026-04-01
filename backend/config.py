import shutil
from pydantic_settings import BaseSettings
from pathlib import Path

class Settings(BaseSettings):
    # Podcast Index API (https://podcastindex.org/developer)
    podcast_index_api_key: str = ""
    podcast_index_secret: str = ""

    # Claude CLI — resolved via PATH by default
    claude_bin: str = ""

    @property
    def claude(self) -> str:
        return self.claude_bin or shutil.which("claude") or "claude"

    # Storage
    media_dir: Path = Path("media")
    db_path: Path = Path("distillpod.db")
    reports_dir: Path = Path("reports")

    # Public-facing domain (used for report URLs, OAuth redirect, CORS)
    public_url: str = "https://your-domain.example.com"

    # Server
    host: str = "127.0.0.1"
    port: int = 8124
    frontend_origin: str = "http://localhost:5173"

    # Transcription
    whisper_model: str = "medium"           # base / small / medium / large-v3
    whisper_device: str = "cpu"
    gist_context_seconds: int = 60        # seconds of audio captured per shot

    # Auth — Google OAuth
    google_client_id: str = ""
    google_client_secret: str = ""
    allowed_emails: str = ""     # comma-separated allowlist
    session_secret: str = ""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        if not self.session_secret or self.session_secret == "change-me-in-production":
            import warnings
            warnings.warn(
                "SESSION_SECRET is not set or uses the default value. "
                "Set a strong random secret in your .env file (openssl rand -hex 32).",
                stacklevel=2,
            )
    session_max_age: int = 30 * 24 * 3600           # 30 days in seconds

    # Test mode — bypass auth for E2E tests. NEVER true in prod.
    test_mode: bool = False

    # Notifications
    telegram_bot_token: str = ""
    telegram_chat_id: str = ""

    class Config:
        env_file = ".env"

settings = Settings()
