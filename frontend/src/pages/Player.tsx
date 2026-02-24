import { useEffect, useState } from "react";
import { useParams, useLocation } from "react-router-dom";
import { createGist, listGists, getTranscriptStatus, Gist, Episode } from "../api/client";
import { useAudio, type PlayableEpisode } from "../context/AudioContext";

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

// ─── Player widget ────────────────────────────────────────────────────────────
function PlayerWidget({
  gists, transcriptStatus, onGist, gisting, gistFlash,
}: {
  gists: Gist[];
  transcriptStatus: string;
  onGist: () => void;
  gisting: boolean;
  gistFlash: boolean;
}) {
  const { audioRef, isPlaying, currentTime, duration, togglePlay, skipBy, setRate } = useAudio();
  const [speedIdx, setSpeedIdx] = useState(0);

  const cycleSpeed = () => {
    const next = (speedIdx + 1) % SPEEDS.length;
    setSpeedIdx(next);
    setRate(SPEEDS[next]);
  };

  const handleSeek = (e: React.ChangeEvent<HTMLInputElement>) => {
    const audio = audioRef.current;
    if (audio) audio.currentTime = Number(e.target.value);
  };

  const progress   = duration > 0 ? (currentTime / duration) * 100 : 0;
  const remaining  = duration - currentTime;
  const speed      = SPEEDS[speedIdx];

  return (
    <div className="bg-gray-900 rounded-2xl p-5 space-y-4">

      {/* Progress bar + gist markers */}
      <div className="space-y-1">
        <div className="relative h-1.5">
          <div className="absolute inset-0 rounded-full bg-gray-700" />
          <div
            className="absolute left-0 top-0 h-full rounded-full bg-indigo-500 pointer-events-none"
            style={{ width: `${progress}%` }}
          />
          {duration > 0 && gists.map(s => (
            <div
              key={s.id}
              className="absolute top-1/2 -translate-y-1/2 w-0.5 h-3 bg-indigo-300 rounded-full pointer-events-none opacity-80"
              style={{ left: `${(s.start_seconds / duration) * 100}%` }}
            />
          ))}
          <input
            type="range" min={0} max={duration || 100} step={1} value={currentTime}
            onChange={handleSeek}
            className="absolute inset-0 w-full opacity-0 cursor-pointer h-full"
          />
        </div>
        <div className="flex justify-between text-xs text-gray-400 font-mono">
          <span>{fmtTime(currentTime)}</span>
          <span>-{fmtTime(remaining)}</span>
        </div>
      </div>

      {/* Controls */}
      <div className="flex items-center justify-between px-2">
        <button
          onClick={cycleSpeed}
          className="w-10 h-10 flex items-center justify-center text-gray-300 hover:text-white text-sm font-bold rounded-full hover:bg-gray-800 transition-colors"
        >
          {speed === 1 ? "1x" : `${speed}x`}
        </button>

        <button
          onClick={() => skipBy(-10)}
          className="w-12 h-12 flex items-center justify-center text-gray-300 hover:text-white rounded-full hover:bg-gray-800 transition-colors"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="w-8 h-8">
            <path d="M2.5 12a9.5 9.5 0 1 1 2.3 6.2" /><path d="M2.5 7v5h5" />
            <text x="7.5" y="15" fontSize="6" fill="currentColor" stroke="none" fontWeight="bold">10</text>
          </svg>
        </button>

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

        <button
          onClick={() => skipBy(30)}
          className="w-12 h-12 flex items-center justify-center text-gray-300 hover:text-white rounded-full hover:bg-gray-800 transition-colors"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="w-8 h-8">
            <path d="M21.5 12a9.5 9.5 0 1 0-2.3 6.2" /><path d="M21.5 7v5h-5" />
            <text x="6.5" y="15" fontSize="6" fill="currentColor" stroke="none" fontWeight="bold">30</text>
          </svg>
        </button>

        <button
          onClick={onGist}
          disabled={gisting || transcriptStatus !== "done"}
          className="w-10 h-10 flex items-center justify-center rounded-full hover:bg-gray-800 transition-colors disabled:opacity-30"
        >
          <span className="text-lg">✂️</span>
        </button>
      </div>

      {/* Gist button */}
      <button
        onClick={onGist}
        disabled={gisting || transcriptStatus !== "done"}
        className={`w-full py-3.5 rounded-xl font-semibold text-base transition-all ${
          gistFlash
            ? "bg-green-600 scale-95"
            : transcriptStatus === "done"
              ? "bg-indigo-600 hover:bg-indigo-500 active:scale-95"
              : "bg-gray-800 text-gray-500 cursor-not-allowed"
        }`}
      >
        {gisting
          ? "Summarising…"
          : transcriptStatus === "done"
            ? "✂️  Gist + summarise"
            : "⏳  Waiting for transcript…"}
      </button>

    </div>
  );
}

// ─── Gist card ────────────────────────────────────────────────────────────────
function parseGistSummary(s: string | undefined): { quote?: string; insight?: string } | null {
  if (!s) return null;
  try {
    const p = JSON.parse(s);
    if (p.quote || p.insight) return p;
  } catch {}
  return { insight: s };
}

function GistCard({ gist }: { gist: Gist }) {
  const [copied, setCopied] = useState(false);
  const ai = parseGistSummary(gist.summary);

  const copy = async () => {
    const text = ai
      ? [ai.quote && `"${ai.quote}"`, ai.insight].filter(Boolean).join("\n\n")
      : gist.text;
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="bg-gray-900 rounded-xl p-4 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs text-gray-500 font-mono">
          {fmtTime(gist.start_seconds)} → {fmtTime(gist.end_seconds)}
        </span>
        <button onClick={copy} className="text-xs text-gray-400 hover:text-white px-2 py-0.5 rounded hover:bg-gray-700 transition-colors">
          {copied ? "✓ Copied" : "📋 Copy"}
        </button>
      </div>
      {ai ? (
        <>
          {ai.quote && <p className="text-sm italic text-gray-100 border-l-2 border-indigo-500 pl-3">"{ai.quote}"</p>}
          {ai.insight && <p className="text-indigo-300 text-sm leading-relaxed">{ai.insight}</p>}
        </>
      ) : (
        <p className="text-sm leading-relaxed text-gray-100">{gist.text}</p>
      )}
    </div>
  );
}

// ─── Player page ──────────────────────────────────────────────────────────────
export default function Player() {
  const { episodeId } = useParams<{ episodeId: string }>();
  const location      = useLocation();
  const routeState    = location.state as (PlayableEpisode & { seekTo?: number }) | null;

  const { loadEpisode, audioReady, audioRef, episode } = useAudio();

  // seekTo comes only from current navigation state (gist → player link)
  const seekTo = routeState?.seekTo;

  const [transcriptStatus, setTranscriptStatus] = useState("none");
  const [gists,    setGists]    = useState<Gist[]>([]);
  const [gisting,  setGisting]  = useState(false);
  const [gistFlash,setGistFlash]= useState(false);
  const [error,    setError]    = useState("");

  // Display episode: prefer what's already in context (avoids blank header flash on same episode)
  const displayEpisode = episode?.id === episodeId ? episode : routeState;

  // Load episode into audio context
  useEffect(() => {
    if (!episodeId) return;
    const ep: PlayableEpisode | null = routeState
      ? { ...routeState, podcast_image: routeState.podcast_image, podcast_title: routeState.podcast_title }
      : null;
    loadEpisode(episodeId, ep, seekTo).catch(e => setError(e.message));
    listGists(episodeId).then(setGists);
  }, [episodeId]); // intentionally only re-run on episodeId change

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

  // Also fetch transcript status immediately when episode loads
  useEffect(() => {
    if (!episodeId) return;
    getTranscriptStatus(episodeId).then(({ status }) => setTranscriptStatus(status));
  }, [episodeId]);

  const handleGist = async () => {
    if (!audioRef.current || !episodeId) return;
    setGisting(true);
    try {
      const gist = await createGist(episodeId, audioRef.current.currentTime, true);
      setGists(prev => [gist, ...prev]);
      setGistFlash(true);
      setTimeout(() => setGistFlash(false), 600);
    } catch (e: any) { setError(e.message); }
    finally { setGisting(false); }
  };

  return (
    <div className="space-y-4">

      {/* Episode header */}
      <div className="flex gap-3 items-start">
        {displayEpisode?.podcast_image
          ? <img src={displayEpisode.podcast_image} className="w-14 h-14 rounded-xl object-cover flex-shrink-0" alt="" />
          : <div className="w-14 h-14 rounded-xl bg-gray-800 flex-shrink-0 animate-pulse" />
        }
        <div className="min-w-0 flex-1">
          {displayEpisode?.title
            ? <h1 className="text-sm font-bold leading-snug line-clamp-3">{displayEpisode.title}</h1>
            : <div className="h-4 bg-gray-800 rounded animate-pulse w-3/4" />
          }
          {seekTo !== undefined && (
            <div className="text-xs text-indigo-400 mt-1">▶ From {fmtTime(seekTo)}</div>
          )}
          <div className="mt-1.5"><TranscriptBadge status={transcriptStatus} /></div>
        </div>
      </div>

      {error && <div className="bg-red-900 text-red-300 rounded-xl p-3 text-sm">{error}</div>}

      {/* Player widget — shown once audio is ready */}
      {audioReady && episodeId && episode?.id === episodeId && (
        <PlayerWidget
          gists={gists}
          transcriptStatus={transcriptStatus}
          onGist={handleGist}
          gisting={gisting}
          gistFlash={gistFlash}
        />
      )}

      {(!audioReady || episode?.id !== episodeId) && !error && (
        <div className="bg-gray-900 rounded-2xl p-5 flex items-center justify-center h-44">
          <div className="text-gray-500 text-sm flex items-center gap-2">
            <span className="w-4 h-4 border-2 border-gray-500 border-t-indigo-400 rounded-full animate-spin inline-block" />
            Loading episode…
          </div>
        </div>
      )}

      {/* Gists list */}
      {gists.length > 0 && (
        <div className="space-y-3">
          <h2 className="text-gray-400 font-medium text-xs uppercase tracking-wide">
            Gists ({gists.length})
          </h2>
          {gists.map(s => <GistCard key={s.id} gist={s} />)}
        </div>
      )}
    </div>
  );
}
