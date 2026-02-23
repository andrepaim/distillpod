import { BrowserRouter, Routes, Route, NavLink } from "react-router-dom";
import Search from "./pages/Search";
import Subscriptions from "./pages/Subscriptions";
import Player from "./pages/Player";

export default function App() {
  return (
    <BrowserRouter>
      <div className="min-h-screen bg-gray-950 text-white flex flex-col">
        <nav className="bg-gray-900 border-b border-gray-800 px-4 py-3 flex gap-6">
          <span className="font-bold text-indigo-400 text-lg">🎙 PodSnip</span>
          <NavLink to="/" className={({ isActive }) => isActive ? "text-white" : "text-gray-400 hover:text-white"}>Search</NavLink>
          <NavLink to="/subscriptions" className={({ isActive }) => isActive ? "text-white" : "text-gray-400 hover:text-white"}>Library</NavLink>
          <NavLink to="/snips" className={({ isActive }) => isActive ? "text-white" : "text-gray-400 hover:text-white"}>Snips</NavLink>
        </nav>
        <main className="flex-1 p-4 max-w-3xl mx-auto w-full">
          <Routes>
            <Route path="/" element={<Search />} />
            <Route path="/subscriptions" element={<Subscriptions />} />
            <Route path="/player/:episodeId" element={<Player />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  );
}
