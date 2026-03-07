import { useEffect, useState, useRef } from "react";
import { useAudio } from "../context/AudioContext";
import {
  getTranscriptStatus, getAdFreeStatus, getChapters, createGist,
  adFreeAudioUrl,
  type AdFreeStatus, type ChaptersResult,
} from "../api/client";

// ─── Constants ─────────────────────────────────────────────────────────────────
const SPEEDS = [1, 1.5, 2, 0.5];

function fmtTime(secs: number) {
  if (!isFinite(secs) || isNaN(secs)) return "0:00";
  const m = Math.floor(secs / 60);
  const s = Math.floor(secs % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
}

// ─── Transcript badge ──────────────────────────────────────────────────────────
function TranscriptBadge({ status }: { status: string }) {
  if (status === "done") return (
    <span className="inline-flex items-center gap-1 text-xs bg-white/10 text-green-300 px-2.5 py-1 rounded-full">
      ✓ Transcript ready
    </span>
  );
  if (status === "error") return (
    <span className="inline-flex items-center gap-1 text-xs bg-white/10 text-red-300 px-2.5 py-1 rounded-full">
      ✗ Transcript error
    </span>
  );
  if (status === "processing" || status === "queued") return (
    <span className="inline-flex items-center gap-2 text-xs bg-white/10 text-yellow-300 px-2.5 py-1 rounded-full">
      <span className="w-1.5 h-1.5 bg-yellow-400 rounded-full animate-pulse inline-block" />
      Transcribing…
    </span>
  );
  return null;
}

// ─── Main component ────────────────────────────────────────────────────────────
export default function FullscreenPlayer() {
  const {
    episode, audioRef, isPlaying, currentTime, duration,
    audioReady, togglePlay, skipBy, setRate,
    playerExpanded, setPlayerExpanded,
  } = useAudio();

  // Playback controls state
  const [speedIdx, setSpeedIdx]           = useState(0);
  // Episode-specific data
  const [transcriptStatus, setTranscriptStatus] = useState("none");
  const [adFreeStatus, setAdFreeStatus]   = useState<AdFreeStatus | null>(null);
  const [useAdFree, setUseAdFree]         = useState(false);
  const [chaptersData, setChaptersData]   = useState<ChaptersResult | null>(null);
  const [chaptersOpen, setChaptersOpen]   = useState(false);
  // Gist state
  const [gisting, setGisting]             = useState(false);
  const [gistFlash, setGistFlash]         = useState(false);
  const [gistCreated, setGistCreated]     = useState(false);
  // Error
  const [error, setError]                 = useState("");
  // Swipe gesture
  const touchStartY                       = useRef(0);
  const pollRef                           = useRef<ReturnType<typeof setInterval> | null>(null);

  const chapters           = chaptersData?.chapters ?? [];
  const currentChapterIndex = chapters.reduce((best, ch, i) =>
    ch.start_time <= currentTime ? i : best, -1);
  const currentChapter = currentChapterIndex >= 0 ? chapters[currentChapterIndex] : null;
  const progress       = duration > 0 ? (currentTime / duration) * 100 : 0;
  const remaining      = duration - currentTime;
  const speed          = SPEEDS[speedIdx];

  // ── History integration: back gesture closes the player ─────────────────────
  // Push a sentinel history entry when the player opens so the browser back
  // gesture / button pops it rather than navigating away from the episode page.
  useEffect(() => {
    if (playerExpanded) {
      window.history.pushState({ distillpodPlayer: true }, '');
    }
  }, [playerExpanded]);

  useEffect(() => {
    const onPopState = () => {
      if (playerExpanded) {
        setPlayerExpanded(false);
        setChaptersOpen(false);
      }
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [playerExpanded, setPlayerExpanded]);

  // ── Fetch data when episode changes ─────────────────────────────────────────
  useEffect(() => {
    if (!episode?.id) return;
    const id = episode.id;

    setTranscriptStatus("none");
    setAdFreeStatus(null);
    setChaptersData(null);
    setChaptersOpen(false);
    setError("");
    setGistCreated(false);

    getTranscriptStatus(id).then(({ status }) => setTranscriptStatus(status)).catch(() => {});
    getAdFreeStatus(id).then(setAdFreeStatus).catch(() => {});
    getChapters(id).then(setChaptersData).catch(() => {});
  }, [episode?.id]);

  // ── Poll transcript until done ───────────────────────────────────────────────
  useEffect(() => {
    if (pollRef.current) clearInterval(pollRef.current);
    if (!episode?.id || transcriptStatus === "done" || transcriptStatus === "error") return;

    pollRef.current = setInterval(async () => {
      if (!episode?.id) return;
      const { status } = await getTranscriptStatus(episode.id);
      setTranscriptStatus(status);
      if (status === "done" || status === "error") {
        if (pollRef.current) clearInterval(pollRef.current);
      }
    }, 5000);
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
  }, [episode?.id, transcriptStatus]);

  // ── Ad-free source swap ──────────────────────────────────────────────────────
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio || !episode?.id || !adFreeStatus?.has_adfree) return;
    const newSrc = useAdFree
      ? adFreeAudioUrl(episode.id)
      : `/player/audio/${episode.id}`;
    if (audio.src.endsWith(newSrc)) return;
    const wasPlaying = !audio.paused;
    const savedTime  = audio.currentTime;
    audio.src = newSrc;
    audio.load();
    audio.currentTime = savedTime;
    if (wasPlaying) audio.play().catch(() => {});
  }, [useAdFree]);

  // ── Handlers ─────────────────────────────────────────────────────────────────
  const cycleSpeed = () => {
    const next = (speedIdx + 1) % SPEEDS.length;
    setSpeedIdx(next);
    setRate(SPEEDS[next]);
  };

  const handleSeek = (e: React.ChangeEvent<HTMLInputElement>) => {
    const audio = audioRef.current;
    if (audio) audio.currentTime = Number(e.target.value);
  };

  const skipToNextChapter = () => {
    const audio = audioRef.current;
    if (!audio || currentChapterIndex < 0) return;
    const next = chapters[currentChapterIndex + 1];
    if (next) audio.currentTime = next.start_time;
  };

  const handleGist = async () => {
    if (!audioRef.current || !episode?.id) return;
    setGisting(true);
    setError("");
    try {
      await createGist(episode.id, audioRef.current.currentTime);
      setGistFlash(true);
      setGistCreated(true);
      setTimeout(() => setGistFlash(false), 800);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setGisting(false);
    }
  };

  const handleClose = () => {
    setChaptersOpen(false);
    setPlayerExpanded(false);
    // Pop the sentinel history entry we pushed on open, so the history stack
    // stays clean after a manual close (handle tap, swipe down, etc.)
    if (window.history.state?.distillpodPlayer) {
      window.history.back();
    }
  };

  // ── Swipe down to close ───────────────────────────────────────────────────────
  const handleTouchStart = (e: React.TouchEvent) => {
    touchStartY.current = e.touches[0].clientY;
  };
  const handleTouchEnd = (e: React.TouchEvent) => {
    const delta = e.changedTouches[0].clientY - touchStartY.current;
    if (delta > 72) handleClose();
  };

  if (!episode || !audioReady) return null;

  return (
    /* Main sheet — fixed full-screen */
    <div
      className={`fixed inset-0 z-[60] flex flex-col overflow-hidden transition-transform duration-300 ease-out ${
        playerExpanded ? "translate-y-0" : "translate-y-full pointer-events-none"
      }`}
    >
      {/* ── Blurred artwork background ── */}
      <div className="absolute inset-0 overflow-hidden">
        {episode.podcast_image ? (
          <img
            src={episode.podcast_image}
            className="absolute inset-0 w-full h-full object-cover scale-110"
            style={{ filter: "blur(48px) brightness(0.25) saturate(1.4)" }}
            alt=""
          />
        ) : (
          <div className="absolute inset-0 bg-gray-950" />
        )}
        <div className="absolute inset-0 bg-black/50" />
      </div>

      {/* ── Scrollable player content ── */}
      <div
        className="relative flex flex-col h-full overflow-y-auto"
        style={{ paddingTop: "max(env(safe-area-inset-top), 12px)", paddingBottom: "max(env(safe-area-inset-bottom), 24px)" }}
      >
          {/* Swipe handle + chevron */}
          <div
            className="flex flex-col items-center gap-1.5 pt-1 pb-4 cursor-pointer select-none"
            onTouchStart={handleTouchStart}
            onTouchEnd={handleTouchEnd}
            onClick={handleClose}
          >
            <div className="w-10 h-1 bg-white/25 rounded-full" />
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5}
              strokeLinecap="round" strokeLinejoin="round"
              className="w-4 h-4 text-white/30">
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </div>

          <div className="flex flex-col flex-1 px-6 gap-4">
            {/* ── Large artwork ── */}
            <div className="flex justify-center">
              {episode.podcast_image ? (
                <img
                  src={episode.podcast_image}
                  alt=""
                  className={`rounded-2xl object-cover shadow-2xl transition-all duration-500 ${
                    isPlaying
                      ? "w-64 h-64 shadow-indigo-900/30"
                      : "w-52 h-52 opacity-80"
                  }`}
                />
              ) : (
                <div className={`rounded-2xl bg-gray-800 flex items-center justify-center shadow-2xl transition-all duration-500 ${
                  isPlaying ? "w-64 h-64" : "w-52 h-52"
                }`}>
                  <span className="text-6xl">🎧</span>
                </div>
              )}
            </div>

            {/* ── Episode info ── */}
            <div className="text-center px-2">
              {episode.podcast_title && (
                <p className="text-xs text-white/40 uppercase tracking-widest font-semibold mb-1">
                  {episode.podcast_title}
                </p>
              )}
              <h2 className="text-base font-bold text-white leading-snug line-clamp-2">
                {episode.title}
              </h2>
            </div>

            {/* ── Error ── */}
            {error && (
              <div className="bg-red-500/20 border border-red-500/30 text-red-300 rounded-xl px-4 py-2 text-xs text-center">
                {error}
              </div>
            )}

            {/* ── Progress scrubber ── */}
            <div className="space-y-1.5">
              <div className="relative h-1 group cursor-pointer">
                {/* Track */}
                <div className="absolute inset-0 rounded-full bg-white/20" />
                {/* Fill */}
                <div
                  className="absolute left-0 top-0 h-full rounded-full bg-white pointer-events-none"
                  style={{ width: `${progress}%` }}
                />
                {/* Thumb (visible on hover/active) */}
                <div
                  className="absolute top-1/2 -translate-y-1/2 w-3 h-3 bg-white rounded-full shadow pointer-events-none opacity-0 group-hover:opacity-100 transition-opacity"
                  style={{ left: `calc(${progress}% - 6px)` }}
                />
                <input
                  type="range" min={0} max={duration || 100} step={1} value={currentTime}
                  onChange={handleSeek}
                  className="absolute inset-0 w-full opacity-0 cursor-pointer h-full"
                />
              </div>
              <div className="flex justify-between text-xs text-white/40 font-mono px-0.5">
                <span>{fmtTime(currentTime)}</span>
                <span>−{fmtTime(remaining)}</span>
              </div>
            </div>

            {/* ── Playback controls ── */}
            <div className="flex items-center justify-between px-2">
              {/* Speed */}
              <button
                onClick={cycleSpeed}
                className="w-10 h-10 flex items-center justify-center text-white/60 hover:text-white text-sm font-bold rounded-full hover:bg-white/10 transition-colors"
              >
                {speed === 1 ? "1×" : `${speed}×`}
              </button>

              {/* Skip back 10s */}
              <button
                onClick={() => skipBy(-10)}
                className="w-12 h-12 flex items-center justify-center text-white/70 hover:text-white rounded-full hover:bg-white/10 transition-colors"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
                  strokeLinecap="round" strokeLinejoin="round" className="w-8 h-8">
                  <path d="M2.5 12a9.5 9.5 0 1 1 2.3 6.2" />
                  <path d="M2.5 7v5h5" />
                  <text x="7.5" y="15" fontSize="6" fill="currentColor" stroke="none" fontWeight="bold">10</text>
                </svg>
              </button>

              {/* Play / Pause */}
              <button
                onClick={togglePlay}
                className="w-20 h-20 bg-white hover:bg-white/90 active:scale-95 rounded-full flex items-center justify-center transition-all shadow-2xl"
              >
                {isPlaying ? (
                  <svg viewBox="0 0 24 24" fill="#111827" className="w-8 h-8">
                    <rect x="6" y="4" width="4" height="16" rx="1" />
                    <rect x="14" y="4" width="4" height="16" rx="1" />
                  </svg>
                ) : (
                  <svg viewBox="0 0 24 24" fill="#111827" className="w-8 h-8 translate-x-0.5">
                    <polygon points="5 3 19 12 5 21 5 3" />
                  </svg>
                )}
              </button>

              {/* Skip forward 30s */}
              <button
                onClick={() => skipBy(30)}
                className="w-12 h-12 flex items-center justify-center text-white/70 hover:text-white rounded-full hover:bg-white/10 transition-colors"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
                  strokeLinecap="round" strokeLinejoin="round" className="w-8 h-8">
                  <path d="M21.5 12a9.5 9.5 0 1 0-2.3 6.2" />
                  <path d="M21.5 7v5h-5" />
                  <text x="6.5" y="15" fontSize="6" fill="currentColor" stroke="none" fontWeight="bold">30</text>
                </svg>
              </button>

              {/* Gist flash indicator (symmetry slot) */}
              <div className="w-10 h-10 flex items-center justify-center">
                {gistCreated && (
                  <span className="text-lg" title="Distill saved">⚗️</span>
                )}
              </div>
            </div>

            {/* ── Distill button ── */}
            <button
              onClick={handleGist}
              disabled={gisting || transcriptStatus !== "done"}
              className={`w-full py-3.5 rounded-2xl font-semibold text-sm transition-all active:scale-[0.98] ${
                gistFlash
                  ? "bg-green-500/70 text-white scale-[0.98]"
                  : transcriptStatus === "done"
                    ? "bg-white/15 hover:bg-white/20 text-white"
                    : "bg-white/5 text-white/30 cursor-not-allowed"
              }`}
            >
              {gisting
                ? "Distilling…"
                : transcriptStatus === "done"
                  ? "⚗️  Distill this moment"
                  : transcriptStatus === "processing" || transcriptStatus === "queued"
                    ? "⏳  Transcribing…"
                    : "⏳  Waiting for transcript"}
            </button>

            {/* ── Ad-free toggle ── */}
            {adFreeStatus?.has_adfree && (
              <div className="flex items-center justify-center gap-3">
                <span className="text-xs text-white/30">
                  {adFreeStatus.ads_count} ad{adFreeStatus.ads_count !== 1 ? "s" : ""} detected
                </span>
                <div className="flex rounded-full bg-white/10 p-0.5">
                  <button
                    onClick={() => setUseAdFree(false)}
                    className={`px-3 py-1 rounded-full text-xs font-medium transition-all ${
                      !useAdFree ? "bg-white text-gray-900 shadow" : "text-white/50"
                    }`}
                  >Original</button>
                  <button
                    onClick={() => setUseAdFree(true)}
                    className={`px-3 py-1 rounded-full text-xs font-medium transition-all ${
                      useAdFree ? "bg-white text-gray-900 shadow" : "text-white/50"
                    }`}
                  >Ad-free ✂️</button>
                </div>
              </div>
            )}

            {/* ── Current chapter + skip ── */}
            {currentChapter && (
              <div className="flex items-center gap-2">
                <button
                  onClick={() => setChaptersOpen(o => !o)}
                  className="flex-1 text-left text-xs text-white/50 hover:text-white/80 truncate transition-colors"
                >
                  § {currentChapter.title}
                </button>
                {currentChapterIndex < chapters.length - 1 && (
                  <button
                    onClick={skipToNextChapter}
                    className="flex-shrink-0 text-xs text-white/40 hover:text-white px-2.5 py-1 rounded-full bg-white/10 hover:bg-white/15 transition-colors"
                  >
                    Next §
                  </button>
                )}
              </div>
            )}
            {chapters.length > 0 && !currentChapter && (
              <button
                onClick={() => setChaptersOpen(o => !o)}
                className="text-xs text-white/40 hover:text-white/60 transition-colors text-left"
              >
                § {chapters.length} chapters
              </button>
            )}

            {/* ── Transcript badge ── */}
            <div className="flex justify-center pb-2">
              <TranscriptBadge status={transcriptStatus} />
            </div>
          </div>
        </div>

        {/* ── Chapters backdrop — inside fixed container, below sheet ── */}
        {chaptersOpen && (
          <div
            className="absolute inset-0 z-10 bg-black/50"
            onClick={() => setChaptersOpen(false)}
          />
        )}

        {/* ── Chapters sheet — outside scroll container, covers all controls ── */}
        <div
          className={`absolute inset-x-0 bottom-0 z-20 bg-gray-950 rounded-t-3xl transition-transform duration-300 ease-out max-h-[70vh] flex flex-col ${
            chaptersOpen ? "translate-y-0" : "translate-y-full"
          }`}
          style={{ paddingBottom: "max(env(safe-area-inset-bottom), 16px)" }}
        >
          {/* Drag handle */}
          <div className="flex justify-center pt-3 pb-1 flex-shrink-0">
            <div className="w-10 h-1 bg-white/20 rounded-full" />
          </div>
          <div className="flex items-center justify-between px-5 py-3 border-b border-gray-800/60 flex-shrink-0">
            <span className="text-sm font-bold text-white">Chapters ({chapters.length})</span>
            <button
              onClick={() => setChaptersOpen(false)}
              className="w-7 h-7 flex items-center justify-center rounded-full bg-gray-800 hover:bg-gray-700 text-gray-400 hover:text-white transition-colors text-lg leading-none"
            >
              ×
            </button>
          </div>
          <div className="overflow-y-auto divide-y divide-gray-800/40">
            {chapters.map((ch, i) => (
              <button
                key={i}
                onClick={() => {
                  const audio = audioRef.current;
                  if (audio) audio.currentTime = ch.start_time;
                  setChaptersOpen(false);
                }}
                className={`w-full flex items-center gap-3 px-5 py-3.5 text-left hover:bg-gray-800/50 transition-colors ${
                  i === currentChapterIndex ? "bg-indigo-900/20" : ""
                }`}
              >
                <span className="text-xs font-mono text-gray-500 w-10 flex-shrink-0">
                  {fmtTime(ch.start_time)}
                </span>
                <span className={`text-sm flex-1 leading-snug ${
                  i === currentChapterIndex ? "text-indigo-300 font-semibold" : "text-gray-300"
                }`}>
                  {i === currentChapterIndex && <span className="mr-1">▶</span>}
                  {ch.title}
                </span>
              </button>
            ))}
          </div>
        </div>
      </div>
  );
}
