import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { listSnips, deleteSnip, getSubscriptions, Snip, Subscription } from "../api/client";

function fmtTime(secs: number) {
  const m = Math.floor(secs / 60);
  const s = Math.floor(secs % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
}

function fmtDate(iso: string) {
  return new Date(iso).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
}

// ─── Individual snip card ─────────────────────────────────────────────────────
function SnipCard({ snip, podcastImage, onDelete }: { snip: Snip; podcastImage?: string; onDelete: () => void }) {
  const nav = useNavigate();
  const [copied, setCopied] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const copy = async () => {
    await navigator.clipboard.writeText(snip.text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDelete = async () => {
    if (!confirm("Delete this snip?")) return;
    setDeleting(true);
    try { await deleteSnip(snip.id); onDelete(); }
    finally { setDeleting(false); }
  };

  const handlePlay = () => {
    nav(`/player/${snip.episode_id}`, {
      state: { seekTo: snip.start_seconds, podcast_image: podcastImage },
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
          {fmtTime(snip.start_seconds)} → {fmtTime(snip.end_seconds)}
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
      <p className="text-sm leading-relaxed text-gray-100">{snip.text}</p>
      {snip.summary && (
        <p className="text-indigo-300 text-sm italic border-l-2 border-indigo-600 pl-3">{snip.summary}</p>
      )}
    </div>
  );
}

// ─── Episode snips view ───────────────────────────────────────────────────────
function EpisodeSnips({
  episodeId, episodeTitle, podcastTitle, podcastImage, snips: initial, onBack, onAllDeleted,
}: {
  episodeId: string;
  episodeTitle: string;
  podcastTitle: string;
  podcastImage?: string;
  snips: Snip[];
  onBack: () => void;
  onAllDeleted: () => void;
}) {
  const nav = useNavigate();
  const [snips, setSnips] = useState(initial);

  const handleDelete = (id: string) => {
    const updated = snips.filter(s => s.id !== id);
    setSnips(updated);
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
          <span className="text-sm font-medium">Snips</span>
        </button>
        <div className="flex-1 min-w-0">
          <div className="text-xs text-gray-500 mb-0.5">{podcastTitle}</div>
          <div className="font-semibold text-sm leading-snug line-clamp-2">{episodeTitle}</div>
        </div>
        <button
          onClick={() => nav(`/player/${episodeId}`, { state: { podcast_image: podcastImage } })}
          className="text-xs bg-indigo-700 hover:bg-indigo-600 text-white px-3 py-1.5 rounded-lg flex-shrink-0"
        >
          ▶ Play
        </button>
      </div>

      <div className="text-xs text-gray-500 pb-1">
        {snips.length} snip{snips.length !== 1 ? "s" : ""}
      </div>

      {snips.map(s => (
        <SnipCard key={s.id} snip={s} podcastImage={podcastImage} onDelete={() => handleDelete(s.id)} />
      ))}
    </div>
  );
}

// ─── Episode summary row ──────────────────────────────────────────────────────
function EpisodeRow({ episodeId, snips, imageUrl, onClick }: { episodeId: string; snips: Snip[]; imageUrl?: string; onClick: () => void }) {
  const latest = snips.reduce((a, b) => a.created_at > b.created_at ? a : b);

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
              <div className="text-xs text-gray-600 mt-1">Last snipped {fmtDate(latest.created_at)}</div>
            </div>
            <div className="flex-shrink-0 flex flex-col items-end gap-1">
              <span className="bg-indigo-900 text-indigo-300 text-xs font-semibold px-2.5 py-1 rounded-full">
                {snips.length} snip{snips.length !== 1 ? "s" : ""}
              </span>
              <span className="text-gray-600 text-lg">›</span>
            </div>
          </div>
          <p className="text-xs text-gray-500 mt-2 line-clamp-2 italic">"{snips[0].text}"</p>
        </div>
      </div>
    </div>
  );
}

// ─── Main Snips page ──────────────────────────────────────────────────────────
export default function Snips() {
  const [allSnips, setAllSnips] = useState<Snip[]>([]);
  const [imageMap, setImageMap] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [selectedEpisode, setSelectedEpisode] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    Promise.all([listSnips(), getSubscriptions()]).then(([snips, subs]) => {
      setAllSnips(snips);
      setImageMap(Object.fromEntries(subs.filter(s => s.image_url).map(s => [s.podcast_id, s.image_url!])));
    }).finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  // Group by episode
  const byEpisode = allSnips.reduce<Record<string, Snip[]>>((acc, snip) => {
    if (!acc[snip.episode_id]) acc[snip.episode_id] = [];
    acc[snip.episode_id].push(snip);
    return acc;
  }, {});

  // Sort episodes by most recent snip
  const episodeIds = Object.keys(byEpisode).sort((a, b) => {
    const latestA = Math.max(...byEpisode[a].map(s => new Date(s.created_at).getTime()));
    const latestB = Math.max(...byEpisode[b].map(s => new Date(s.created_at).getTime()));
    return latestB - latestA;
  });

  // Drill-down view
  if (selectedEpisode && byEpisode[selectedEpisode]) {
    const snips = byEpisode[selectedEpisode];
    return (
      <EpisodeSnips
        episodeId={selectedEpisode}
        episodeTitle={snips[0].episode_title}
        podcastTitle={snips[0].podcast_title}
        podcastImage={imageMap[snips[0].podcast_id]}
        snips={snips}
        onBack={() => setSelectedEpisode(null)}
        onAllDeleted={() => {
          setAllSnips(prev => prev.filter(s => s.episode_id !== selectedEpisode));
          setSelectedEpisode(null);
        }}
      />
    );
  }

  // Episode list view
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-bold">Snips</h1>
        {allSnips.length > 0 && (
          <span className="text-sm text-gray-500">
            {allSnips.length} across {episodeIds.length} episode{episodeIds.length !== 1 ? "s" : ""}
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
          <div className="text-4xl mb-3">✂️</div>
          <p>No snips yet.</p>
          <p className="text-sm mt-1">Play an episode and tap Snip to capture a moment.</p>
        </div>
      )}

      {!loading && episodeIds.map(epId => (
        <EpisodeRow
          key={epId}
          episodeId={epId}
          snips={byEpisode[epId]}
          imageUrl={imageMap[byEpisode[epId][0].podcast_id]}
          onClick={() => setSelectedEpisode(epId)}
        />
      ))}
    </div>
  );
}
