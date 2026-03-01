import { useState, useEffect } from "react";
import {
  searchPodcasts, subscribe, getSubscriptions,
  getSuggestions, dismissSuggestion,
  Podcast, Suggestion,
} from "../api/client";

// ─── Podcast card (shared by search results and suggestions) ──────────────────
function PodcastCard({
  id, title, author, description, image_url, feed_url,
  reason, isSubscribed, isBusy,
  onSubscribe, onDismiss,
}: {
  id: string; title: string; author?: string; description?: string;
  image_url?: string; feed_url: string; reason?: string;
  isSubscribed: boolean; isBusy: boolean;
  onSubscribe: () => void;
  onDismiss?: () => void;
}) {
  return (
    <div className="bg-gray-900 rounded-lg p-4 flex gap-4 items-start">
      {image_url
        ? <img src={image_url} className="w-16 h-16 rounded object-cover flex-shrink-0" alt="" />
        : <div className="w-16 h-16 rounded bg-gray-800 flex-shrink-0 flex items-center justify-center text-2xl">🎙</div>
      }
      <div className="flex-1 min-w-0">
        <div className="font-semibold truncate">{title}</div>
        {author && <div className="text-gray-400 text-sm">{author}</div>}
        {reason
          ? <div className="text-indigo-400 text-xs mt-1 italic">{reason}</div>
          : description && <div className="text-gray-500 text-xs mt-1 line-clamp-2">{description}</div>
        }
      </div>
      <div className="flex flex-col gap-1.5 flex-shrink-0 items-end">
        <button
          onClick={onSubscribe}
          disabled={isSubscribed || isBusy}
          className={`text-sm px-3 py-1.5 rounded whitespace-nowrap font-medium transition-colors ${
            isSubscribed
              ? "bg-green-900 text-green-300 cursor-default"
              : "bg-indigo-700 hover:bg-indigo-600 text-white"
          }`}
        >
          {isBusy ? "…" : isSubscribed ? "✓ Subscribed" : "Subscribe"}
        </button>
        {onDismiss && !isSubscribed && (
          <button
            onClick={onDismiss}
            className="text-xs text-gray-600 hover:text-gray-400 transition-colors"
          >
            Not interested
          </button>
        )}
      </div>
    </div>
  );
}


// ─── Main ─────────────────────────────────────────────────────────────────────
export default function Search() {
  const [q, setQ] = useState("");
  const [results, setResults] = useState<Podcast[]>([]);
  const [loading, setLoading] = useState(false);
  const [subscribedIds, setSubscribedIds] = useState<Set<string>>(new Set());
  const [subscribing, setSubscribing] = useState<Set<string>>(new Set());
  const [toast, setToast] = useState("");

  // Suggestions state
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [loadingSuggestions, setLoadingSuggestions] = useState(true);

  useEffect(() => {
    getSubscriptions().then(subs => setSubscribedIds(new Set(subs.map(s => s.podcast_id))));
    getSuggestions()
      .then(setSuggestions)
      .catch(() => {})
      .finally(() => setLoadingSuggestions(false));
  }, []);

  const doSearch = async () => {
    if (!q.trim()) return;
    setLoading(true);
    try { setResults(await searchPodcasts(q)); }
    finally { setLoading(false); }
  };

  const handleSubscribe = async (p: { id: string; feed_url: string; title: string; image_url?: string }) => {
    if (subscribedIds.has(p.id)) return;
    setSubscribing(prev => new Set(prev).add(p.id));
    try {
      await subscribe(p.id, p.feed_url, p.title, p.image_url);
      setSubscribedIds(prev => new Set(prev).add(p.id));
      setToast(`Subscribed to ${p.title}`);
      setTimeout(() => setToast(""), 3000);
    } finally {
      setSubscribing(prev => { const s = new Set(prev); s.delete(p.id); return s; });
    }
  };

  const handleDismiss = async (id: string) => {
    setSuggestions(prev => prev.filter(s => s.id !== id));
    await dismissSuggestion(id).catch(() => {});
  };

  const showSuggestions = !q && suggestions.length > 0;
  const showEmpty = !q && !loadingSuggestions && suggestions.length === 0;

  return (
    <div className="space-y-4">
      {toast && (
        <div className="fixed top-4 right-4 bg-green-700 text-white px-4 py-2 rounded-lg shadow-lg z-50 text-sm">
          ✓ {toast}
        </div>
      )}

      {/* Search bar */}
      <div className="flex gap-2">
        <input
          className="flex-1 bg-gray-800 rounded px-3 py-2 outline-none focus:ring-2 ring-indigo-500"
          placeholder="Search podcasts…"
          value={q}
          onChange={e => setQ(e.target.value)}
          onKeyDown={e => e.key === "Enter" && doSearch()}
        />
        <button
          onClick={doSearch}
          disabled={loading}
          className="bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 px-4 py-2 rounded font-medium"
        >
          {loading
            ? <span className="inline-block w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
            : "Search"
          }
        </button>
      </div>

      {/* Search results */}
      {q && results.length === 0 && !loading && (
        <p className="text-gray-500 text-sm text-center pt-4">No results for "{q}"</p>
      )}

      {q && results.map(p => (
        <PodcastCard
          key={p.id}
          {...p}
          isSubscribed={subscribedIds.has(p.id)}
          isBusy={subscribing.has(p.id)}
          onSubscribe={() => handleSubscribe(p)}
        />
      ))}

      {/* Suggestions — shown when search is empty */}
      {showSuggestions && (
        <div className="space-y-3">
          <div className="flex items-center gap-2">
            <span className="text-xs font-semibold uppercase tracking-widest text-gray-500">
              🤖 Suggested for you
            </span>
          </div>
          {suggestions.map(s => (
            <PodcastCard
              key={s.id}
              id={s.podcast_index_id || s.id}
              title={s.title}
              author={s.author}
              description={s.description}
              image_url={s.image_url}
              feed_url={s.feed_url}
              reason={s.reason}
              isSubscribed={subscribedIds.has(s.podcast_index_id || s.id)}
              isBusy={subscribing.has(s.podcast_index_id || s.id)}
              onSubscribe={() => handleSubscribe({
                id: s.podcast_index_id || s.id,
                feed_url: s.feed_url,
                title: s.title,
                image_url: s.image_url,
              })}
              onDismiss={() => handleDismiss(s.id)}
            />
          ))}
          <p className="text-xs text-gray-600 text-center pt-1">
            Updated daily · dismiss to hide
          </p>
        </div>
      )}

      {showEmpty && (
        <p className="text-gray-600 text-sm text-center pt-8">
          Suggestions will appear here after the first daily job runs.
        </p>
      )}
    </div>
  );
}
