import { useState, useEffect } from "react";
import { getCached, setCached } from "./cache";
import { getSubscriptions, getEpisodes } from "./api/client";
import { useQueue } from "./stores/queueStore";
import { BrowserRouter, Routes, Route, useLocation, useNavigate } from "react-router-dom";
import { AudioProvider, useAudio } from "./context/AudioContext";
import MiniPlayer from "./components/MiniPlayer";
import Home from "./pages/Home";
import Search from "./pages/Search";
import Queue from './pages/Queue';
import Subscriptions from "./pages/Subscriptions";
import Player from "./pages/Player";
import Gists from "./pages/Gists";
import Chat from "./pages/Chat";
import Login from "./pages/Login";
import Unauthorized from "./pages/Unauthorized";

// ─── Icons ────────────────────────────────────────────────────────────────────
const HomeIcon = ({ active }: { active: boolean }) => (
  <svg viewBox="0 0 24 24" fill={active ? "currentColor" : "none"} stroke="currentColor" strokeWidth={active ? 0 : 2} strokeLinecap="round" strokeLinejoin="round" className="w-6 h-6">
    <path d="M3 9.75L12 3l9 6.75V21a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V9.75z" />
    <rect x="9" y="12" width="6" height="10" fill={active ? "white" : "none"} opacity={active ? 0.3 : 0} />
  </svg>
);

const SearchIcon = ({ active }: { active: boolean }) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={active ? 2.5 : 2} strokeLinecap="round" strokeLinejoin="round" className="w-6 h-6">
    <circle cx="11" cy="11" r="7" />
    <line x1="21" y1="21" x2="16.65" y2="16.65" />
  </svg>
);

const LibraryIcon = ({ active }: { active: boolean }) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={active ? 2.5 : 2} strokeLinecap="round" strokeLinejoin="round" className="w-6 h-6">
    <path d="M3 18v-6a9 9 0 0 1 18 0v6" />
    <path d="M21 19a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3zM3 19a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H3z" />
  </svg>
);

const QueueIcon = ({ active }: { active: boolean }) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="w-5 h-5">
    <line x1="8" y1="6" x2="21" y2="6" /><line x1="8" y1="12" x2="21" y2="12" /><line x1="8" y1="18" x2="21" y2="18" />
    <line x1="3" y1="6" x2="3.01" y2="6" /><line x1="3" y1="12" x2="3.01" y2="12" /><line x1="3" y1="18" x2="3.01" y2="18" />
  </svg>
);

const GistsIcon = ({ active }: { active: boolean }) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={active ? 2.5 : 2} strokeLinecap="round" strokeLinejoin="round" className="w-6 h-6">
    <circle cx="6" cy="6" r="3" />
    <circle cx="6" cy="18" r="3" />
    <line x1="20" y1="4" x2="8.12" y2="15.88" />
    <line x1="14.47" y1="14.48" x2="20" y2="20" />
    <line x1="8.12" y1="8.12" x2="12" y2="12" />
  </svg>
);

// ─── Bottom nav ───────────────────────────────────────────────────────────────
const tabs = [
  { to: "/",              label: "Home",    Icon: HomeIcon    },
  { to: "/search",        label: "Search",  Icon: SearchIcon  },
  { to: "/queue",         label: "Queue",   Icon: QueueIcon   },
  { to: "/subscriptions", label: "Library", Icon: LibraryIcon },
  { to: "/gists",         label: "Distills",Icon: GistsIcon   },
];

function BottomNav() {
  const location = useLocation();
  const navigate = useNavigate();
  const { queue } = useQueue();
  const isActive = (to: string) =>
    to === "/" ? location.pathname === "/" : location.pathname.startsWith(to);

  return (
    <nav
      className="fixed bottom-0 left-0 right-0 bg-gray-900 border-t border-gray-800 flex z-50"
      style={{ paddingBottom: "env(safe-area-inset-bottom)" }}
    >
      {tabs.map(({ to, label, Icon }) => {
        const active = isActive(to);
        return (
          <button
            key={to}
            onClick={() => navigate(to)}
            className={`relative flex-1 flex flex-col items-center justify-center gap-1 py-2.5 transition-colors ${
              active ? "text-indigo-400" : "text-gray-500 hover:text-gray-300"
            }`}
          >
            <Icon active={active} />
            <span className={`text-xs font-medium ${active ? "text-indigo-400" : "text-gray-500"}`}>
              {label}
            </span>
            {active && (
              <span className="absolute top-0 left-1/2 -translate-x-1/2 w-8 h-0.5 bg-indigo-400 rounded-b-full" />
            )}
          </button>
        );
      })}
    </nav>
  );
}

// ─── App shell (needs useAudio → must be inside AudioProvider) ────────────────
function AppShell() {
  const { audioReady } = useAudio();

  // Prefetch all subscribed podcast episodes on mount (warm the cache silently)
  useEffect(() => {
    getSubscriptions().then(subs => {
      subs.forEach(sub => {
        const key = `episodes:${sub.podcast_id}`;
        if (!getCached(key)) {
          getEpisodes(sub.podcast_id).then(eps => setCached(key, eps)).catch(() => {});
        }
      });
    }).catch(() => {});
  }, []);

  return (
    <div className="min-h-screen bg-gray-950 text-white flex flex-col">
      <header
        className="sticky top-0 z-40 bg-gray-900 border-b border-gray-800 px-4 py-3"
        style={{ paddingTop: "max(0.75rem, env(safe-area-inset-top))" }}
      >
        <span className="font-bold text-indigo-400 text-lg tracking-tight">⚗️ DistillPod</span>
      </header>

      {/* Extra bottom padding when mini player is visible */}
      <main className={`flex-1 p-4 max-w-3xl mx-auto w-full transition-[padding] ${
        audioReady ? "pb-36" : "pb-24"
      }`}>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/search" element={<Search />} />
          <Route path="/subscriptions" element={<Subscriptions />} />
          <Route path="/player/:episodeId" element={<Player />} />
          <Route path="/player/:episodeId/chat" element={<Chat />} />
          <Route path="/queue" element={<Queue />} />
          <Route path="/gists" element={<Gists />} />
          <Route path="*" element={<Home />} />
        </Routes>
      </main>

      <MiniPlayer />   {/* sits above BottomNav when active */}
      <BottomNav />
    </div>
  );
}

// ─── Root ─────────────────────────────────────────────────────────────────────
interface User { email: string; name: string; picture: string; }

export default function App() {
  const [user, setUser] = useState<User | null | "loading">("loading");

  useEffect(() => {
    fetch("/auth/me", { credentials: "include" })
      .then(r => (r.ok ? r.json() : null))
      .then(u => setUser(u))
      .catch(() => setUser(null));
  }, []);

  if (user === "loading") {
    return (
      <div className="min-h-screen bg-gray-950 flex items-center justify-center">
        <span className="text-4xl animate-pulse select-none">⚗️</span>
      </div>
    );
  }

  if (!user) {
    if (window.location.pathname === "/unauthorized") return <Unauthorized />;
    return <Login />;
  }

  return (
    <BrowserRouter>
      <AudioProvider>
        <AppShell />
      </AudioProvider>
    </BrowserRouter>
  );
}
