import { useState, useRef } from "react";

// ─── Slide data ───────────────────────────────────────────────────────────────

const SLIDES = [
  {
    emoji: "⚗️",
    headline: "DistillPod",
    sub: "There are many podcast apps.\nThis one is mine.",
    accent: "text-indigo-400",
  },
  {
    emoji: "✂️",
    headline: "Gist any episode",
    sub: "AI summaries in seconds.\nGet the signal, skip the noise.",
    accent: "text-violet-400",
  },
  {
    emoji: "🎧",
    headline: "Your queue, your rules",
    sub: "Progress saved. Mini player always ready.\nOffline-friendly.",
    accent: "text-sky-400",
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

// ─── Carousel ─────────────────────────────────────────────────────────────────

function Carousel({ index, onIndexChange }: { index: number; onIndexChange: (i: number) => void }) {
  const touchStartX = useRef<number | null>(null);

  const handleTouchStart = (e: React.TouchEvent) => {
    touchStartX.current = e.touches[0].clientX;
  };

  const handleTouchEnd = (e: React.TouchEvent) => {
    if (touchStartX.current === null) return;
    const delta = e.changedTouches[0].clientX - touchStartX.current;
    touchStartX.current = null;
    if (Math.abs(delta) < 40) return;
    if (delta < 0 && index < SLIDES.length - 1) onIndexChange(index + 1);
    if (delta > 0 && index > 0) onIndexChange(index - 1);
  };

  return (
    <div
      className="flex-1 relative overflow-hidden select-none"
      onTouchStart={handleTouchStart}
      onTouchEnd={handleTouchEnd}
    >
      {/* Slide track */}
      <div
        className="flex h-full"
        style={{
          transform: `translateX(-${index * 100}%)`,
          transition: "transform 320ms cubic-bezier(0.4,0,0.2,1)",
          width: `${SLIDES.length * 100}%`,
        }}
      >
        {SLIDES.map((slide, i) => (
          <div
            key={i}
            className="flex flex-col items-center justify-center gap-5 px-8"
            style={{ width: `${100 / SLIDES.length}%` }}
          >
            <span
              className="text-7xl"
              style={{ filter: "drop-shadow(0 0 24px rgba(99,102,241,0.35))" }}
            >
              {slide.emoji}
            </span>

            <div className="flex flex-col items-center gap-2 text-center">
              <h1 className={`text-3xl font-bold tracking-tight ${slide.accent}`}>
                {slide.headline}
              </h1>
              <p className="text-gray-400 text-sm leading-relaxed whitespace-pre-line">
                {slide.sub}
              </p>
            </div>
          </div>
        ))}
      </div>

      {/* Left / right tap zones (desktop UX) */}
      {index > 0 && (
        <button
          onClick={() => onIndexChange(index - 1)}
          className="absolute left-0 top-0 h-full w-12 flex items-center justify-start pl-2 text-gray-600 hover:text-gray-400 transition-colors"
          aria-label="Previous"
        >
          <svg viewBox="0 0 24 24" className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth={2}>
            <path d="M15 18l-6-6 6-6" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
      )}
      {index < SLIDES.length - 1 && (
        <button
          onClick={() => onIndexChange(index + 1)}
          className="absolute right-0 top-0 h-full w-12 flex items-center justify-end pr-2 text-gray-600 hover:text-gray-400 transition-colors"
          aria-label="Next"
        >
          <svg viewBox="0 0 24 24" className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth={2}>
            <path d="M9 18l6-6-6-6" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
      )}
    </div>
  );
}

// ─── Dot indicators ───────────────────────────────────────────────────────────

function Dots({ index, total, onDotClick }: { index: number; total: number; onDotClick: (i: number) => void }) {
  return (
    <div className="flex items-center justify-center gap-2 py-4">
      {Array.from({ length: total }).map((_, i) => (
        <button
          key={i}
          onClick={() => onDotClick(i)}
          aria-label={`Go to slide ${i + 1}`}
          className="transition-all duration-300 rounded-full"
          style={{
            width: i === index ? "20px" : "7px",
            height: "7px",
            background: i === index ? "#818cf8" : "#374151",
          }}
        />
      ))}
    </div>
  );
}

// ─── Login page ───────────────────────────────────────────────────────────────

export default function Login() {
  const [index, setIndex] = useState(0);

  const handleGoogleLogin = () => {
    window.location.href = "/auth/google";
  };

  return (
    <div
      className="min-h-screen bg-gray-950 flex flex-col"
      style={{ paddingBottom: "env(safe-area-inset-bottom)" }}
    >
      {/* Carousel fills the upper portion */}
      <Carousel index={index} onIndexChange={setIndex} />

      {/* Bottom section — dots + CTA */}
      <div className="flex flex-col items-center gap-4 px-6 pb-10">
        <Dots index={index} total={SLIDES.length} onDotClick={setIndex} />

        <button
          onClick={handleGoogleLogin}
          className="w-full max-w-sm flex items-center justify-center gap-3 bg-white text-gray-800 font-medium py-3.5 px-6 rounded-2xl shadow-lg hover:bg-gray-100 active:bg-gray-200 active:scale-95 transition-all"
        >
          <GoogleLogo />
          Sign in with Google
        </button>

        <p className="text-gray-600 text-xs text-center">
          Private. Just for you.
        </p>
      </div>
    </div>
  );
}
