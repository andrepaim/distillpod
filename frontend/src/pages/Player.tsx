import { useEffect, useRef, useState } from "react";
import { useParams, useLocation } from "react-router-dom";
import { startPlay, createSnip, listSnips, audioStreamUrl, getTranscriptStatus, getEpisode, Snip, Episode } from "../api/client";

// ─── Helpers ──────────────────────────────────────────────────────────────────
function fmtTime(secs: number) {
  if (!isFinite(secs) || isNaN(secs)) return "0:00";
  const m = Math.floor(secs / 60);
  const s = Math.floor(secs % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
}

const SPEEDS = [1, 1.5, 2, 0.5];

// ─── Transcript badge ─────────────────────────────────────────────────────────
function TranscriptBadge({ status }: { status: string }) {
  if (status === "done") return (
    <span className="inline-flex items-center gap-1 text-xs bg-green-900 text-green-300 px-2 py-0.5 rounded-full">
      ✓ Transcript ready
    </span>
  );
  if (status === "error") return (
    <span className="inline-flex items-center gap-1 text-xs bg-red-900 text-red-300 px-2 py-0.5 rounded-full">
      ✗ Transcript error
    </span>
  );
  if (status === "processing" || status === "queued") return (
    <span className="inline-flex items-center gap-2 text-xs bg-yellow-900 text-yellow-300 px-2 py-0.5 rounded-full">
      <span className="w-2 h-2 bg-yellow-400 rounded-full animate-pulse inline-block" />
      Transcribing…
    </span>
  );
  return (
    <span className="inline-flex items-center gap-1 text-xs bg-gray-800 text-gray-400 px-2 py-0.5 rounded-full">
      No transcript yet
    </span>
  );
}

// ─── Custom player widget ─────────────────────────────────────────────────────
function PlayerWidget({
  audioRef, snips, transcriptStatus, onSnip, snipping, snipFlash,
}: {
  audioRef: React.RefObject<HTMLAudioElement>;
  snips: Snip[];
  transcriptStatus: string;
  onSnip: () => void;
  snipping: boolean;
  snipFlash: boolean;
}) {
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [speedIdx, setSpeedIdx] = useState(0);
  const progressRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;

    const onTimeUpdate = () => setCurrentTime(audio.currentTime);
    const onLoaded = () => setDuration(audio.duration || 0);
    const onPlay = () => setIsPlaying(true);
    const onPause = () => setIsPlaying(false);
    const onEnded = () => setIsPlaying(false);

    audio.addEventListener("timeupdate", onTimeUpdate);
    audio.addEventListener("loadedmetadata", onLoaded);
    audio.addEventListener("durationchange", onLoaded);
    audio.addEventListener("play", onPlay);
    audio.addEventListener("pause", onPause);
    audio.addEventListener("ended", onEnded);

    return () => {
      audio.removeEventListener("timeupdate", onTimeUpdate);
      audio.removeEventListener("loadedmetadata", onLoaded);
      audio.removeEventListener("durationchange", onLoaded);
      audio.removeEventListener("play", onPlay);
      audio.removeEventListener("pause", onPause);
      audio.removeEventListener("ended", onEnded);
    };
  }, []);

  const togglePlay = () => {
    const audio = audioRef.current;
    if (!audio) return;
    isPlaying ? audio.pause() : audio.play().catch(() => {});
  };

  const skip = (secs: number) => {
    const audio = audioRef.current;
    if (!audio) return;
    audio.currentTime = Math.max(0, Math.min(audio.currentTime + secs, duration));
  };

  const cycleSpeed = () => {
    const audio = audioRef.current;
    if (!audio) return;
    const next = (speedIdx + 1) % SPEEDS.length;
    setSpeedIdx(next);
    audio.playbackRate = SPEEDS[next];
  };

  const handleSeek = (e: React.ChangeEvent<HTMLInputElement>) => {
    const audio = audioRef.current;
    if (!audio) return;
    audio.currentTime = Number(e.target.value);
  };

  const progress = duration > 0 ? (currentTime / duration) * 100 : 0;
  const remaining = duration - currentTime;
  const speed = SPEEDS[speedIdx];

  return (
    <div className="bg-gray-900 rounded-2xl p-5 space-y-4">

      {/* Progress bar + snip markers */}
      <div className="space-y-1">
        <div className="relative h-1.5">
          {/* Track background */}
          <div className="absolute inset-0 rounded-full bg-gray-700" />
          {/* Fill */}
          <div
            className="absolute left-0 top-0 h-full rounded-full bg-indigo-500 pointer-events-none"
            style={{ width: `${progress}%` }}
          />
          {/* Snip markers */}
          {duration > 0 && snips.map(s => (
            <div
              key={s.id}
              className="absolute top-1/2 -translate-y-1/2 w-0.5 h-3 bg-indigo-300 rounded-full pointer-events-none opacity-80"
              style={{ left: `${(s.start_seconds / duration) * 100}%` }}
            />
          ))}
          {/* Invisible range input over the top for interaction */}
          <input
            ref={progressRef}
            type="range"
            min={0}
            max={duration || 100}
            step={1}
            value={currentTime}
            onChange={handleSeek}
            className="absolute inset-0 w-full opacity-0 cursor-pointer h-full"
          />
        </div>

        {/* Time row */}
        <div className="flex justify-between text-xs text-gray-400 font-mono">
          <span>{fmtTime(currentTime)}</span>
          <span>-{fmtTime(remaining)}</span>
        </div>
      </div>

      {/* Controls */}
      <div className="flex items-center justify-between px-2">

        {/* Speed */}
        <button
          onClick={cycleSpeed}
          className="w-10 h-10 flex items-center justify-center text-gray-300 hover:text-white text-sm font-bold rounded-full hover:bg-gray-800 transition-colors"
        >
          {speed === 1 ? "1x" : `${speed}x`}
        </button>

        {/* -10s */}
        <button
          onClick={() => skip(-10)}
          className="w-12 h-12 flex items-center justify-center text-gray-300 hover:text-white rounded-full hover:bg-gray-800 transition-colors"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="w-8 h-8">
            <path d="M2.5 12a9.5 9.5 0 1 1 2.3 6.2" />
            <path d="M2.5 7v5h5" />
            <text x="7.5" y="15" fontSize="6" fill="currentColor" stroke="none" fontWeight="bold">10</text>
          </svg>
        </button>

        {/* Play / Pause */}
        <button
          onClick={togglePlay}
          className="w-16 h-16 bg-indigo-600 hover:bg-indigo-500 active:scale-95 rounded-full flex items-center justify-center transition-all shadow-lg"
        >
          {isPlaying ? (
            <svg viewBox="0 0 24 24" fill="white" className="w-7 h-7">
              <rect x="6" y="4" width="4" height="16" rx="1" />
              <rect x="14" y="4" width="4" height="16" rx="1" />
            </svg>
          ) : (
            <svg viewBox="0 0 24 24" fill="white" className="w-7 h-7 translate-x-0.5">
              <polygon points="5 3 19 12 5 21 5 3" />
            </svg>
          )}
        </button>

        {/* +30s */}
        <button
          onClick={() => skip(30)}
          className="w-12 h-12 flex items-center justify-center text-gray-300 hover:text-white rounded-full hover:bg-gray-800 transition-colors"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="w-8 h-8">
            <path d="M21.5 12a9.5 9.5 0 1 0-2.3 6.2" />
            <path d="M21.5 7v5h-5" />
            <text x="6.5" y="15" fontSize="6" fill="currentColor" stroke="none" fontWeight="bold">30</text>
          </svg>
        </button>

        {/* Snip count */}
        <button
          onClick={onSnip}
          disabled={snipping || transcriptStatus !== "done"}
          className="w-10 h-10 flex items-center justify-center rounded-full hover:bg-gray-800 transition-colors disabled:opacity-30"
        >
          <span className="text-lg">✂️</span>
        </button>

      </div>

      {/* Snip button */}
      <button
        onClick={onSnip}
        disabled={snipping || transcriptStatus !== "done"}
        className={`w-full py-3.5 rounded-xl font-semibold text-base transition-all ${
          snipFlash
            ? "bg-green-600 scale-95"
            : transcriptStatus === "done"
              ? "bg-indigo-600 hover:bg-indigo-500 active:scale-95"
              : "bg-gray-800 text-gray-500 cursor-not-allowed"
        }`}
      >
        {snipping ? "Creating snip…" : transcriptStatus === "done" ? "✂️  Snip this moment" : "⏳  Waiting for transcript…"}
      </button>

    </div>
  );
}

// ─── Snip card ────────────────────────────────────────────────────────────────
function SnipCard({ snip }: { snip: Snip }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    await navigator.clipboard.writeText(snip.text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };
  return (
    <div className="bg-gray-900 rounded-xl p-4 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs text-gray-500 font-mono">
          {fmtTime(snip.start_seconds)} → {fmtTime(snip.end_seconds)}
        </span>
        <button onClick={copy} className="text-xs text-gray-400 hover:text-white px-2 py-0.5 rounded hover:bg-gray-700 transition-colors">
          {copied ? "✓ Copied" : "📋 Copy"}
        </button>
      </div>
      <p className="text-sm leading-relaxed text-gray-100">{snip.text}</p>
      {snip.summary && (
        <p className="text-indigo-300 text-sm italic border-l-2 border-indigo-600 pl-3">{snip.summary}</p>
      )}
    </div>
  );
}

// ─── Player page ──────────────────────────────────────────────────────────────
export default function Player() {
  const { episodeId } = useParams<{ episodeId: string }>();
  const location = useLocation();
  const routeState = location.state as (Episode & { podcast_image?: string; seekTo?: number }) | null;
  const audioRef = useRef<HTMLAudioElement>(null);

  const [episode, setEpisode] = useState<(Episode & { podcast_image?: string }) | null>(routeState);
  const [seekTo] = useState<number | undefined>(routeState?.seekTo);
  const [transcriptStatus, setTranscriptStatus] = useState("none");
  const [snips, setSnips] = useState<Snip[]>([]);
  const [snipping, setSnipping] = useState(false);
  const [snipFlash, setSnipFlash] = useState(false);
  const [audioReady, setAudioReady] = useState(false);
  const [error, setError] = useState("");

  // Load episode + start playback
  useEffect(() => {
    if (!episodeId) return;
    const init = async () => {
      let ep = episode;
      if (!ep?.audio_url) {
        const savedImage = ep?.podcast_image;
        try {
          ep = await getEpisode(episodeId);
          ep = { ...ep, podcast_image: savedImage };
          setEpisode(ep);
        } catch (e: any) { setError("Could not load episode: " + e.message); return; }
      }
      try {
        await startPlay(episodeId, ep.audio_url);
        setAudioReady(true);
        try {
          const key = "podsnip:played";
          const played = new Set(JSON.parse(localStorage.getItem(key) || "[]"));
          played.add(episodeId);
          localStorage.setItem(key, JSON.stringify([...played]));
        } catch {}
      } catch (e: any) { setError(e.message); }
    };
    init();
    listSnips(episodeId).then(setSnips);
  }, [episodeId]);

  // Seek + autoplay after audio ready
  useEffect(() => {
    if (!audioReady || !seekTo || !audioRef.current) return;
    const audio = audioRef.current;
    const doPlay = () => { audio.play().catch(() => {}); audio.removeEventListener("seeked", doPlay); };
    const doSeek = () => { audio.currentTime = seekTo; audio.addEventListener("seeked", doPlay); audio.removeEventListener("loadedmetadata", doSeek); };
    if (audio.readyState >= 1) doSeek();
    else audio.addEventListener("loadedmetadata", doSeek);
    return () => { audio.removeEventListener("loadedmetadata", doSeek); audio.removeEventListener("seeked", doPlay); };
  }, [audioReady, seekTo]);

  // Poll transcript status
  useEffect(() => {
    if (!episodeId || transcriptStatus === "done" || transcriptStatus === "error") return;
    const timer = setInterval(async () => {
      const { status } = await getTranscriptStatus(episodeId);
      setTranscriptStatus(status);
      if (status === "done" || status === "error") clearInterval(timer);
    }, 5000);
    return () => clearInterval(timer);
  }, [episodeId, transcriptStatus]);

  const handleSnip = async () => {
    if (!audioRef.current || !episodeId) return;
    setSnipping(true);
    try {
      const snip = await createSnip(episodeId, audioRef.current.currentTime, false);
      setSnips(prev => [snip, ...prev]);
      setSnipFlash(true);
      setTimeout(() => setSnipFlash(false), 600);
    } catch (e: any) { setError(e.message); }
    finally { setSnipping(false); }
  };

  return (
    <div className="space-y-4">

      {/* Episode header */}
      <div className="flex gap-3 items-start">
        {episode?.podcast_image
          ? <img src={episode.podcast_image} className="w-14 h-14 rounded-xl object-cover flex-shrink-0" alt="" />
          : <div className="w-14 h-14 rounded-xl bg-gray-800 flex-shrink-0 animate-pulse" />
        }
        <div className="min-w-0 flex-1">
          {episode?.title
            ? <h1 className="text-sm font-bold leading-snug line-clamp-3">{episode.title}</h1>
            : <div className="h-4 bg-gray-800 rounded animate-pulse w-3/4" />
          }
          {seekTo !== undefined && (
            <div className="text-xs text-indigo-400 mt-1">▶ From {fmtTime(seekTo)}</div>
          )}
          <div className="mt-1.5"><TranscriptBadge status={transcriptStatus} /></div>
        </div>
      </div>

      {error && <div className="bg-red-900 text-red-300 rounded-xl p-3 text-sm">{error}</div>}

      {/* Hidden audio element */}
      {audioReady && (
        <audio ref={audioRef} src={audioStreamUrl(episodeId!)} preload="auto" className="hidden" />
      )}

      {/* Player widget */}
      {audioReady && (
        <PlayerWidget
          audioRef={audioRef}
          snips={snips}
          transcriptStatus={transcriptStatus}
          onSnip={handleSnip}
          snipping={snipping}
          snipFlash={snipFlash}
        />
      )}

      {!audioReady && !error && (
        <div className="bg-gray-900 rounded-2xl p-5 flex items-center justify-center h-44">
          <div className="text-gray-500 text-sm flex items-center gap-2">
            <span className="w-4 h-4 border-2 border-gray-500 border-t-indigo-400 rounded-full animate-spin inline-block" />
            Loading episode…
          </div>
        </div>
      )}

      {/* Snips list */}
      {snips.length > 0 && (
        <div className="space-y-3">
          <h2 className="text-gray-400 font-medium text-xs uppercase tracking-wide">
            Snips ({snips.length})
          </h2>
          {snips.map(s => <SnipCard key={s.id} snip={s} />)}
        </div>
      )}
    </div>
  );
}
