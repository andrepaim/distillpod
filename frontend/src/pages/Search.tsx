import { useState, useEffect } from "react";
import { searchPodcasts, subscribe, getSubscriptions, Podcast } from "../api/client";

export default function Search() {
  const [q, setQ] = useState("");
  const [results, setResults] = useState<Podcast[]>([]);
  const [loading, setLoading] = useState(false);
  const [subscribedIds, setSubscribedIds] = useState<Set<string>>(new Set());
  const [subscribing, setSubscribing] = useState<Set<string>>(new Set());
  const [toast, setToast] = useState("");

  useEffect(() => {
    getSubscriptions().then(subs => setSubscribedIds(new Set(subs.map(s => s.podcast_id))));
  }, []);

  const doSearch = async () => {
    if (!q.trim()) return;
    setLoading(true);
    try { setResults(await searchPodcasts(q)); }
    finally { setLoading(false); }
  };

  const handleSubscribe = async (p: Podcast) => {
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

  return (
    <div className="space-y-4">
      {toast && (
        <div className="fixed top-4 right-4 bg-green-700 text-white px-4 py-2 rounded-lg shadow-lg z-50 text-sm animate-fade-in">
          ✓ {toast}
        </div>
      )}

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
          {loading ? <span className="inline-block w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" /> : "Search"}
        </button>
      </div>

      {results.length === 0 && !loading && q && (
        <p className="text-gray-500 text-sm text-center pt-4">No results for "{q}"</p>
      )}

      {results.map(p => {
        const isSubscribed = subscribedIds.has(p.id);
        const isBusy = subscribing.has(p.id);
        return (
          <div key={p.id} className="bg-gray-900 rounded-lg p-4 flex gap-4 items-start">
            {p.image_url
              ? <img src={p.image_url} className="w-16 h-16 rounded object-cover flex-shrink-0" alt="" />
              : <div className="w-16 h-16 rounded bg-gray-800 flex-shrink-0 flex items-center justify-center text-2xl">🎙</div>
            }
            <div className="flex-1 min-w-0">
              <div className="font-semibold truncate">{p.title}</div>
              <div className="text-gray-400 text-sm">{p.author}</div>
              <div className="text-gray-500 text-xs mt-1 line-clamp-2">{p.description}</div>
            </div>
            <button
              onClick={() => handleSubscribe(p)}
              disabled={isSubscribed || isBusy}
              className={`text-sm px-3 py-1.5 rounded whitespace-nowrap flex-shrink-0 font-medium transition-colors ${
                isSubscribed
                  ? "bg-green-900 text-green-300 cursor-default"
                  : "bg-indigo-700 hover:bg-indigo-600 text-white"
              }`}
            >
              {isBusy ? "…" : isSubscribed ? "✓ Subscribed" : "Subscribe"}
            </button>
          </div>
        );
      })}
    </div>
  );
}
