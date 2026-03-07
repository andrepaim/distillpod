import { useState, useCallback } from "react";
import { useAudio } from "../context/AudioContext";
import { getTranscriptStatus, createGist } from "../api/client";

export default function MiniPlayer() {
  const { episode, isPlaying, togglePlay, currentTime, duration, audioReady, setPlayerExpanded } = useAudio();
  const [distillState, setDistillState] = useState<"idle"|"loading"|"done"|"error">("idle");

  const handleDistill = useCallback(async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!episode || distillState === "loading") return;
    setDistillState("loading");
    try {
      const { status } = await getTranscriptStatus(episode.id);
      if (status !== "done") {
        setDistillState("error"); // no transcript yet
        setTimeout(() => setDistillState("idle"), 2000);
        return;
      }
      await createGist(episode.id, 0);
      setDistillState("done");
      setTimeout(() => setDistillState("idle"), 3000);
    } catch {
      setDistillState("error");
      setTimeout(() => setDistillState("idle"), 2000);
    }
  }, [episode, distillState]);

  if (!episode || !audioReady) return null;

  const progress = duration > 0 ? (currentTime / duration) * 100 : 0;

  const fmt = (secs: number) => {
    if (!isFinite(secs) || isNaN(secs)) return "0:00";
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${String(s).padStart(2, "0")}`;
  };

  return (
    <div
      className="fixed left-0 right-0 bg-gray-900 border-t border-gray-800 z-40 transition-transform"
      style={{ bottom: "calc(56px + env(safe-area-inset-bottom))" }}
    >
      {/* Thin progress strip at top edge */}
      <div className="h-0.5 bg-gray-800">
        <div
          className="h-full bg-indigo-500 transition-[width] duration-500"
          style={{ width: `${progress}%` }}
        />
      </div>

      {/* Content row */}
      <div className="flex items-center gap-3 px-3 py-2">

        {/* Tap area → open fullscreen player */}
        <button
          onClick={() => setPlayerExpanded(true)}
          className="flex items-center gap-2.5 flex-1 min-w-0 text-left"
        >
          {episode.podcast_image
            ? <img
                src={episode.podcast_image}
                className="w-9 h-9 rounded-lg object-cover flex-shrink-0"
                alt=""
              />
            : <div className="w-9 h-9 rounded-lg bg-gray-700 flex-shrink-0 flex items-center justify-center text-lg">
                🎧
              </div>
          }
          <div className="min-w-0 flex-1">
            {episode.podcast_title && (
              <div className="text-xs text-gray-500 truncate leading-tight">
                {episode.podcast_title}
              </div>
            )}
            <div className="text-sm font-medium text-white truncate leading-snug">
              {episode.title}
            </div>
          </div>
        </button>

        {/* Time */}
        <span className="text-xs text-gray-500 font-mono flex-shrink-0 hidden xs:block">
          {fmt(currentTime)}
        </span>

        {/* Distill button */}
        <button
          onClick={handleDistill}
          title={distillState === "error" ? "No transcript yet" : "Distill this episode"}
          className="w-9 h-9 flex items-center justify-center rounded-full transition-all flex-shrink-0 active:scale-95"
          style={{ background: distillState === "done" ? "#22c55e22" : distillState === "error" ? "#ef444422" : "rgba(255,215,0,0.15)" }}
          disabled={distillState === "loading"}
        >
          {distillState === "loading" ? (
            <span className="w-4 h-4 border-2 border-gray-500 border-t-yellow-400 rounded-full animate-spin inline-block" />
          ) : distillState === "done" ? (
            <span className="text-green-400 text-base">✅</span>
          ) : distillState === "error" ? (
            <span className="text-red-400 text-base">🎙️</span>
          ) : (
            <span style={{ color: "#FFD700" }} className="text-base">⚗️</span>
          )}
        </button>

        {/* Play / Pause */}
        <button
          onClick={e => { e.stopPropagation(); togglePlay(); }}
          className="w-9 h-9 bg-indigo-600 hover:bg-indigo-500 active:scale-95 rounded-full flex items-center justify-center transition-all flex-shrink-0"
        >
          {isPlaying ? (
            <svg viewBox="0 0 24 24" fill="white" className="w-4 h-4">
              <rect x="6" y="4" width="4" height="16" rx="1" />
              <rect x="14" y="4" width="4" height="16" rx="1" />
            </svg>
          ) : (
            <svg viewBox="0 0 24 24" fill="white" className="w-4 h-4 translate-x-0.5">
              <polygon points="5 3 19 12 5 21 5 3" />
            </svg>
          )}
        </button>

      </div>
    </div>
  );
}
