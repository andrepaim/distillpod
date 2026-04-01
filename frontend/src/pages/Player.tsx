import { useEffect, useState, useRef } from "react";
import { useParams, useLocation, useNavigate } from "react-router-dom";
import {
  getEpisode, getChapters, listGists, createGist,
  type Gist, type ChaptersResult,
  type Episode,
} from "../api/client";
import { useAudio, readProgress, type PlayableEpisode } from "../context/AudioContext";
import { useQueue, type QueueItem } from "../stores/queueStore";

function stripHtml(html: string): string {
  return html.replace(/<[^>]*>/g, "").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&nbsp;/g, " ").replace(/&#39;/g, "'").replace(/&quot;/g, '"').trim();
}

function fmtTime(secs: number) {
  if (!isFinite(secs) || isNaN(secs)) return "0:00";
  const m = Math.floor(secs / 60);
  const s = Math.floor(secs % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
}

function fmtDuration(secs?: number) {
  if (!secs) return null;
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function fmtDate(iso?: string) {
  if (!iso) return null;
  try {
    return new Date(iso).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
  } catch { return null; }
}

// ─── Episode description (collapsible) ───────────────────────────────────────
function EpisodeDescription({ html }: { html: string }) {
  const [expanded, setExpanded] = useState(false);
  const plainLen = html.replace(/<[^>]*>/g, "").length;
  const isLong = plainLen > 300;
  return (
    <div className="bg-gray-900 rounded-2xl px-4 py-3 text-[11px] text-gray-400 leading-relaxed">
      <div
        className={`prose prose-invert max-w-none [&_*]:text-[11px] [&_*]:leading-relaxed ${!expanded && isLong ? "line-clamp-6" : ""}`}
        dangerouslySetInnerHTML={{ __html: html }}
      />
      {isLong && (
        <button
          onClick={() => setExpanded(e => !e)}
          className="mt-2 text-indigo-400 hover:text-indigo-300 font-medium"
        >
          {expanded ? "Show less" : "Show more"}
        </button>
      )}
    </div>
  );
}

// ─── Gist card ────────────────────────────────────────────────────────────────
function parseGistSummary(s: string | undefined): { quote?: string; insight?: string } | null {
  if (!s) return null;
  const stripped = s.replace(/^```(?:json)?\s*/i, "").replace(/\s*```\s*$/, "").trim();
  try {
    const p = JSON.parse(stripped);
    if (p.quote || p.insight) return p;
  } catch {}
  return { insight: s };
}

function GistCard({ gist, episodeTitle, podcastTitle }: {
  gist: Gist; episodeTitle?: string; podcastTitle?: string;
}) {
  const [copied, setCopied] = useState(false);
  const [shared, setShared] = useState(false);
  const ai = parseGistSummary(gist.summary);

  const buildShareText = () => {
    const lines: string[] = [];
    if (ai?.quote)   lines.push(`"${ai.quote}"`);
    if (ai?.insight) lines.push(`💡 ${ai.insight}`);
    else if (!ai)    lines.push(gist.text);
    lines.push("");
    if (episodeTitle || podcastTitle)
      lines.push(`🎙️ ${[episodeTitle, podcastTitle].filter(Boolean).join(" — ")}`);
    lines.push(`⏱ ${fmtTime(gist.start_seconds)} → ${fmtTime(gist.end_seconds)}`);
    lines.push("");
    lines.push("⚗️ Distilled with DistillPod");
    lines.push(`${window.location.origin}/player/${gist.episode_id}`);
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
    <div className="bg-gray-900 rounded-2xl p-4 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs text-gray-500 font-mono">
          {fmtTime(gist.start_seconds)} → {fmtTime(gist.end_seconds)}
        </span>
        <div className="flex gap-1">
          <button onClick={copy}
            className="text-xs font-semibold text-gray-300 hover:text-white bg-gray-700 hover:bg-gray-600 active:bg-gray-500 px-3 py-1 rounded-full transition-colors">
            {copied ? "✓ Copied" : "Copy"}
          </button>
          <button onClick={share}
            className="flex items-center gap-1 text-xs font-semibold bg-indigo-600 hover:bg-indigo-500 active:bg-indigo-700 text-white px-3 py-1 rounded-full transition-colors">
            {shared ? <span>✓ Shared</span> : (
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
      <div className="selectable">
        {ai ? (
          <>
            {ai.quote   && <p className="text-sm italic text-gray-100 border-l-2 border-indigo-500 pl-3 mb-1">"{ai.quote}"</p>}
            {ai.insight && <p className="text-indigo-300 text-sm leading-relaxed">{ai.insight}</p>}
          </>
        ) : (
          <p className="text-sm leading-relaxed text-gray-100">{gist.text}</p>
        )}
      </div>
    </div>
  );
}

// ─── Episode page ──────────────────────────────────────────────────────────────
export default function Player() {
  const { episodeId }  = useParams<{ episodeId: string }>();
  const location       = useLocation();
  const navigate       = useNavigate();
  const routeState     = location.state as (PlayableEpisode & { seekTo?: number }) | null;

  const {
    episode, isPlaying, currentTime, duration,
    audioReady, loadEpisode,
    playerExpanded, setPlayerExpanded,
  } = useAudio();
  const { addNext, addToEnd, remove } = useQueue();

  // Episode metadata for display (may come from routeState, AudioContext, or API)
  const [episodeInfo, setEpisodeInfo] = useState<PlayableEpisode | null>(routeState || null);
  const [chaptersData, setChaptersData] = useState<ChaptersResult | null>(null);
  const [gists, setGists]             = useState<Gist[]>([]);
  const [chaptersOpen, setChaptersOpen] = useState(false);
  const [queueFeedback, setQueueFeedback] = useState<"next" | "end" | null>(null);
  const [loading, setLoading]         = useState(false);
  const [error, setError]             = useState("");

  // Track previous playerExpanded to detect close → refresh gists
  const prevExpandedRef = useRef(playerExpanded);

  // The best available episode data for display.
  // Normalise: when episode comes from getEpisode() directly, it has image_url but not
  // podcast_image — promote image_url so the hero artwork always renders.
  const rawDisplay: PlayableEpisode | null = episode?.id === episodeId ? episode : episodeInfo;
  const displayEpisode: PlayableEpisode | null = rawDisplay
    ? { ...rawDisplay, podcast_image: rawDisplay.podcast_image ?? rawDisplay.image_url }
    : null;

  const isThisEpisode = episode?.id === episodeId && audioReady;
  const isThisPlaying = isThisEpisode && isPlaying;

  // ── Fetch episode metadata if not available ───────────────────────────────
  useEffect(() => {
    if (!episodeId) return;
    if (episodeInfo?.id === episodeId) return;
    if (episode?.id === episodeId) { setEpisodeInfo(episode); return; }
    getEpisode(episodeId)
      .then(ep => setEpisodeInfo(ep as PlayableEpisode))
      .catch(() => {});
  }, [episodeId]);

  // ── Fetch chapters + gists ────────────────────────────────────────────────
  useEffect(() => {
    if (!episodeId) return;
    getChapters(episodeId).then(setChaptersData).catch(() => {});
    listGists(episodeId).then(setGists).catch(() => {});
  }, [episodeId]);

  // ── Refresh gists when fullscreen player closes (user may have distilled) ─
  useEffect(() => {
    if (prevExpandedRef.current && !playerExpanded && episodeId) {
      listGists(episodeId).then(setGists).catch(() => {});
    }
    prevExpandedRef.current = playerExpanded;
  }, [playerExpanded, episodeId]);

  // ── Play handler ──────────────────────────────────────────────────────────
  const handlePlay = async () => {
    if (!episodeId) return;
    if (!isThisEpisode) {
      setLoading(true);
      setError("");
      try {
        // nav-state seekTo (e.g. from Gists) takes priority; fall back to saved progress
        let seekTo: number | undefined = routeState?.seekTo;
        if (seekTo == null) {
          const saved = readProgress()[episodeId];
          if (saved && saved.currentTime > 10) seekTo = saved.currentTime;
        }
        await loadEpisode(episodeId, displayEpisode || null, seekTo);
        // Remove from queue if present — playing directly shouldn't leave a duplicate in queue
        remove(episodeId);
      } catch (e: any) {
        setError(e.message);
        setLoading(false);
        return;
      }
      setLoading(false);
    }
    setPlayerExpanded(true);
  };

  // ── Queue helpers ─────────────────────────────────────────────────────────
  const makeQueueItem = (): QueueItem | null => {
    if (!displayEpisode) return null;
    return {
      episodeId: displayEpisode.id,
      title: displayEpisode.title,
      podcastTitle: displayEpisode.podcast_title || "",
      audioUrl: displayEpisode.audio_url,
      imageUrl: displayEpisode.podcast_image || displayEpisode.image_url,
      durationSeconds: displayEpisode.duration_seconds,
    };
  };

  const chapters = chaptersData?.chapters ?? [];

  return (
    <div className="pb-2">
      {/* ── Back button ────────────────────────────────────────────────── */}
      <button
        onClick={() => navigate(-1)}
        className="flex items-center gap-1.5 text-sm text-gray-400 hover:text-white transition-colors mb-4"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5}
          strokeLinecap="round" strokeLinejoin="round" className="w-4 h-4">
          <polyline points="15 18 9 12 15 6" />
        </svg>
        Back
      </button>

      {/* ── Hero: blurred artwork background ───────────────────────────── */}
      <div className="relative rounded-3xl overflow-hidden mb-5" style={{ minHeight: "220px" }}>
        {/* Blurred background */}
        <div className="absolute inset-0">
          {displayEpisode?.podcast_image ? (
            <img
              src={displayEpisode.podcast_image}
              className="w-full h-full object-cover scale-110"
              style={{ filter: "blur(40px) brightness(0.3) saturate(1.3)" }}
              alt=""
            />
          ) : (
            <div className="w-full h-full bg-gradient-to-br from-gray-800 to-gray-900" />
          )}
          <div className="absolute inset-0 bg-black/40" />
        </div>

        {/* Centered artwork */}
        <div className="relative flex flex-col items-center justify-center py-8 gap-3">
          {displayEpisode?.podcast_image ? (
            <img
              src={displayEpisode.podcast_image}
              alt=""
              className="w-32 h-32 rounded-2xl object-cover shadow-2xl ring-1 ring-white/10"
            />
          ) : (
            <div className="w-32 h-32 rounded-2xl bg-gray-700 flex items-center justify-center shadow-2xl">
              {!displayEpisode
                ? <span className="w-8 h-8 border-2 border-gray-500 border-t-indigo-400 rounded-full animate-spin inline-block" />
                : <span className="text-4xl">🎧</span>
              }
            </div>
          )}

          {/* Podcast title */}
          {displayEpisode?.podcast_title && (
            <p className="text-xs text-white/50 uppercase tracking-widest font-semibold">
              {displayEpisode.podcast_title}
            </p>
          )}
        </div>
      </div>

      {/* ── Episode title + meta ────────────────────────────────────────── */}
      <div className="mb-4 px-1">
        {displayEpisode?.title ? (
          <h1 className="text-lg font-bold leading-snug mb-1">{displayEpisode.title}</h1>
        ) : (
          <div className="h-5 bg-gray-800 rounded animate-pulse w-3/4 mb-2" />
        )}
        <div className="flex items-center gap-2 text-xs text-gray-500">
          {fmtDuration(displayEpisode?.duration_seconds) && (
            <span>{fmtDuration(displayEpisode?.duration_seconds)}</span>
          )}
          {fmtDate(displayEpisode?.published_at) && (
            <>
              {fmtDuration(displayEpisode?.duration_seconds) && <span>·</span>}
              <span>{fmtDate(displayEpisode?.published_at)}</span>
            </>
          )}
        </div>
      </div>

      {/* ── Error ──────────────────────────────────────────────────────── */}
      {error && (
        <div className="bg-red-900/60 border border-red-700/40 text-red-300 rounded-2xl px-4 py-3 text-sm mb-4">
          {error}
        </div>
      )}

      {/* ── Action row ─────────────────────────────────────────────────── */}
      <div className="grid grid-cols-4 gap-2 mb-4">
        {/* Play / open player */}
        <button
          onClick={handlePlay}
          disabled={loading}
          className={`col-span-2 flex items-center justify-center gap-2.5 py-3.5 rounded-2xl font-semibold text-sm transition-all active:scale-95 ${
            isThisPlaying
              ? "bg-indigo-600 hover:bg-indigo-500 text-white"
              : "bg-indigo-600 hover:bg-indigo-500 text-white"
          }`}
        >
          {loading ? (
            <span className="w-4 h-4 border-2 border-white/40 border-t-white rounded-full animate-spin inline-block" />
          ) : isThisPlaying ? (
            <>
              <svg viewBox="0 0 24 24" fill="white" className="w-4 h-4 flex-shrink-0">
                <rect x="6" y="4" width="4" height="16" rx="1" />
                <rect x="14" y="4" width="4" height="16" rx="1" />
              </svg>
              Now Playing
            </>
          ) : isThisEpisode ? (
            <>
              <svg viewBox="0 0 24 24" fill="white" className="w-4 h-4 flex-shrink-0 translate-x-0.5">
                <polygon points="5 3 19 12 5 21 5 3" />
              </svg>
              Resume
            </>
          ) : (
            <>
              <svg viewBox="0 0 24 24" fill="white" className="w-4 h-4 flex-shrink-0 translate-x-0.5">
                <polygon points="5 3 19 12 5 21 5 3" />
              </svg>
              Play
            </>
          )}
        </button>

        {/* Add next */}
        <button
          onClick={() => {
            const item = makeQueueItem();
            if (!item) return;
            addNext(item);
            setQueueFeedback("next");
            setTimeout(() => setQueueFeedback(null), 1400);
          }}
          title="Play next"
          className={`flex flex-col items-center justify-center gap-1 py-3 rounded-2xl text-xs font-medium transition-all active:scale-95 ${
            queueFeedback === "next"
              ? "bg-green-600/20 text-green-400"
              : "bg-gray-800 hover:bg-gray-700 text-gray-400"
          }`}
        >
          {queueFeedback === "next" ? (
            <span className="text-base">✓</span>
          ) : (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}
              strokeLinecap="round" strokeLinejoin="round" className="w-4 h-4">
              <polygon points="5 4 15 12 5 20 5 4" />
              <line x1="19" y1="5" x2="19" y2="19" />
            </svg>
          )}
          <span>{queueFeedback === "next" ? "Added" : "Next"}</span>
        </button>

        {/* Add to end */}
        <button
          onClick={() => {
            const item = makeQueueItem();
            if (!item) return;
            addToEnd(item);
            setQueueFeedback("end");
            setTimeout(() => setQueueFeedback(null), 1400);
          }}
          title="Add to queue"
          className={`flex flex-col items-center justify-center gap-1 py-3 rounded-2xl text-xs font-medium transition-all active:scale-95 ${
            queueFeedback === "end"
              ? "bg-green-600/20 text-green-400"
              : "bg-gray-800 hover:bg-gray-700 text-gray-400"
          }`}
        >
          {queueFeedback === "end" ? (
            <span className="text-base">✓</span>
          ) : (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}
              strokeLinecap="round" strokeLinejoin="round" className="w-4 h-4">
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
          )}
          <span>{queueFeedback === "end" ? "Added" : "Queue"}</span>
        </button>
      </div>

      {/* Chat button (only when episode is loaded and transcript exists) */}
      <button
        onClick={() => navigate(`/player/${episodeId}/chat`, {
          state: { episodeTitle: displayEpisode?.title }
        })}
        className="w-full py-3 rounded-2xl font-semibold text-sm bg-gray-800 hover:bg-gray-700 active:scale-95 transition-all flex items-center justify-center gap-2 mb-4"
        style={{ color: "#FFD700" }}
      >
        <span>💬</span> Chat about this episode
      </button>

      {/* ── Inline progress (if this episode is active) ─────────────── */}
      {isThisEpisode && duration > 0 && (
        <button
          onClick={() => setPlayerExpanded(true)}
          className="w-full mb-5 group"
        >
          <div className="relative h-1 rounded-full bg-gray-800 overflow-hidden">
            <div
              className="h-full bg-indigo-500 rounded-full transition-[width] duration-500"
              style={{ width: `${(currentTime / duration) * 100}%` }}
            />
          </div>
          <div className="flex justify-between text-xs text-gray-600 mt-1 px-0.5 group-hover:text-gray-400 transition-colors">
            <span>{fmtTime(currentTime)}</span>
            <span className="text-indigo-400/60 group-hover:text-indigo-400 transition-colors">
              Open player ↑
            </span>
            <span>{fmtTime(duration)}</span>
          </div>
        </button>
      )}

      {/* ── Scrollable content ──────────────────────────────────────────── */}
      <div className="space-y-4">

        {/* AI Summary (or description fallback) */}
        {chaptersData?.summary ? (
          <div className="bg-gray-900 rounded-2xl px-4 py-4">
            <p className="text-xs font-semibold text-indigo-400 uppercase tracking-wider mb-2">
              ✦ AI Summary
            </p>
            <p className="text-sm text-gray-200 leading-relaxed selectable">
              {chaptersData.summary}
            </p>
          </div>
        ) : displayEpisode?.description ? (
          <div className="bg-gray-900 rounded-2xl px-4 py-4">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
              About this episode
            </p>
            <EpisodeDescription html={displayEpisode.description} />
          </div>
        ) : null}

        {/* Chapters */}
        {chapters.length > 0 && (
          <div className="bg-gray-900 rounded-2xl overflow-hidden">
            <button
              onClick={() => setChaptersOpen(o => !o)}
              className="w-full flex items-center justify-between px-4 py-3.5 hover:bg-gray-800 transition-colors"
            >
              <span className="text-xs font-semibold text-gray-300 uppercase tracking-wider">
                Chapters ({chapters.length})
              </span>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}
                strokeLinecap="round" strokeLinejoin="round"
                className={`w-4 h-4 text-gray-500 transition-transform ${chaptersOpen ? "rotate-180" : ""}`}>
                <polyline points="6 9 12 15 18 9" />
              </svg>
            </button>
            {chaptersOpen && (
              <div className="divide-y divide-gray-800/60">
                {chapters.map((ch, i) => (
                  <button
                    key={i}
                    onClick={async () => {
                      if (!episodeId) return;
                      if (!isThisEpisode) {
                        await loadEpisode(episodeId, displayEpisode || null, ch.start_time);
                      } else {
                        const audio = document.querySelector("audio");
                        if (audio) audio.currentTime = ch.start_time;
                      }
                      setPlayerExpanded(true);
                    }}
                    className="w-full flex items-center gap-3 px-4 py-2.5 text-left hover:bg-gray-800/50 transition-colors"
                  >
                    <span className="text-xs font-mono text-gray-500 w-10 flex-shrink-0">
                      {fmtTime(ch.start_time)}
                    </span>
                    <span className="text-sm flex-1 text-gray-300">{ch.title}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Description (if AI summary exists, show description below collapsed) */}
        {chaptersData?.summary && displayEpisode?.description && (
          <div className="bg-gray-900 rounded-2xl overflow-hidden">
            <details>
              <summary className="px-4 py-3.5 text-xs font-semibold text-gray-500 uppercase tracking-wider cursor-pointer hover:bg-gray-800 transition-colors list-none flex items-center justify-between">
                <span>Episode description</span>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}
                  strokeLinecap="round" strokeLinejoin="round" className="w-4 h-4">
                  <polyline points="6 9 12 15 18 9" />
                </svg>
              </summary>
              <div className="px-4 pb-3 pt-1">
                <EpisodeDescription html={displayEpisode.description} />
              </div>
            </details>
          </div>
        )}

        {/* Distills */}
        {gists.length > 0 && (
          <div className="space-y-3">
            <h2 className="text-gray-500 font-semibold text-xs uppercase tracking-wider px-1">
              ⚗️ Distillations ({gists.length})
            </h2>
            {gists.map(g => (
              <GistCard
                key={g.id}
                gist={g}
                episodeTitle={displayEpisode?.title}
                podcastTitle={displayEpisode?.podcast_title}
              />
            ))}
          </div>
        )}

        {/* Empty distills nudge */}
        {gists.length === 0 && isThisEpisode && (
          <div className="text-center py-4 text-gray-600 text-xs">
            Open the player and tap ⚗️ to distill moments from this episode
          </div>
        )}
      </div>
    </div>
  );
}
