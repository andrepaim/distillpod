const BASE = import.meta.env.VITE_API_URL ?? "http://localhost:8124";

async function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  const r = await fetch(`${BASE}${path}`, {
    method,
    credentials: "include",
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  if (r.status === 401) {
    // Session expired or missing — navigate to root to trigger auth check in App.tsx
    window.location.href = "/";
    throw new Error("Unauthorized");
  }
  if (!r.ok) throw new Error(`${method} ${path} → ${r.status}`);
  return r.json();
}

// --- Podcasts ---
export const searchPodcasts = (q: string) =>
  req<Podcast[]>("GET", `/podcasts/search?q=${encodeURIComponent(q)}`);

export const getSubscriptions = () =>
  req<Subscription[]>("GET", "/podcasts/subscriptions");

export const subscribe = (podcastId: string, feedUrl: string, title: string, imageUrl?: string) =>
  req("POST", `/podcasts/subscriptions/${podcastId}?feed_url=${encodeURIComponent(feedUrl)}&title=${encodeURIComponent(title)}${imageUrl ? `&image_url=${encodeURIComponent(imageUrl)}` : ""}`);

export const unsubscribe = (podcastId: string) =>
  req("DELETE", `/podcasts/subscriptions/${podcastId}`);

export const getEpisodes = (podcastId: string, refresh = false) =>
  req<Episode[]>("GET", `/podcasts/${podcastId}/episodes?refresh=${refresh}`);

// --- Player ---
export const startPlay = (episodeId: string, audioUrl: string) =>
  req<{ audio_url: string; transcript_status: string }>(
    "POST", "/player/play", { episode_id: episodeId, audio_url: audioUrl }
  );

export const getTranscriptStatus = (episodeId: string) =>
  req<{ status: string }>("GET", `/player/transcript-status/${episodeId}`);

export const getEpisode = (episodeId: string) =>
  req<Episode>("GET", `/player/episode/${episodeId}`);

export const audioStreamUrl = (episodeId: string) => `${BASE}/player/audio/${episodeId}`;

// --- Shots ---
export const createGist = (episodeId: string, currentSeconds: number, withSummary = false) =>
  req<Gist>("POST", `/gists/?summary=${withSummary}`, {
    episode_id: episodeId,
    current_seconds: currentSeconds,
  });

export const listGists = (episodeId?: string) =>
  req<Gist[]>("GET", episodeId ? `/gists/?episode_id=${episodeId}` : "/gists/");

export const deleteGist = (snipId: string) =>
  req("DELETE", `/gists/${snipId}`);

// --- Types ---
export interface Podcast {
  id: string; title: string; author: string; description: string;
  image_url?: string; feed_url: string; episode_count?: number;
}
export interface Subscription {
  podcast_id: string; feed_url: string; title: string; image_url?: string;
  subscribed_at: string;
}
export interface Episode {
  id: string; podcast_id: string; title: string; description?: string;
  audio_url: string; duration_seconds?: number; published_at?: string;
  image_url?: string; downloaded: boolean; transcript_status: string;
}
export interface Gist {
  id: string; episode_id: string; podcast_id: string;
  episode_title: string; podcast_title: string;
  start_seconds: number; end_seconds: number;
  text: string; summary?: string; created_at: string;
}
