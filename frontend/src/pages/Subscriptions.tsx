import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getSubscriptions, getEpisodes, Subscription, Episode } from "../api/client";

export default function Subscriptions() {
  const [subs, setSubs] = useState<Subscription[]>([]);
  const [selected, setSelected] = useState<Subscription | null>(null);
  const [episodes, setEpisodes] = useState<Episode[]>([]);
  const nav = useNavigate();

  useEffect(() => { getSubscriptions().then(setSubs); }, []);

  const loadEpisodes = async (sub: Subscription) => {
    setSelected(sub);
    const eps = await getEpisodes(sub.podcast_id, true);
    setEpisodes(eps);
  };

  return (
    <div className="space-y-4">
      {subs.length === 0 && <p className="text-gray-500">No subscriptions yet. Search for podcasts to subscribe.</p>}
      {subs.map(s => (
        <div key={s.podcast_id}
          className={`bg-gray-900 rounded-lg p-4 flex gap-4 items-center cursor-pointer hover:bg-gray-800 ${selected?.podcast_id === s.podcast_id ? "ring-2 ring-indigo-500" : ""}`}
          onClick={() => loadEpisodes(s)}
        >
          {s.image_url && <img src={s.image_url} className="w-12 h-12 rounded object-cover" />}
          <span className="font-medium">{s.title}</span>
        </div>
      ))}
      {selected && episodes.length > 0 && (
        <div className="space-y-2 mt-4">
          <h2 className="text-gray-400 font-medium">{selected.title} — Episodes</h2>
          {episodes.map(ep => (
            <div key={ep.id}
              className="bg-gray-800 rounded p-3 flex justify-between items-center cursor-pointer hover:bg-gray-700"
              onClick={() => nav(`/player/${ep.id}`, { state: ep })}
            >
              <div className="min-w-0">
                <div className="text-sm font-medium truncate">{ep.title}</div>
                <div className="text-xs text-gray-500">{ep.published_at?.slice(0, 10)}</div>
              </div>
              <span className={`text-xs ml-4 px-2 py-1 rounded ${ep.transcript_status === "done" ? "bg-green-900 text-green-300" : "bg-gray-700 text-gray-400"}`}>
                {ep.transcript_status}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
