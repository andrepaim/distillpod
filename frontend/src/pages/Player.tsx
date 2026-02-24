import { useEffect, useRef, useState } from "react";
import { useParams, useLocation } from "react-router-dom";
import { startPlay, createSnip, listSnips, audioStreamUrl, getTranscriptStatus, getEpisode, Snip, Episode } from "../api/client";

function fmtTime(secs: number) {
  const m = Math.floor(secs / 60);
  const s = Math.floor(secs % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
}

function TranscriptBadge({ status }: { status: string }) {
  if (status === "done") return (
    <span className="inline-flex items-center gap-1 text-xs bg-green-900 text-green-300 px-2 py-0.5 rounded-full">
      <span>✓</span> Transcript ready
    </span>
  );
  if (status === "error") return (
    <span className="inline-flex items-center gap-1 text-xs bg-red-900 text-red-300 px-2 py-0.5 rounded-full">
      ✗ Transcript error
    </span>
  );
  if (status === "processing" || status === "queued") return (
    <span className="inline-flex items-center gap-2 text-xs bg-yellow-900 text-yellow-300 px-2 py-0.5 rounded-full">
      <span className="inline-block w-2 h-2 bg-yellow-400 rounded-full animate-pulse" />
      Transcribing…
    </span>
  );
  return (
    <span className="inline-flex items-center gap-1 text-xs bg-gray-800 text-gray-400 px-2 py-0.5 rounded-full">
      No transcript yet
    </span>
  );
}

function SnipCard({ snip }: { snip: Snip }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    await navigator.clipboard.writeText(snip.text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="bg-gray-900 rounded-lg p-4 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs text-gray-500 font-mono">
          {fmtTime(snip.start_seconds)} → {fmtTime(snip.end_seconds)}
        </span>
        <button
          onClick={copy}
          className="text-xs text-gray-400 hover:text-white px-2 py-0.5 rounded hover:bg-gray-700 transition-colors"
        >
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

  useEffect(() => {
    if (!episodeId) return;

    // Fetch episode from backend if not passed via router state
    const init = async () => {
      let ep = episode;
      if (!ep?.audio_url) {
        try { ep = await getEpisode(episodeId); setEpisode(ep); }
        catch (e: any) { setError("Could not load episode: " + e.message); return; }
      }

      try {
        await startPlay(episodeId, ep.audio_url);
        setAudioReady(true);
        // Mark as played
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
    } catch (e: any) {
      setError(e.message);
    } finally {
      setSnipping(false);
    }
  };

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex gap-4 items-start">
        {episode?.podcast_image
          ? <img src={episode.podcast_image} className="w-16 h-16 rounded-lg object-cover flex-shrink-0" alt="" />
          : <div className="w-16 h-16 rounded-lg bg-gray-800 flex-shrink-0 animate-pulse" />
        }
        <div className="min-w-0 flex-1">
          {episode?.title
            ? <h1 className="text-base font-bold leading-snug line-clamp-2">{episode.title}</h1>
            : <div className="h-5 bg-gray-800 rounded animate-pulse w-3/4 mb-1" />
          }
          {seekTo !== undefined && (
            <div className="text-xs text-indigo-400 mt-0.5">▶ Playing from {fmtTime(seekTo)}</div>
          )}
          <div className="mt-1.5">
            <TranscriptBadge status={transcriptStatus} />
          </div>
        </div>
      </div>

      {error && (
        <div className="bg-red-900 text-red-300 rounded p-3 text-sm">{error}</div>
      )}

      {audioReady && (
        <div className="space-y-3">
          <audio
            ref={audioRef}
            src={audioStreamUrl(episodeId!)}
            controls
            className="w-full rounded"
            onCanPlay={() => {
              if (seekTo && audioRef.current && audioRef.current.currentTime < 1) {
                audioRef.current.currentTime = seekTo;
              }
            }}
          />
          <button
            onClick={handleSnip}
            disabled={snipping || transcriptStatus !== "done"}
            className={`w-full py-3 rounded-lg font-semibold text-lg transition-all ${
              snipFlash
                ? "bg-green-600 scale-95"
                : transcriptStatus === "done"
                  ? "bg-indigo-600 hover:bg-indigo-500 active:scale-95"
                  : "bg-gray-800 text-gray-500 cursor-not-allowed"
            }`}
          >
            {snipping ? "Creating snip…" : "✂️ Snip"}
          </button>
          {transcriptStatus !== "done" && transcriptStatus !== "error" && (
            <p className="text-center text-gray-500 text-xs">
              {transcriptStatus === "none" ? "Snip available once transcript is ready" : "Transcribing in background, snip available soon…"}
            </p>
          )}
        </div>
      )}

      {snips.length > 0 && (
        <div className="space-y-3">
          <h2 className="text-gray-400 font-medium text-sm uppercase tracking-wide">
            Snips ({snips.length})
          </h2>
          {snips.map(s => <SnipCard key={s.id} snip={s} />)}
        </div>
      )}
    </div>
  );
}
