import { useEffect, useState, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { listGists, deleteGist, getSubscriptions, triggerResearch, getResearch, Gist, Subscription, Research } from "../api/client";

function fmtTime(secs: number) {
  const m = Math.floor(secs / 60);
  const s = Math.floor(secs % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
}

function fmtDate(iso: string) {
  return new Date(iso).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
}

// ─── Individual shot card ─────────────────────────────────────────────────────
function parseGistSummary(summary: string | undefined): { quote?: string; insight?: string } | null {
  if (!summary) return null;
  try {
    const parsed = JSON.parse(summary);
    if (parsed.quote || parsed.insight) return parsed;
  } catch {}
  return { insight: summary };
}

function GistCard({ gist, podcastImage, onDelete }: { gist: Gist; podcastImage?: string; onDelete: () => void }) {
  const nav = useNavigate();
  const [copied, setCopied] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [research, setResearch] = useState<Research>({ status: "none" });
  const pollRef = useRef<ReturnType<typeof setInterval>>(undefined);
  const ai = parseGistSummary(gist.summary);

  useEffect(() => {
    getResearch(gist.id).then(setResearch);
  }, [gist.id]);

  useEffect(() => {
    if (research.status === "pending" || research.status === "running") {
      pollRef.current = setInterval(() => {
        getResearch(gist.id).then(setResearch);
      }, 10000);
    } else if (pollRef.current) {
      clearInterval(pollRef.current);
    }
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
  }, [research.status, gist.id]);

  const copy = async () => {
    const text = ai
      ? [ai.quote && `"${ai.quote}"`, ai.insight].filter(Boolean).join("\n\n")
      : gist.text;
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDelete = async () => {
    if (!confirm("Delete this shot?")) return;
    setDeleting(true);
    try { await deleteGist(gist.id); onDelete(); }
    finally { setDeleting(false); }
  };

  const handlePlay = () => {
    nav(`/player/${gist.episode_id}`, {
      state: { seekTo: gist.start_seconds, podcast_image: podcastImage, podcast_title: gist.podcast_title },
    });
  };

  return (
    <div className="bg-gray-900 rounded-xl p-4 space-y-2">
      <div className="flex items-center justify-between">
        <button
          onClick={handlePlay}
          className="flex items-center gap-1.5 text-xs text-indigo-400 hover:text-indigo-300 font-mono transition-colors"
        >
          <svg viewBox="0 0 24 24" fill="currentColor" className="w-3.5 h-3.5">
            <polygon points="5 3 19 12 5 21 5 3" />
          </svg>
          {fmtTime(gist.start_seconds)} → {fmtTime(gist.end_seconds)}
        </button>
        <div className="flex gap-2">
          <button
            onClick={copy}
            className="text-xs text-gray-400 hover:text-white px-2 py-0.5 rounded hover:bg-gray-700 transition-colors"
          >
            {copied ? "✓ Copied" : "📋 Copy"}
          </button>
          <button
            onClick={handleDelete}
            disabled={deleting}
            className="text-xs text-gray-500 hover:text-red-400 px-2 py-0.5 rounded hover:bg-gray-700 transition-colors"
          >
            {deleting ? "…" : "🗑"}
          </button>
        </div>
      </div>
      <div className="selectable">
        {ai ? (
          <>
            {ai.quote && (
              <p className="text-sm italic text-gray-100 border-l-2 border-indigo-500 pl-3">"{ai.quote}"</p>
            )}
            {ai.insight && (
              <p className="text-indigo-300 text-sm leading-relaxed">{ai.insight}</p>
            )}
          </>
        ) : (
          <p className="text-sm leading-relaxed text-gray-100">{gist.text}</p>
        )}
      </div>

      {/* Research */}
      <div className="pt-1">
        {research.status === "none" && (
          <button
            onClick={() => triggerResearch(gist.id).then(setResearch)}
            className="text-xs text-yellow-400 hover:text-yellow-300 px-2 py-0.5 rounded hover:bg-gray-700 transition-colors"
          >
            🔬 Research
          </button>
        )}
        {(research.status === "pending" || research.status === "running") && (
          <span className="text-xs text-gray-400">⏳ Researching... (3-5 min)</span>
        )}
        {research.status === "done" && research.public_url && (
          <button
            onClick={() => window.open(research.public_url, "_blank")}
            className="text-xs font-semibold px-3 py-1 rounded-lg"
            style={{ background: "#FFD700", color: "#1A1A1A" }}
          >
            📄 Open Report
          </button>
        )}
        {research.status === "error" && (
          <span className="text-xs text-red-400">
            ⚠️ Research failed.{" "}
            <button
              onClick={() => triggerResearch(gist.id).then(setResearch)}
              className="underline hover:text-red-300"
            >
              Retry
            </button>
          </span>
        )}
      </div>
    </div>
  );
}

// ─── Episode shots view ───────────────────────────────────────────────────────
function EpisodeGists({
  episodeId, episodeTitle, podcastTitle, podcastImage, gists: initial, onBack, onAllDeleted,
}: {
  episodeId: string;
  episodeTitle: string;
  podcastTitle: string;
  podcastImage?: string;
  gists: Gist[];
  onBack: () => void;
  onAllDeleted: () => void;
}) {
  const nav = useNavigate();
  const [gists, setGists] = useState(initial);

  const handleDelete = (id: string) => {
    const updated = gists.filter(s => s.id !== id);
    setGists(updated);
    if (updated.length === 0) onAllDeleted();
  };

  return (
    <div className="space-y-3">
      {/* Header */}
      <div className="flex items-start gap-3">
        <button
          onClick={onBack}
          className="flex items-center gap-1.5 text-indigo-400 hover:text-indigo-300 active:text-indigo-500 transition-colors py-1 pr-2 -ml-1 flex-shrink-0"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round" className="w-5 h-5">
            <polyline points="15 18 9 12 15 6" />
          </svg>
          <span className="text-sm font-medium">Distillations</span>
        </button>
        <div className="flex-1 min-w-0">
          <div className="text-xs text-gray-500 mb-0.5">{podcastTitle}</div>
          <div className="font-semibold text-sm leading-snug line-clamp-2">{episodeTitle}</div>
        </div>
        <button
          onClick={() => nav(`/player/${episodeId}`, { state: { podcast_image: podcastImage, podcast_title: podcastTitle } })}
          className="text-xs bg-indigo-700 hover:bg-indigo-600 text-white px-3 py-1.5 rounded-lg flex-shrink-0"
        >
          ▶ Play
        </button>
      </div>

      <div className="text-xs text-gray-500 pb-1">
        {gists.length} distillation{gists.length !== 1 ? "s" : ""}
      </div>

      {gists.map(s => (
        <GistCard key={s.id} gist={s} podcastImage={podcastImage} onDelete={() => handleDelete(s.id)} />
      ))}
    </div>
  );
}

// ─── Episode summary row ──────────────────────────────────────────────────────
function EpisodeRow({ episodeId, gists, imageUrl, onClick }: { episodeId: string; gists: Gist[]; imageUrl?: string; onClick: () => void }) {
  const latest = gists.reduce((a, b) => a.created_at > b.created_at ? a : b);

  return (
    <div
      onClick={onClick}
      className="bg-gray-900 hover:bg-gray-800 active:bg-gray-700 rounded-xl p-4 cursor-pointer transition-colors"
    >
      <div className="flex items-start gap-3">
        {/* Podcast image */}
        {imageUrl
          ? <img src={imageUrl} className="w-12 h-12 rounded-lg object-cover flex-shrink-0" alt="" />
          : <div className="w-12 h-12 rounded-lg bg-gray-800 flex-shrink-0 flex items-center justify-center text-xl">🎙</div>
        }

        <div className="flex-1 min-w-0">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <div className="text-xs text-gray-500 mb-0.5">{latest.podcast_title}</div>
              <div className="text-sm font-medium leading-snug line-clamp-2">{latest.episode_title}</div>
              <div className="text-xs text-gray-600 mt-1">Last gist {fmtDate(latest.created_at)}</div>
            </div>
            <div className="flex-shrink-0 flex flex-col items-end gap-1">
              <span className="bg-indigo-900 text-indigo-300 text-xs font-semibold px-2.5 py-1 rounded-full">
                {gists.length} distillation{gists.length !== 1 ? "s" : ""}
              </span>
              <span className="text-gray-600 text-lg">›</span>
            </div>
          </div>
          <p className="text-xs text-gray-500 mt-2 line-clamp-2 italic">"{gists[0].text}"</p>
        </div>
      </div>
    </div>
  );
}

// ─── Main Gists page ──────────────────────────────────────────────────────────
export default function Gists() {
  const [allGists, setAllGists] = useState<Gist[]>([]);
  const [imageMap, setImageMap] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [selectedEpisode, setSelectedEpisode] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    Promise.all([listGists(), getSubscriptions()]).then(([shots, subs]) => {
      setAllGists(shots);
      setImageMap(Object.fromEntries(subs.filter(s => s.image_url).map(s => [s.podcast_id, s.image_url!])));
    }).finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  // Group by episode
  const byEpisode = allGists.reduce<Record<string, Gist[]>>((acc, g) => {
    if (!acc[g.episode_id]) acc[g.episode_id] = [];
    acc[g.episode_id].push(g);
    return acc;
  }, {});

  // Sort episodes by most recent shot
  const episodeIds = Object.keys(byEpisode).sort((a, b) => {
    const latestA = Math.max(...byEpisode[a].map(s => new Date(s.created_at).getTime()));
    const latestB = Math.max(...byEpisode[b].map(s => new Date(s.created_at).getTime()));
    return latestB - latestA;
  });

  // Drill-down view
  if (selectedEpisode && byEpisode[selectedEpisode]) {
    const selGists = byEpisode[selectedEpisode];
    return (
      <EpisodeGists
        episodeId={selectedEpisode}
        episodeTitle={selGists[0].episode_title}
        podcastTitle={selGists[0].podcast_title}
        podcastImage={imageMap[selGists[0].podcast_id]}
        gists={selGists}
        onBack={() => setSelectedEpisode(null)}
        onAllDeleted={() => {
          setAllGists(prev => prev.filter(s => s.episode_id !== selectedEpisode));
          setSelectedEpisode(null);
        }}
      />
    );
  }

  // Episode list view
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-bold">Distillations</h1>
        {allGists.length > 0 && (
          <span className="text-sm text-gray-500">
            {allGists.length} across {episodeIds.length} episode{episodeIds.length !== 1 ? "s" : ""}
          </span>
        )}
      </div>

      {loading && (
        <div className="space-y-3">
          {[...Array(3)].map((_, i) => (
            <div key={i} className="bg-gray-900 rounded-xl p-4 animate-pulse h-28" />
          ))}
        </div>
      )}

      {!loading && episodeIds.length === 0 && (
        <div className="text-center py-12 text-gray-500">
          <div className="text-4xl mb-3">⚗️</div>
          <p>No distillations yet.</p>
          <p className="text-sm mt-1">Play an episode and tap Distill to capture a moment.</p>
        </div>
      )}

      {!loading && episodeIds.map(epId => (
        <EpisodeRow
          key={epId}
          episodeId={epId}
          gists={byEpisode[epId]}
          imageUrl={imageMap[byEpisode[epId][0].podcast_id]}
          onClick={() => setSelectedEpisode(epId)}
        />
      ))}
    </div>
  );
}
