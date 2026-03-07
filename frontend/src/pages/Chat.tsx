import React, { useEffect, useRef, useState } from "react";
import { useParams, useLocation, useNavigate } from "react-router-dom";
import { getChat, initChat, sendChatMessage, ChatMessage } from "../api/client";
import { useAudio } from "../context/AudioContext";
import ReactMarkdown from "react-markdown";

const markdownComponents = {
  li: ({ children, ...props }: any) => {
    // Unwrap <p> tags that react-markdown adds inside list items
    // when source markdown has blank lines between items.
    // This prevents the list marker from being orphaned on its own line.
    const unwrapped = React.Children.map(children, (child: React.ReactNode) => {
      if (React.isValidElement(child) && child.type === "p") {
        return <>{child.props.children}</>;
      }
      return child;
    });
    return <li {...props}>{unwrapped}</li>;
  },
};

export default function Chat() {
  const { episodeId } = useParams<{ episodeId: string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const episodeTitle = (location.state as { episodeTitle?: string } | null)?.episodeTitle ?? "Episode";
  const { episode: audioEpisode } = useAudio();
  const bottomOffset = audioEpisode ? "calc(56px + 56px + env(safe-area-inset-bottom))" : "calc(56px + env(safe-area-inset-bottom))";

  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [initializing, setInitializing] = useState(true);
  const bottomRef = useRef<HTMLDivElement>(null);

  const scrollToBottom = () => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages, loading]);

  useEffect(() => {
    if (!episodeId) return;
    let cancelled = false;
    (async () => {
      setInitializing(true);
      try {
        const existing = await getChat(episodeId);
        if (cancelled) return;
        if (existing.length > 0) {
          setMessages(existing);
        } else {
          setLoading(true);
          const first = await initChat(episodeId);
          if (cancelled) return;
          setMessages([first]);
          setLoading(false);
        }
      } catch {
        // ignore
      } finally {
        if (!cancelled) setInitializing(false);
      }
    })();
    return () => { cancelled = true; };
  }, [episodeId]);

  const handleSend = async () => {
    if (!input.trim() || !episodeId || loading) return;
    const text = input.trim();
    setInput("");
    const userMsg: ChatMessage = {
      id: crypto.randomUUID(),
      role: "user",
      content: text,
      created_at: new Date().toISOString(),
    };
    setMessages(prev => [...prev, userMsg]);
    setLoading(true);
    try {
      const reply = await sendChatMessage(episodeId, text);
      setMessages(prev => [...prev, reply]);
    } catch {
      setMessages(prev => [
        ...prev,
        { id: crypto.randomUUID(), role: "assistant", content: "Sorry, something went wrong. Please try again.", created_at: new Date().toISOString() },
      ]);
    } finally {
      setLoading(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="fixed top-0 left-0 right-0 flex flex-col" style={{ background: "#1A1A1A", bottom: bottomOffset }}>
      {/* Header */}
      <div className="flex items-center gap-3 px-4 py-3 border-b border-gray-800 bg-gray-900" style={{ paddingTop: "max(0.75rem, env(safe-area-inset-top))" }}>
        <button
          onClick={() => navigate(-1)}
          className="text-gray-300 hover:text-white transition-colors"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="w-6 h-6">
            <polyline points="15 18 9 12 15 6" />
          </svg>
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="text-sm font-bold truncate" style={{ color: "#FFD700" }}>Chat</h1>
          <p className="text-xs text-gray-400 truncate">{episodeTitle}</p>
        </div>
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto px-4 py-4 space-y-3">
        {initializing && messages.length === 0 && (
          <div className="flex items-center justify-center h-full">
            <div className="text-gray-500 text-sm flex items-center gap-2">
              <span className="w-4 h-4 border-2 border-gray-500 border-t-yellow-400 rounded-full animate-spin inline-block" />
              Loading conversation...
            </div>
          </div>
        )}

        {messages.map((msg) => (
          <div key={msg.id} className={`flex ${msg.role === "user" ? "justify-end" : "justify-start"}`}>
            <div
              className={`selectable max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed ${
                msg.role === "assistant"
                  ? "bg-gray-800 text-gray-100"
                  : "bg-gray-800"
              }`}
              style={msg.role === "user" ? { color: "#FFD700", whiteSpace: "pre-wrap" } : undefined}
            >
              {msg.role === "assistant"
                ? <div className="markdown-chat"><ReactMarkdown components={markdownComponents}>{msg.content}</ReactMarkdown></div>
                : msg.content}
            </div>
          </div>
        ))}

        {loading && (
          <div className="flex justify-start">
            <div className="bg-gray-800 rounded-2xl px-4 py-2.5 text-sm text-gray-400">
              <span className="inline-flex gap-1">
                <span className="w-2 h-2 bg-gray-500 rounded-full animate-bounce" style={{ animationDelay: "0ms" }} />
                <span className="w-2 h-2 bg-gray-500 rounded-full animate-bounce" style={{ animationDelay: "150ms" }} />
                <span className="w-2 h-2 bg-gray-500 rounded-full animate-bounce" style={{ animationDelay: "300ms" }} />
              </span>
            </div>
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      {/* Input */}
      <div className="border-t border-gray-800 bg-gray-900 px-4 py-3" style={{ paddingBottom: "max(0.75rem, env(safe-area-inset-bottom))" }}>
        <div className="flex gap-2 items-end">
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Ask about this episode..."
            rows={1}
            className="flex-1 bg-gray-800 text-white placeholder-gray-500 rounded-xl px-4 py-2.5 text-sm resize-none focus:outline-none focus:ring-1"
            style={{ focusRingColor: "#FFD700" } as React.CSSProperties}
            disabled={loading}
          />
          <button
            onClick={handleSend}
            disabled={!input.trim() || loading}
            className="flex-shrink-0 w-10 h-10 rounded-xl flex items-center justify-center transition-colors disabled:opacity-40"
            style={{ background: "#FFD700" }}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="#1A1A1A" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round" className="w-5 h-5">
              <line x1="22" y1="2" x2="11" y2="13" />
              <polygon points="22 2 15 22 11 13 2 9 22 2" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  );
}
