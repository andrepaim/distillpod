import { useEffect, useState } from "react";
import { useNavigate, useParams, useLocation } from "react-router-dom";
import { getSubscriptions, getEpisodes, unsubscribe, Subscription, Episode } from "../api/client";
import { getCached, setCached, bustCache } from "../cache";

const TrashIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="w-5 h-5">
    <polyline points="3 6 5 6 21 6" />
    <path d="M19 6l-1 14H6L5 6" />
    <path d="M10 11v6M14 11v6" />
    <path d="M9 6V4h6v2" />
  </svg>
);

const statusColors: Record<string, string> = {
  done:       "bg-green-900 text-green-300",
  processing: "bg-yellow-900 text-yellow-300",
  queued:     "bg-yellow-900 text-yellow-300",
  error:      "bg-red-900 text-red-300",
  none:       "bg-gray-700 text-gray-400",
};

function fmtDate(iso?: string) {
  if (!iso) return null;
  return new Date(iso).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
}

function fmtDuration(secs?: number | null) {
  if (!secs) return null;
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

// ─── Episode List View ────────────────────────────────────────────────────────
function EpisodeList({ sub, onUnsubscribed }: { sub: Subscription; onUnsubscribed: () => void }) {
  const nav = useNavigate();
  const cacheKey = `episodes:${sub.podcast_id}`;
  const [episodes, setEpisodes] = useState<Episode[]>(() => getCached<Episode[]>(cacheKey) || []);
  const [loading, setLoading] = useState(() => !getCached(cacheKey));
  const [refreshing, setRefreshing] = useState(false);
  const [unsubbing, setUnsubbing] = useState(false);

  const loadEpisodes = async (forceRefresh = false) => {
    if (forceRefresh) setRefreshing(true);
    try {
      const eps = await getEpisodes(sub.podcast_id, forceRefresh);
      setEpisodes(eps);
      setCached(cacheKey, eps);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => { loadEpisodes(false); }, [sub.podcast_id]);

  const handleUnsubscribe = async () => {
    if (!confirm(`Unsubscribe from "${sub.title}"?`)) return;
    setUnsubbing(true);
    try {
      await unsubscribe(sub.podcast_id);
      // Bug 5: Bust home feed cache so unsubscribed episodes disappear immediately
      bustCache("home:feed");
      bustCache("home:shotCounts");
      bustCache(`episodes:${sub.podcast_id}`);
      nav('/subscriptions');
      onUnsubscribed();
    } finally {
      setUnsubbing(false);
    }
  };

  return (
    <div className="space-y-3">
      {/* Header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => nav('/subscriptions')}
          className="flex items-center gap-1.5 text-indigo-400 hover:text-indigo-300 active:text-indigo-500 transition-colors py-2 pr-2 -ml-1"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round" className="w-5 h-5">
            <polyline points="15 18 9 12 15 6" />
          </svg>
          <span className="text-sm font-medium">Library</span>
        </button>
        <div className="flex-1 flex items-center gap-2 min-w-0">
          {sub.image_url && <img src={sub.image_url} className="w-8 h-8 rounded object-cover flex-shrink-0" alt="" />}
          <h2 className="font-semibold text-sm leading-snug line-clamp-1">{sub.title}</h2>
        </div>
        <button
          onClick={() => loadEpisodes(true)}
          disabled={refreshing}
          className="text-gray-500 hover:text-white disabled:opacity-40 transition-colors p-2 rounded-lg hover:bg-gray-800"
          title="Refresh episodes"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"
            className={`w-5 h-5 ${refreshing ? "animate-spin" : ""}`}>
            <polyline points="23 4 23 10 17 10" />
            <polyline points="1 20 1 14 7 14" />
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
          </svg>
        </button>
        <button
          onClick={handleUnsubscribe}
          disabled={unsubbing}
          className="text-gray-500 hover:text-red-400 disabled:opacity-40 transition-colors p-2 rounded-lg hover:bg-gray-800"
          title="Unsubscribe"
        >
          {unsubbing ? <span className="text-sm">…</span> : <TrashIcon />}
        </button>
      </div>

      {/* Loading skeletons */}
      {loading && (
        <div className="space-y-2 pt-1">
          {[...Array(6)].map((_, i) => (
            <div key={i} className="bg-gray-900 rounded-xl p-4 animate-pulse h-16" />
          ))}
        </div>
      )}

      {/* Empty */}
      {!loading && episodes.length === 0 && (
        <p className="text-gray-500 text-sm text-center pt-8">No episodes found.</p>
      )}

      {/* Episode rows */}
      {!loading && episodes.map(ep => (
        <div
          key={ep.id}
          onClick={() => nav(`/player/${ep.id}`, { state: { ...ep, podcast_image: sub.image_url, podcast_title: sub.title } })}
          className="bg-gray-900 hover:bg-gray-800 active:bg-gray-700 rounded-xl p-4 cursor-pointer transition-colors"
        >
          <div className="flex justify-between items-start gap-3">
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium leading-snug mb-1">{ep.title}</div>
              <div className="text-xs text-gray-500 flex gap-2">
                {fmtDate(ep.published_at) && <span>{fmtDate(ep.published_at)}</span>}
                {fmtDuration(ep.duration_seconds) && <span>· {fmtDuration(ep.duration_seconds)}</span>}
              </div>
            </div>
            <span className={`text-xs px-2 py-0.5 rounded-full flex-shrink-0 mt-0.5 ${statusColors[ep.transcript_status] ?? statusColors.none}`}>
              {ep.transcript_status === "none" ? "–" : ep.transcript_status}
            </span>
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Podcast Episode List Page (routed: /subscriptions/:podcastId) ────────────
export function PodcastEpisodes() {
  const { podcastId } = useParams<{ podcastId: string }>();
  const location = useLocation();
  const nav = useNavigate();
  // Sub data passed as nav state (fast path); fall back to API fetch
  const [sub, setSub] = useState<Subscription | null>(
    (location.state as Subscription | null) ?? null
  );

  useEffect(() => {
    if (sub || !podcastId) return;
    getSubscriptions()
      .then(subs => setSub(subs.find(s => s.podcast_id === podcastId) ?? null))
      .catch(() => {});
  }, [podcastId]);

  if (!sub) {
    return (
      <div className="flex items-center justify-center h-40 text-gray-500 text-sm">
        <span className="w-5 h-5 border-2 border-gray-600 border-t-indigo-400 rounded-full animate-spin mr-2" />
        Loading…
      </div>
    );
  }

  return (
    <EpisodeList
      sub={sub}
      onUnsubscribed={() => nav('/subscriptions')}
    />
  );
}

// ─── Podcast List View (routed: /subscriptions) ───────────────────────────────
export default function Subscriptions() {
  const nav = useNavigate();
  const [subs, setSubs] = useState<Subscription[]>([]);

  useEffect(() => { getSubscriptions().then(setSubs); }, []);

  return (
    <div className="space-y-3">
      <h1 className="text-lg font-bold">Library</h1>

      {subs.length === 0 && (
        <div className="text-center py-12 text-gray-500">
          <div className="text-4xl mb-3">🎙</div>
          <p>No subscriptions yet.</p>
          <p className="text-sm mt-1">Search for podcasts to get started.</p>
        </div>
      )}

      {subs.map(s => (
        <div
          key={s.podcast_id}
          onClick={() => nav(`/subscriptions/${s.podcast_id}`, { state: s })}
          className="bg-gray-900 hover:bg-gray-800 active:bg-gray-700 rounded-xl p-4 flex gap-4 items-center cursor-pointer transition-colors"
        >
          {s.image_url
            ? <img src={s.image_url} className="w-14 h-14 rounded-lg object-cover flex-shrink-0" alt="" />
            : <div className="w-14 h-14 rounded-lg bg-gray-800 flex-shrink-0 flex items-center justify-center text-2xl">🎙</div>
          }
          <div className="flex-1 min-w-0">
            <div className="font-medium leading-snug">{s.title}</div>
            <div className="text-xs text-gray-500 mt-0.5">
              Since {fmtDate(s.subscribed_at)}
            </div>
          </div>
          <span className="text-gray-600 text-lg">›</span>
        </div>
      ))}
    </div>
  );
}
