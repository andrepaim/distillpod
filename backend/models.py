from pydantic import BaseModel
from typing import Optional
from datetime import datetime


class Podcast(BaseModel):
    id: str                        # Podcast Index feed ID
    title: str
    author: str
    description: str
    image_url: Optional[str]
    feed_url: str
    website_url: Optional[str]
    episode_count: Optional[int]


class Episode(BaseModel):
    id: str                        # guid from RSS
    podcast_id: str
    title: str
    description: Optional[str]
    audio_url: str
    duration_seconds: Optional[int]
    published_at: Optional[datetime]
    image_url: Optional[str]
    # Local state
    downloaded: bool = False
    local_path: Optional[str] = None
    transcript_status: str = "none"   # none | queued | processing | done | error


class Subscription(BaseModel):
    podcast_id: str
    feed_url: str
    title: str
    image_url: Optional[str]
    last_checked: Optional[datetime]
    subscribed_at: datetime


class TranscriptWord(BaseModel):
    word: str
    start: float    # seconds
    end: float      # seconds


class Transcript(BaseModel):
    episode_id: str
    words: list[TranscriptWord]
    language: Optional[str]
    created_at: datetime


class Snip(BaseModel):
    id: str
    episode_id: str
    podcast_id: str
    episode_title: str
    podcast_title: str
    start_seconds: float
    end_seconds: float
    text: str                     # extracted from transcript
    summary: Optional[str]        # GPT-4o-mini summary (optional)
    created_at: datetime


# Request / Response schemas

class SnipRequest(BaseModel):
    episode_id: str
    current_seconds: float        # playback position when user tapped Snip


class PlayRequest(BaseModel):
    episode_id: str
    audio_url: str                # original RSS audio URL


class TranscriptStatus(BaseModel):
    episode_id: str
    status: str                   # none | queued | processing | done | error
    progress_percent: Optional[float]
