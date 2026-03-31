import {
  createContext, useContext, useRef, useState, useEffect, useCallback,
  type ReactNode, type RefObject,
} from "react";
import { startPlay, getEpisode, audioStreamUrl, type Episode } from "../api/client";
import { useQueue } from "../stores/queueStore";

// ─── Progress persistence ─────────────────────────────────────────────────────
const PROGRESS_KEY = "distillpod:progress";

export interface ProgressEntry {
  currentTime:    number;
  duration:       number;
  title?:         string;
  podcast_image?: string;
  podcast_title?: string;
  savedAt:        number;
}

export function readProgress(): Record<string, ProgressEntry> {
  try { return JSON.parse(localStorage.getItem(PROGRESS_KEY) || "{}"); } catch { return {}; }
}

function writeProgress(id: string, time: number, dur: number, ep: PlayableEpisode | null) {
  // Bug 6: Use proportional thresholds for short episodes
  // Don't save if nearly finished (within 30s or last 10% for short episodes)
  const endThreshold = dur > 0 ? Math.min(30, dur * 0.1) : 30;
  if (dur > 0 && time > dur - endThreshold) return;
  // Don't save if barely started (under 10s or first 5% for short episodes)
  const startThreshold = dur > 0 ? Math.min(10, dur * 0.05) : 10;
  if (time < startThreshold) return;
  try {
    const map = readProgress();
    map[id] = {
      currentTime:   time,
      duration:      dur,
      title:         ep?.title,
      podcast_image: ep?.podcast_image,
      podcast_title: ep?.podcast_title,
      savedAt:       Date.now(),
    };
    localStorage.setItem(PROGRESS_KEY, JSON.stringify(map));
  } catch {}
}

function clearProgress(id: string) {
  try {
    const map = readProgress();
    delete map[id];
    localStorage.setItem(PROGRESS_KEY, JSON.stringify(map));
  } catch {}
}

// ─── Types ────────────────────────────────────────────────────────────────────
export type PlayableEpisode = Episode & {
  podcast_image?: string;
  podcast_title?: string;
};

interface AudioContextValue {
  episode:           PlayableEpisode | null;
  audioRef:          RefObject<HTMLAudioElement>;
  isPlaying:         boolean;
  currentTime:       number;
  duration:          number;
  audioReady:        boolean;
  loadEpisode:       (id: string, ep: PlayableEpisode | null, seekTo?: number) => Promise<void>;
  togglePlay:        () => void;
  seek:              (secs: number) => void;
  skipBy:            (delta: number) => void;
  setRate:           (rate: number) => void;
  playerExpanded:    boolean;
  setPlayerExpanded: (v: boolean) => void;
}

const Ctx = createContext<AudioContextValue | null>(null);

// ─── Provider ─────────────────────────────────────────────────────────────────
export function AudioProvider({ children }: { children: ReactNode }) {
  const audioRef     = useRef<HTMLAudioElement>(null);
  const loadedIdRef  = useRef<string | null>(null); // prevents double-loading same episode
  const episodeRef   = useRef<PlayableEpisode | null>(null); // for access inside event listeners
  const lastSaveRef  = useRef<number>(0);            // throttle: last progress-save timestamp

  const loadEpisodeRef = useRef<AudioContextValue["loadEpisode"] | null>(null);

  const [episode,        setEpisode]        = useState<PlayableEpisode | null>(null);
  const [isPlaying,      setIsPlaying]      = useState(false);
  const [currentTime,    setCurrentTime]    = useState(0);
  const [duration,       setDuration]       = useState(0);
  const [audioReady,     setAudioReady]     = useState(false);
  const [playerExpanded, setPlayerExpanded] = useState(false);

  // Keep episodeRef in sync for use inside event listeners
  useEffect(() => { episodeRef.current = episode; }, [episode]);

  // ── Media Session: update metadata when episode changes ─────────────────
  useEffect(() => {
    if (!episode || !("mediaSession" in navigator)) return;
    // Proxy external artwork through our own domain so Chrome can load it
    const artwork: MediaImage[] = [
      { src: "/icon-512.png", sizes: "512x512", type: "image/png" }, // local fallback
    ];
    if (episode.podcast_image) {
      const proxied = `/proxy/image?url=${encodeURIComponent(episode.podcast_image)}`;
      artwork.unshift(
        { src: proxied, sizes: "512x512", type: "image/jpeg" },
        { src: proxied, sizes: "256x256", type: "image/jpeg" },
      );
    }
    navigator.mediaSession.metadata = new MediaMetadata({
      title:  episode.title         || "Unknown Episode",
      artist: episode.podcast_title  || "DistillPod",
      album:  "DistillPod ⚗️",
      artwork,
    });
  }, [episode]);

  // ── Media Session: action handlers (mounted once — audio element is stable) ─
  useEffect(() => {
    if (!("mediaSession" in navigator)) return;
    const audio = audioRef.current;
    if (!audio) return;

    const handlers: [MediaSessionAction, MediaSessionActionHandler][] = [
      ["play",           ()  => audio.play().catch(() => {})],
      ["pause",          ()  => audio.pause()],
      ["seekbackward",   (d) => { audio.currentTime = Math.max(0, audio.currentTime - (d.seekOffset ?? 10)); }],
      ["seekforward",    (d) => { audio.currentTime = Math.min(audio.duration || Infinity, audio.currentTime + (d.seekOffset ?? 30)); }],
      ["seekto",         (d) => { if (d.seekTime != null) audio.currentTime = d.seekTime; }],
      // previoustrack / nexttrack: shown in compact Android notification as ⏮ ⏭
      ["previoustrack",  ()  => { audio.currentTime = Math.max(0, audio.currentTime - 10); }],
      ["nexttrack",      ()  => { audio.currentTime = Math.min(audio.duration || Infinity, audio.currentTime + 30); }],
    ];
    handlers.forEach(([action, handler]) =>
      navigator.mediaSession.setActionHandler(action, handler)
    );
    return () => handlers.forEach(([action]) =>
      navigator.mediaSession.setActionHandler(action, null)
    );
  }, []);

  // Wire up persistent audio event listeners once on mount
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;

    const onTime = () => {
      setCurrentTime(audio.currentTime);
      // Throttled progress save (every 5 s)
      const now = Date.now();
      if (loadedIdRef.current && now - lastSaveRef.current > 5_000) {
        lastSaveRef.current = now;
        writeProgress(loadedIdRef.current, audio.currentTime, audio.duration || 0, episodeRef.current);
      }
      // Keep lock-screen scrubber in sync
      if ("mediaSession" in navigator && audio.duration > 0) {
        try {
          navigator.mediaSession.setPositionState({
            duration:     audio.duration,
            playbackRate: audio.playbackRate,
            position:     audio.currentTime,
          });
        } catch {}
      }
    };
    const onMeta  = () => setDuration(audio.duration || 0);
    const onPlay  = () => {
      setIsPlaying(true);
      if ("mediaSession" in navigator) navigator.mediaSession.playbackState = "playing";
    };
    const onPause = () => {
      setIsPlaying(false);
      if ("mediaSession" in navigator) navigator.mediaSession.playbackState = "paused";
    };
    const onEnded = () => {
      setIsPlaying(false);
      if ("mediaSession" in navigator) navigator.mediaSession.playbackState = "paused";
      // Episode finished — clear saved progress
      if (loadedIdRef.current) clearProgress(loadedIdRef.current);
      // Auto-advance: play next item from queue
      const next = useQueue.getState().shift();
      if (next && loadEpisodeRef.current) {
        loadEpisodeRef.current(next.episodeId, {
          id: next.episodeId,
          title: next.title,
          audio_url: next.audioUrl,
          image_url: next.imageUrl,
          podcast_image: next.imageUrl,
          podcast_title: next.podcastTitle,
          podcast_id: "",
          downloaded: false,
          transcript_status: "none",
        } as PlayableEpisode, 0);
      }
    };

    audio.addEventListener("timeupdate",     onTime);
    audio.addEventListener("loadedmetadata", onMeta);
    audio.addEventListener("durationchange", onMeta);
    audio.addEventListener("play",  onPlay);
    audio.addEventListener("pause", onPause);
    audio.addEventListener("ended", onEnded);
    return () => {
      audio.removeEventListener("timeupdate",     onTime);
      audio.removeEventListener("loadedmetadata", onMeta);
      audio.removeEventListener("durationchange", onMeta);
      audio.removeEventListener("play",  onPlay);
      audio.removeEventListener("pause", onPause);
      audio.removeEventListener("ended", onEnded);
    };
  }, []);

  const loadEpisode = useCallback(async (
    id: string,
    ep: PlayableEpisode | null,
    seekTo?: number,
  ) => {
    const audio = audioRef.current;
    if (!audio) return;

    // Same episode already loaded → just seek if needed, no reload
    if (loadedIdRef.current === id && audioReady) {
      if (seekTo != null) {
        audio.currentTime = seekTo;
        audio.play().catch(() => {});
      }
      return;
    }

    // Resolve episode data if not provided
    let resolved = ep;
    if (!resolved?.audio_url) {
      const savedImage = resolved?.podcast_image;
      const savedTitle = resolved?.podcast_title;
      resolved = await getEpisode(id);
      resolved = { ...resolved, podcast_image: savedImage, podcast_title: savedTitle };
    }

    // Ensure file is downloaded (startPlay is idempotent)
    await startPlay(id, resolved.audio_url);

    // Swap src
    loadedIdRef.current = id;
    setEpisode(resolved);
    setAudioReady(false);
    setCurrentTime(0);
    setDuration(0);

    audio.src = audioStreamUrl(id);
    audio.load();
    setAudioReady(true);

    // Seek + autoplay
    const play = () => audio.play().catch(() => {});
    if (seekTo != null) {
      const doSeek = () => {
        audio.currentTime = seekTo;
        audio.addEventListener("seeked",   play, { once: true });
        audio.addEventListener("canplay",  play, { once: true });
      };
      audio.readyState >= 1
        ? doSeek()
        : audio.addEventListener("loadedmetadata", doSeek, { once: true });
    } else {
      play();
    }

    // Mark as played in localStorage
    try {
      const played = new Set(JSON.parse(localStorage.getItem("distillpod:played") || "[]"));
      played.add(id);
      localStorage.setItem("distillpod:played", JSON.stringify([...played]));
    } catch {}
  }, [audioReady]);

  // Keep ref in sync so onEnded can call loadEpisode
  useEffect(() => { loadEpisodeRef.current = loadEpisode; }, [loadEpisode]);

  const togglePlay = useCallback(() => {
    const audio = audioRef.current;
    if (!audio || !audioReady) return;
    isPlaying ? audio.pause() : audio.play().catch(() => {});
  }, [isPlaying, audioReady]);

  const seek = useCallback((secs: number) => {
    const audio = audioRef.current;
    if (audio) audio.currentTime = secs;
  }, []);

  const skipBy = useCallback((delta: number) => {
    const audio = audioRef.current;
    if (audio) audio.currentTime = Math.max(0, Math.min(audio.currentTime + delta, duration));
  }, [duration]);

  const setRate = useCallback((rate: number) => {
    const audio = audioRef.current;
    if (audio) audio.playbackRate = rate;
  }, []);

  return (
    <Ctx.Provider value={{
      episode, audioRef, isPlaying, currentTime, duration, audioReady,
      loadEpisode, togglePlay, seek, skipBy, setRate,
      playerExpanded, setPlayerExpanded,
    }}>
      {/* Single persistent audio element — never unmounts */}
      <audio ref={audioRef} preload="auto" className="hidden" />
      {children}
    </Ctx.Provider>
  );
}

// ─── Hook ─────────────────────────────────────────────────────────────────────
export function useAudio() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useAudio must be inside <AudioProvider>");
  return ctx;
}
