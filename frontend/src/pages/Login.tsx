import { useState, useRef } from "react";

// ─── Slide data ───────────────────────────────────────────────────────────────

const SLIDES = [
  {
    emoji: "⚗️",
    headline: "DistillPod",
    sub: "Hundreds of hours of podcasts,\ndistilled into what actually matters.",
    bg: "from-indigo-950 via-gray-950 to-gray-950",
    accent: "#818cf8", // indigo-400
  },
  {
    emoji: "💧",
    headline: "Distill any episode",
    sub: "Tap Distill while you listen.\nAI extracts the key ideas — no fluff, no filler.",
    bg: "from-violet-950 via-gray-950 to-gray-950",
    accent: "#a78bfa", // violet-400
  },
  {
    emoji: "🔒",
    headline: "How do I sign up?",
    sub: "You can't. This is just for me.\n\nThere are many podcast apps.\nThis one is mine.",
    bg: "from-gray-900 via-gray-950 to-gray-950",
    accent: "#9ca3af", // gray-400
  },
] as const;

// ─── Google logo SVG ──────────────────────────────────────────────────────────

function GoogleLogo() {
  return (
    <svg viewBox="0 0 24 24" className="w-5 h-5 flex-shrink-0" xmlns="http://www.w3.org/2000/svg">
      <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" fill="#4285F4" />
      <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853" />
      <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05" />
      <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335" />
    </svg>
  );
}

// ─── Login page ───────────────────────────────────────────────────────────────

export default function Login() {
  const [index, setIndex] = useState(0);
  const touchStartX = useRef<number | null>(null);

  const handleTouchStart = (e: React.TouchEvent) => {
    touchStartX.current = e.touches[0].clientX;
  };
  const handleTouchEnd = (e: React.TouchEvent) => {
    if (touchStartX.current === null) return;
    const delta = e.changedTouches[0].clientX - touchStartX.current;
    touchStartX.current = null;
    if (Math.abs(delta) < 40) return;
    if (delta < 0) setIndex(i => Math.min(i + 1, SLIDES.length - 1)); // swipe left → next
    else setIndex(i => Math.max(i - 1, 0));                            // swipe right → prev
  };

  const slide = SLIDES[index];

  return (
    // h-[100dvh] = dynamic viewport height (shrinks when mobile browser chrome hides)
    <div
      className={`h-[100dvh] flex flex-col bg-gradient-to-b ${slide.bg} transition-all duration-500`}
      style={{ paddingBottom: "env(safe-area-inset-bottom)", paddingTop: "env(safe-area-inset-top)" }}
    >
      {/* ── Slide area (swipeable) ── */}
      <div
        className="flex-1 flex flex-col items-center justify-center gap-6 px-10 select-none"
        onTouchStart={handleTouchStart}
        onTouchEnd={handleTouchEnd}
      >
        {/* Emoji */}
        <span
          className="text-8xl leading-none"
          style={{ filter: `drop-shadow(0 0 32px ${slide.accent}66)` }}
        >
          {slide.emoji}
        </span>

        {/* Text */}
        <div className="flex flex-col items-center gap-3 text-center">
          <h1
            className="text-3xl font-bold tracking-tight transition-colors duration-500 whitespace-pre-line"
            style={{ color: slide.accent }}
          >
            {slide.headline}
          </h1>
          <p className="text-gray-400 text-base leading-relaxed whitespace-pre-line">
            {slide.sub}
          </p>
        </div>


      </div>

      {/* ── Bottom bar ── */}
      <div className="flex flex-col items-center gap-5 px-6 pb-8">
        {/* Dot indicators */}
        <div className="flex items-center gap-2">
          {SLIDES.map((_, i) => (
            <button
              key={i}
              onClick={() => setIndex(i)}
              aria-label={`Slide ${i + 1}`}
              className="rounded-full transition-all duration-300"
              style={{
                width: i === index ? "20px" : "7px",
                height: "7px",
                background: i === index ? slide.accent : "#374151",
              }}
            />
          ))}
        </div>

        {/* Google sign-in */}
        <button
          onClick={() => { window.location.href = "/auth/google"; }}
          className="w-full max-w-sm flex items-center justify-center gap-3 bg-white text-gray-800 font-semibold py-3.5 px-6 rounded-2xl shadow-xl hover:bg-gray-50 active:scale-95 transition-all"
        >
          <GoogleLogo />
          Sign in with Google
        </button>

        <p className="text-gray-600 text-xs">Private. Just for you.</p>
      </div>
    </div>
  );
}
