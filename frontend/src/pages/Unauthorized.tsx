import { useState } from "react";

const REPO = "github.com/andrepaim/distillpod";

export default function Unauthorized() {
  const [copied, setCopied] = useState(false);
  const [copiedPrompt, setCopiedPrompt] = useState(false);
  const PROMPT = `Clone ${REPO}, set it up on my VPS and personalize it for me.`;
  const copy = () => {
    navigator.clipboard.writeText(`https://${REPO}`);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };
  const copyPrompt = () => {
    navigator.clipboard.writeText(PROMPT);
    setCopiedPrompt(true);
    setTimeout(() => setCopiedPrompt(false), 2000);
  };

  return (
    <div
      className="h-[100dvh] bg-gray-950 text-white flex flex-col justify-between px-6 py-8"
      style={{ paddingBottom: "max(2rem, env(safe-area-inset-bottom))", paddingTop: "max(2rem, env(safe-area-inset-top))" }}
    >
      {/* ── Top: brand + headline ── */}
      <div className="flex flex-col items-center gap-1 text-center">
        <span className="text-5xl" style={{ filter: "drop-shadow(0 0 20px #818cf855)" }}>⚗️</span>
        <span className="text-base font-bold text-indigo-400 tracking-tight">DistillPod</span>
        <h1 className="text-xl font-bold text-white mt-2 leading-snug">
          Andre shared a distillation with you
        </h1>
        <p className="text-gray-500 text-xs mt-1">
          This app is private — access is his alone.
        </p>
      </div>

      {/* ── Middle: explanation ── */}
      <div className="flex flex-col gap-2 bg-gray-900 border border-gray-800 rounded-2xl p-4">
        <p className="text-xs font-semibold text-indigo-400 uppercase tracking-widest">What is this?</p>
        <p className="text-gray-400 text-xs leading-relaxed">
          DistillPod is Andre's personal podcast app. It transcribes episodes locally with
          faster-whisper, then distills key moments into quotes and insights — zero per-call cost.
        </p>
        <p className="text-gray-400 text-xs leading-relaxed">
          The AI runs via the{" "}
          <code className="text-indigo-300 bg-gray-800 px-1 rounded">claude</code>{" "}
          CLI — called as a subprocess, authenticated through a Claude subscription.
          No separate API billing.
        </p>
      </div>

      {/* ── Bottom: clone CTA ── */}
      <div className="flex flex-col gap-3">
        <div className="text-center">
          <p className="text-white text-sm font-semibold">Want your own?</p>
          <p className="text-gray-500 text-xs mt-0.5">
            The repo is public — self-hostable on any VPS.
          </p>
        </div>

        {/* Repo */}
        <div className="flex items-center justify-between gap-3 bg-gray-900 border border-gray-700 rounded-xl px-4 py-2.5">
          <span className="text-indigo-300 text-xs font-mono truncate">{REPO}</span>
          <button
            onClick={copy}
            className="flex-shrink-0 text-xs px-3 py-1 rounded-lg border transition-all"
            style={{ borderColor: copied ? "#818cf8" : "#374151", color: copied ? "#818cf8" : "#6b7280" }}
          >
            {copied ? "✓ copied" : "copy"}
          </button>
        </div>

        {/* Prompt hint */}
        <div className="bg-gray-900 border border-dashed border-gray-800 rounded-xl px-4 py-2.5 flex flex-col gap-1.5">
          <p className="text-gray-600 text-xs">If you have OpenClaw or any other AI bot, ask it:</p>
          <div className="flex items-start justify-between gap-3">
            <p className="text-gray-400 text-xs italic leading-relaxed">
              "{PROMPT}"
            </p>
            <button
              onClick={copyPrompt}
              className="flex-shrink-0 text-xs px-3 py-1 rounded-lg border transition-all"
              style={{ borderColor: copiedPrompt ? "#818cf8" : "#374151", color: copiedPrompt ? "#818cf8" : "#6b7280" }}
            >
              {copiedPrompt ? "✓ copied" : "copy"}
            </button>
          </div>
        </div>
      </div>

      {/* ── Footer ── */}
      <p className="text-center text-gray-700 text-xs italic">
        There are many podcast apps. This one is his.
      </p>
    </div>
  );
}
