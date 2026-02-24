import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getSubscriptions, getEpisodes, listShots, Episode, Subscription } from "../api/client";
import { getCached, setCached } from "../cache";

const FEED_CACHE_KEY = "home:feed";
const SHOTS_CACHE_KEY = "home:shotCounts";

// ─── Listened state (localStorage) ───────────────────────────────────────────
const STORAGE_KEY = "earshot:played";

function getPlayed(): Set<string> {
  try { return new Set(JSON.parse(localStorage.getItem(STORAGE_KEY) || "[]")); }
  catch { return new Set(); }
}

function togglePlayed(id: string): Set<string> {
  const played = getPlayed();
  played.has(id) ? played.delete(id) : played.add(id);
  localStorage.setItem(STORAGE_KEY, JSON.stringify([...played]));
  return played;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────
function fmtDate(iso?: string) {
  if (!iso) return null;
  const d = new Date(iso);
  const now = new Date();
  const diffDays = Math.floor((now.getTime() - d.getTime()) / 86400000);
  if (diffDays === 0) return "Today";
  if (diffDays === 1) return "Yesterday";
  if (diffDays < 7) return `${diffDays}d ago`;
  return d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
}

function fmtDuration(secs?: number | null) {
  if (!secs) return null;
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

// ─── Types ────────────────────────────────────────────────────────────────────
interface FeedEpisode extends Episode {
  podcastTitle: string;
  podcastImage?: string;
}

// ─── Skeleton card ────────────────────────────────────────────────────────────
function SkeletonCard() {
  return (
    <div className="bg-gray-900 rounded-xl p-4 flex gap-3 animate-pulse">
      <div className="w-12 h-12 rounded-lg bg-gray-800 flex-shrink-0" />
      <div className="flex-1 space-y-2">
        <div className="h-3 bg-gray-800 rounded w-1/3" />
        <div className="h-4 bg-gray-800 rounded w-full" />
        <div className="h-4 bg-gray-800 rounded w-3/4" />
        <div className="h-3 bg-gray-800 rounded w-1/4" />
      </div>
    </div>
  );
}

// ─── Episode card ─────────────────────────────────────────────────────────────
function EpisodeCard({
  ep, shotCount, played, onTogglePlayed,
}: {
  ep: FeedEpisode;
  shotCount: number;
  played: boolean;
  onTogglePlayed: () => void;
}) {
  const nav = useNavigate();

  return (
    <div
      className={`bg-gray-900 rounded-xl p-4 flex gap-3 transition-opacity ${played ? "opacity-60" : ""}`}
    >
      {/* Podcast art */}
      <div className="flex-shrink-0">
        {ep.podcastImage
          ? <img src={ep.podcastImage} className="w-12 h-12 rounded-lg object-cover" alt="" />
          : <div className="w-12 h-12 rounded-lg bg-gray-800 flex items-center justify-center text-xl">🎙</div>
        }
      </div>

      {/* Content */}
      <div
        className="flex-1 min-w-0 cursor-pointer"
        onClick={() => nav(`/player/${ep.id}`, { state: { ...ep, podcast_image: ep.podcastImage } })}
      >
        <div className="text-xs text-gray-500 mb-0.5 truncate">{ep.podcastTitle}</div>
        <div className="text-sm font-medium leading-snug line-clamp-2">{ep.title}</div>

        {/* Meta row */}
        <div className="flex items-center gap-2 mt-1.5 flex-wrap">
          {fmtDate(ep.published_at) && (
            <span className="text-xs text-gray-500">{fmtDate(ep.published_at)}</span>
          )}
          {fmtDuration(ep.duration_seconds) && (
            <span className="text-xs text-gray-600">· {fmtDuration(ep.duration_seconds)}</span>
          )}
          {shotCount > 0 && (
            <span className="text-xs bg-indigo-900 text-indigo-300 px-1.5 py-0.5 rounded-full font-medium">
              ✂️ {shotCount}
            </span>
          )}
        </div>
      </div>

      {/* Listened toggle */}
      <button
        onClick={e => { e.stopPropagation(); onTogglePlayed(); }}
        className="flex-shrink-0 self-center ml-1"
        title={played ? "Mark as unplayed" : "Mark as played"}
      >
        {played ? (
          <div className="w-6 h-6 rounded-full bg-indigo-500 flex items-center justify-center">
            <svg viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth={3} strokeLinecap="round" strokeLinejoin="round" className="w-3.5 h-3.5">
              <polyline points="20 6 9 17 4 12" />
            </svg>
          </div>
        ) : (
          <div className="w-6 h-6 rounded-full border-2 border-gray-600 hover:border-gray-400 transition-colors" />
        )}
      </button>
    </div>
  );
}

// ─── Home page ────────────────────────────────────────────────────────────────
export default function Home() {
  const nav = useNavigate();
  const [feed, setFeed] = useState<FeedEpisode[]>(() => getCached<FeedEpisode[]>(FEED_CACHE_KEY) || []);
  const [shotCounts, setShotCounts] = useState<Record<string, number>>(() => getCached<Record<string, number>>(SHOTS_CACHE_KEY) || {});
  const [played, setPlayed] = useState<Set<string>>(getPlayed());
  const [loading, setLoading] = useState(() => !getCached(FEED_CACHE_KEY)); // skip spinner if cache hit
  const [refreshing, setRefreshing] = useState(false);
  const [noSubs, setNoSubs] = useState(false);

  const fetchFeed = async (showRefreshing = false) => {
    if (showRefreshing) setRefreshing(true);
    try {
      const subs = await getSubscriptions();
      if (subs.length === 0) { setNoSubs(true); return; }

      const [episodeLists, shots] = await Promise.all([
        Promise.all(
          subs.map((s: Subscription) =>
            getEpisodes(s.podcast_id, false)
              .then(eps => eps.map(ep => ({
                ...ep,
                podcastTitle: s.title,
                podcastImage: s.image_url,
              })))
              .catch(() => [] as FeedEpisode[])
          )
        ),
        listShots().catch(() => []),
      ]);

      const counts: Record<string, number> = {};
      shots.forEach(s => { counts[s.episode_id] = (counts[s.episode_id] || 0) + 1; });

      const all = episodeLists.flat().sort((a, b) => {
        if (!a.published_at) return 1;
        if (!b.published_at) return -1;
        return new Date(b.published_at).getTime() - new Date(a.published_at).getTime();
      }).slice(0, 50);

      setFeed(all);
      setShotCounts(counts);
      setCached(FEED_CACHE_KEY, all);
      setCached(SHOTS_CACHE_KEY, counts);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => { fetchFeed(); }, []);

  const handleTogglePlayed = (id: string) => {
    setPlayed(togglePlayed(id));
  };

  // ── Empty: no subscriptions ──
  if (!loading && noSubs) return (
    <div className="text-center py-16 space-y-4">
      <div className="text-5xl">🎙</div>
      <p className="text-gray-300 font-medium">No subscriptions yet</p>
      <p className="text-gray-500 text-sm">Search for podcasts to fill your feed.</p>
      <button
        onClick={() => nav("/search")}
        className="mt-2 bg-indigo-600 hover:bg-indigo-500 text-white px-5 py-2.5 rounded-xl font-medium text-sm"
      >
        Find podcasts
      </button>
    </div>
  );

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-bold">Latest Episodes</h1>
        <button
          onClick={() => fetchFeed(true)}
          disabled={refreshing}
          className="text-gray-400 hover:text-white disabled:opacity-40 transition-colors p-1"
          title="Refresh"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"
            className={`w-5 h-5 ${refreshing ? "animate-spin" : ""}`}>
            <polyline points="23 4 23 10 17 10" />
            <polyline points="1 20 1 14 7 14" />
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
          </svg>
        </button>
      </div>

      {loading && [...Array(5)].map((_, i) => <SkeletonCard key={i} />)}

      {!loading && feed.map(ep => (
        <EpisodeCard
          key={ep.id}
          ep={ep}
          shotCount={shotCounts[ep.id] || 0}
          played={played.has(ep.id)}
          onTogglePlayed={() => handleTogglePlayed(ep.id)}
        />
      ))}
    </div>
  );
}
