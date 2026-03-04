import { useEffect, useState } from "react";
import { useParams, useLocation, useNavigate } from "react-router-dom";
import { createGist, listGists, getTranscriptStatus, getAdFreeStatus, adFreeAudioUrl, Gist, Episode, AdFreeStatus } from "../api/client";
import { useAudio, readProgress, type PlayableEpisode } from "../context/AudioContext";
import { useQueue, type QueueItem } from "../stores/queueStore";

function stripHtml(html: string): string {
  return html.replace(/<[^>]*>/g, "").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&nbsp;/g, " ").replace(/&#39;/g, "'").replace(/&quot;/g, '"').trim();
}

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
            ? "⚗️  Distill this moment"
            : "⏳  Waiting for transcript…"}
      </button>

    </div>
  );
}

// ─── Gist card ────────────────────────────────────────────────────────────────
function parseGistSummary(s: string | undefined): { quote?: string; insight?: string } | null {
  if (!s) return null;
  // Strip markdown code fences: ```json ... ``` or ``` ... ```
  const stripped = s.replace(/^```(?:json)?\s*/i, "").replace(/\s*```\s*$/, "").trim();
  try {
    const p = JSON.parse(stripped);
    if (p.quote || p.insight) return p;
  } catch {}
  return { insight: s };
}

interface GistCardProps {
  gist: Gist;
  episodeTitle?: string;
  podcastTitle?: string;
}

function GistCard({ gist, episodeTitle, podcastTitle }: GistCardProps) {
  const [copied, setCopied]   = useState(false);
  const [shared, setShared]   = useState(false);
  const ai = parseGistSummary(gist.summary);

  const buildShareText = () => {
    const lines: string[] = [];
    if (ai?.quote)   lines.push(`"${ai.quote}"`);
    if (ai?.insight) lines.push(`💡 ${ai.insight}`);
    else if (!ai)    lines.push(gist.text);
    lines.push("");
    if (episodeTitle || podcastTitle) {
      lines.push(`🎙️ ${[episodeTitle, podcastTitle].filter(Boolean).join(" — ")}`);
      lines.push(`⏱ ${fmtTime(gist.start_seconds)} → ${fmtTime(gist.end_seconds)}`);
      lines.push("");
    }
    lines.push("⚗️ Distilled with DistillPod");
    lines.push(`https://distillpod.duckdns.org/player/${gist.episode_id}`);
    lines.push("");
    lines.push("Built for one. Shared by accident.");
    return lines.join("\n");
  };

  const copy = async () => {
    await navigator.clipboard.writeText(buildShareText());
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const share = async () => {
    const text = buildShareText();
    if (navigator.share) {
      try {
        await navigator.share({ text });
        setShared(true);
        setTimeout(() => setShared(false), 2000);
      } catch (e: any) {
        if (e?.name !== "AbortError") {
          // User cancelled — silently ignore. Any other error → fall back to copy
          await navigator.clipboard.writeText(text);
          setCopied(true);
          setTimeout(() => setCopied(false), 2000);
        }
      }
    } else {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <div className="bg-gray-900 rounded-xl p-4 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs text-gray-500 font-mono">
          {fmtTime(gist.start_seconds)} → {fmtTime(gist.end_seconds)}
        </span>
        <div className="flex gap-1">
          <button onClick={copy} className="text-xs font-semibold text-gray-300 hover:text-white bg-gray-700 hover:bg-gray-600 active:bg-gray-500 px-3 py-1 rounded-full transition-colors">
            {copied ? "✓ Copied" : "Copy"}
          </button>
          <button onClick={share} className="flex items-center gap-1 text-xs font-semibold bg-indigo-600 hover:bg-indigo-500 active:bg-indigo-700 text-white px-3 py-1 rounded-full transition-colors">
            {shared ? (
              <span>✓ Shared</span>
            ) : (
              <>
                <svg xmlns="http://www.w3.org/2000/svg" className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round">
                  <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8" />
                  <polyline points="16 6 12 2 8 6" />
                  <line x1="12" y1="2" x2="12" y2="15" />
                </svg>
                Share
              </>
            )}
          </button>
        </div>
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
function EpisodeDescription({ html }: { html: string }) {
  const [expanded, setExpanded] = useState(false);
  // Estimate "short" by plain text length
  const plainLen = html.replace(/<[^>]*>/g, "").length;
  const isLong = plainLen > 300;
  return (
    <div className="bg-gray-900 rounded-xl px-4 py-3 text-[11px] text-gray-400 leading-relaxed">
      <div
        className={`prose prose-invert max-w-none [&_*]:text-[11px] [&_*]:leading-relaxed ${!expanded && isLong ? "line-clamp-6" : ""}`}
        dangerouslySetInnerHTML={{ __html: html }}
      />
      {isLong && (
        <button
          onClick={() => setExpanded(e => !e)}
          className="mt-1.5 text-indigo-400 hover:text-indigo-300 font-medium"
        >
          {expanded ? "Show less" : "Show more"}
        </button>
      )}
    </div>
  );
}

export default function Player() {
  const { episodeId } = useParams<{ episodeId: string }>();
  const location      = useLocation();
  const navigate      = useNavigate();
  const routeState    = location.state as (PlayableEpisode & { seekTo?: number }) | null;

  const { loadEpisode, audioReady, audioRef, episode } = useAudio();

  // seekTo comes only from current navigation state (gist → player link)
  const seekTo = routeState?.seekTo;

  const [transcriptStatus, setTranscriptStatus] = useState("none");
  const [gists,    setGists]    = useState<Gist[]>([]);
  const [gisting,    setGisting]    = useState(false);
  const [gistFlash,  setGistFlash]  = useState(false);
  const [resumedFrom,setResumedFrom]= useState<number | null>(null);
  const [error,      setError]      = useState("");
  const [queueFeedback, setQueueFeedback] = useState<"next" | "end" | null>(null);
  const [adFreeStatus, setAdFreeStatus] = useState<AdFreeStatus | null>(null);
  const [useAdFree, setUseAdFree] = useState(false);
  const { addNext, addToEnd } = useQueue();

  // Display episode: prefer what's already in context (avoids blank header flash on same episode)
  const displayEpisode = episode?.id === episodeId ? episode : routeState;

  // Load episode into audio context
  useEffect(() => {
    if (!episodeId) return;
    const ep: PlayableEpisode | null = routeState
      ? { ...routeState, podcast_image: routeState.podcast_image, podcast_title: routeState.podcast_title }
      : null;

    // If no explicit seekTo from nav state, check for saved progress
    let resolvedSeekTo = seekTo;
    if (resolvedSeekTo == null) {
      const saved = readProgress()[episodeId];
      if (saved && saved.currentTime > 10) {
        resolvedSeekTo = saved.currentTime;
        setResumedFrom(resolvedSeekTo);
      }
    }

    loadEpisode(episodeId, ep, resolvedSeekTo).catch(e => setError(e.message));
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

  // Fetch ad-free status
  useEffect(() => {
    if (!episodeId) return;
    getAdFreeStatus(episodeId).then(setAdFreeStatus).catch(() => {});
  }, [episodeId]);

  // Switch audio source when ad-free toggle changes
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio || !episodeId || !episode) return;
    const newSrc = useAdFree && adFreeStatus?.has_adfree
      ? adFreeAudioUrl(episodeId)
      : `/player/audio/${episodeId}`;
    if (audio.src.endsWith(newSrc)) return;
    const wasPlaying = !audio.paused;
    audio.src = newSrc;
    audio.load();
    if (wasPlaying) audio.play().catch(() => {});
  }, [useAdFree]);

  const handleGist = async () => {
    if (!audioRef.current || !episodeId) return;
    setGisting(true);
    try {
      const gist = await createGist(episodeId, audioRef.current.currentTime);
      setGists(prev => [gist, ...prev]);
      setGistFlash(true);
      setTimeout(() => setGistFlash(false), 600);
    } catch (e: any) { setError(e.message); }
    finally { setGisting(false); }
  };

  return (
    <div>
      {/* ── STICKY: header + player ───────────────────────────────── */}
      <div className="sticky top-0 z-10 -mx-4 px-4 pb-3 pt-1 bg-gray-950 border-b border-gray-800 space-y-4">

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
            {seekTo != null && (
              <div className="text-xs text-indigo-400 mt-1">▶ From {fmtTime(seekTo)}</div>
            )}
            {seekTo == null && resumedFrom != null && (
              <div className="text-xs text-indigo-400 mt-1">⏩ Resuming from {fmtTime(resumedFrom)}</div>
            )}
            <div className="mt-1.5"><TranscriptBadge status={transcriptStatus} /></div>
          </div>
        </div>

        {error && <div className="bg-red-900 text-red-300 rounded-xl p-3 text-sm">{error}</div>}

        {/* Player widget */}
        {audioReady && episodeId && episode?.id === episodeId && (
          <>
            <PlayerWidget
              gists={gists}
              transcriptStatus={transcriptStatus}
              onGist={handleGist}
              gisting={gisting}
              gistFlash={gistFlash}
            />
            {adFreeStatus?.has_adfree && (
              <div className='flex gap-2 items-center justify-center mt-2'>
                <span className='text-xs text-gray-400'>{adFreeStatus.ads_count} ad{adFreeStatus.ads_count !== 1 ? 's' : ''} detected</span>
                <button
                  onClick={() => setUseAdFree(false)}
                  className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${!useAdFree ? 'text-gray-900' : 'bg-gray-700 text-gray-300'}`}
                  style={!useAdFree ? {background: '#FFD700'} : {}}
                >Original</button>
                <button
                  onClick={() => setUseAdFree(true)}
                  className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${useAdFree ? 'text-gray-900' : 'bg-gray-700 text-gray-300'}`}
                  style={useAdFree ? {background: '#FFD700'} : {}}
                >Ad-free</button>
              </div>
            )}
            {transcriptStatus === "done" && (
              <button
                onClick={() => navigate(`/player/${episodeId}/chat`, { state: { episodeTitle: displayEpisode?.title } })}
                className="w-full py-3 rounded-xl font-semibold text-base bg-gray-800 hover:bg-gray-700 active:scale-95 transition-all flex items-center justify-center gap-2"
                style={{ color: "#FFD700" }}
              >
                <span>💬</span> Chat about this episode
              </button>
            )}

            {/* Queue actions */}
            {displayEpisode && (
              <div className="flex gap-2">
                <button
                  onClick={() => {
                    const item: QueueItem = {
                      episodeId: displayEpisode.id,
                      title: displayEpisode.title,
                      podcastTitle: displayEpisode.podcast_title || "",
                      audioUrl: displayEpisode.audio_url,
                      imageUrl: displayEpisode.podcast_image || displayEpisode.image_url,
                      durationSeconds: displayEpisode.duration_seconds,
                    };
                    addNext(item);
                    setQueueFeedback("next");
                    setTimeout(() => setQueueFeedback(null), 1200);
                  }}
                  className={`flex-1 py-2.5 rounded-xl font-semibold text-sm transition-all active:scale-95 ${
                    queueFeedback === "next"
                      ? "bg-green-600 text-white"
                      : "bg-gray-800 text-gray-300 hover:bg-gray-700"
                  }`}
                >
                  {queueFeedback === "next" ? "✓ Added!" : "⏭ Play next"}
                </button>
                <button
                  onClick={() => {
                    const item: QueueItem = {
                      episodeId: displayEpisode.id,
                      title: displayEpisode.title,
                      podcastTitle: displayEpisode.podcast_title || "",
                      audioUrl: displayEpisode.audio_url,
                      imageUrl: displayEpisode.podcast_image || displayEpisode.image_url,
                      durationSeconds: displayEpisode.duration_seconds,
                    };
                    addToEnd(item);
                    setQueueFeedback("end");
                    setTimeout(() => setQueueFeedback(null), 1200);
                  }}
                  className={`flex-1 py-2.5 rounded-xl font-semibold text-sm transition-all active:scale-95 ${
                    queueFeedback === "end"
                      ? "bg-green-600 text-white"
                      : "bg-gray-800 text-gray-300 hover:bg-gray-700"
                  }`}
                >
                  {queueFeedback === "end" ? "✓ Added!" : "+ Add to queue"}
                </button>
              </div>
            )}
          </>
        )}

        {(!audioReady || episode?.id !== episodeId) && !error && (
          <div className="bg-gray-900 rounded-2xl p-5 flex items-center justify-center h-44">
            <div className="text-gray-500 text-sm flex items-center gap-2">
              <span className="w-4 h-4 border-2 border-gray-500 border-t-indigo-400 rounded-full animate-spin inline-block" />
              Loading episode…
            </div>
          </div>
        )}
      </div>

      {/* ── SCROLLABLE: description + gists ──────────────────────── */}
      <div className="space-y-4 mt-4">

        {/* Episode description */}
        {displayEpisode?.description && (
          <EpisodeDescription html={displayEpisode.description} />
        )}

        {/* Gists list */}
        {gists.length > 0 && (
          <div className="space-y-3">
            <h2 className="text-gray-400 font-medium text-xs uppercase tracking-wide">
              Distillations ({gists.length})
            </h2>
            {gists.map(s => (
              <GistCard
                key={s.id}
                gist={s}
                episodeTitle={displayEpisode?.title}
                podcastTitle={displayEpisode?.podcast_title}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
