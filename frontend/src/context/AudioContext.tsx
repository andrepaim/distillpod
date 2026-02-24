import {
  createContext, useContext, useRef, useState, useEffect, useCallback,
  type ReactNode, type RefObject,
} from "react";
import { startPlay, getEpisode, audioStreamUrl, type Episode } from "../api/client";

// ─── Types ────────────────────────────────────────────────────────────────────
export type PlayableEpisode = Episode & {
  podcast_image?: string;
  podcast_title?: string;
};

interface AudioContextValue {
  episode:        PlayableEpisode | null;
  audioRef:       RefObject<HTMLAudioElement>;
  isPlaying:      boolean;
  currentTime:    number;
  duration:       number;
  audioReady:     boolean;
  loadEpisode:    (id: string, ep: PlayableEpisode | null, seekTo?: number) => Promise<void>;
  togglePlay:     () => void;
  seek:           (secs: number) => void;
  skipBy:         (delta: number) => void;
  setRate:        (rate: number) => void;
}

const Ctx = createContext<AudioContextValue | null>(null);

// ─── Provider ─────────────────────────────────────────────────────────────────
export function AudioProvider({ children }: { children: ReactNode }) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const loadedIdRef = useRef<string | null>(null); // prevents double-loading same episode

  const [episode,     setEpisode]     = useState<PlayableEpisode | null>(null);
  const [isPlaying,   setIsPlaying]   = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration,    setDuration]    = useState(0);
  const [audioReady,  setAudioReady]  = useState(false);

  // Wire up persistent audio event listeners once on mount
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;
    const onTime  = () => setCurrentTime(audio.currentTime);
    const onMeta  = () => setDuration(audio.duration || 0);
    const onPlay  = () => setIsPlaying(true);
    const onPause = () => setIsPlaying(false);
    const onEnded = () => setIsPlaying(false);
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
      const played = new Set(JSON.parse(localStorage.getItem("podgist:played") || "[]"));
      played.add(id);
      localStorage.setItem("podgist:played", JSON.stringify([...played]));
    } catch {}
  }, [audioReady]);

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
