import { useState } from "react";
import { searchPodcasts, subscribe, Podcast } from "../api/client";

export default function Search() {
  const [q, setQ] = useState("");
  const [results, setResults] = useState<Podcast[]>([]);
  const [loading, setLoading] = useState(false);

  const doSearch = async () => {
    if (!q.trim()) return;
    setLoading(true);
    try { setResults(await searchPodcasts(q)); }
    finally { setLoading(false); }
  };

  return (
    <div className="space-y-4">
      <div className="flex gap-2">
        <input
          className="flex-1 bg-gray-800 rounded px-3 py-2 outline-none focus:ring-2 ring-indigo-500"
          placeholder="Search podcasts..."
          value={q}
          onChange={e => setQ(e.target.value)}
          onKeyDown={e => e.key === "Enter" && doSearch()}
        />
        <button onClick={doSearch} className="bg-indigo-600 hover:bg-indigo-500 px-4 py-2 rounded font-medium">
          {loading ? "..." : "Search"}
        </button>
      </div>
      {results.map(p => (
        <div key={p.id} className="bg-gray-900 rounded-lg p-4 flex gap-4 items-start">
          {p.image_url && <img src={p.image_url} className="w-16 h-16 rounded object-cover" />}
          <div className="flex-1 min-w-0">
            <div className="font-semibold truncate">{p.title}</div>
            <div className="text-gray-400 text-sm">{p.author}</div>
            <div className="text-gray-500 text-xs mt-1 line-clamp-2">{p.description}</div>
          </div>
          <button
            onClick={() => subscribe(p.id, p.feed_url, p.title, p.image_url)}
            className="bg-indigo-700 hover:bg-indigo-600 text-sm px-3 py-1 rounded whitespace-nowrap"
          >
            Subscribe
          </button>
        </div>
      ))}
    </div>
  );
}
