import { useEffect, useRef, useState } from "react";
import { useParams, useLocation } from "react-router-dom";
import { startPlay, createSnip, listSnips, audioStreamUrl, getTranscriptStatus, Snip, Episode } from "../api/client";

export default function Player() {
  const { episodeId } = useParams<{ episodeId: string }>();
  const location = useLocation();
  const episode = location.state as Episode;
  const audioRef = useRef<HTMLAudioElement>(null);

  const [transcriptStatus, setTranscriptStatus] = useState("none");
  const [snips, setSnips] = useState<Snip[]>([]);
  const [snipping, setSnipping] = useState(false);
  const [audioReady, setAudioReady] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!episodeId || !episode) return;
    startPlay(episodeId, episode.audio_url)
      .then(() => setAudioReady(true))
      .catch(e => setError(e.message));
    listSnips(episodeId).then(setSnips);
  }, [episodeId]);

  // Poll transcript status until done
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
      const currentSec = audioRef.current.currentTime;
      const snip = await createSnip(episodeId, currentSec, false);
      setSnips(prev => [snip, ...prev]);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setSnipping(false);
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-lg font-bold">{episode?.title ?? "Loading…"}</h1>
        <div className={`text-xs mt-1 ${transcriptStatus === "done" ? "text-green-400" : "text-yellow-400"}`}>
          Transcript: {transcriptStatus}
        </div>
      </div>

      {error && <div className="bg-red-900 text-red-300 rounded p-3 text-sm">{error}</div>}

      {audioReady && (
        <div className="space-y-3">
          <audio
            ref={audioRef}
            src={audioStreamUrl(episodeId!)}
            controls
            className="w-full"
          />
          <button
            onClick={handleSnip}
            disabled={snipping || transcriptStatus !== "done"}
            className="w-full bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 disabled:cursor-not-allowed py-3 rounded-lg font-semibold text-lg"
          >
            {snipping ? "Creating snip…" : "✂️ Snip"}
          </button>
          {transcriptStatus !== "done" && (
            <p className="text-center text-gray-500 text-sm">Snip available once transcript is ready</p>
          )}
        </div>
      )}

      {snips.length > 0 && (
        <div className="space-y-3">
          <h2 className="text-gray-400 font-medium">Snips</h2>
          {snips.map(s => (
            <div key={s.id} className="bg-gray-900 rounded-lg p-4 space-y-2">
              <div className="text-xs text-gray-500">
                {Math.floor(s.start_seconds / 60)}:{String(Math.floor(s.start_seconds % 60)).padStart(2, "0")}
                {" → "}
                {Math.floor(s.end_seconds / 60)}:{String(Math.floor(s.end_seconds % 60)).padStart(2, "0")}
              </div>
              <p className="text-sm leading-relaxed">{s.text}</p>
              {s.summary && <p className="text-indigo-300 text-sm italic">{s.summary}</p>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
