const PREFIX = "distillpod:cache:";
const TTL_MS = 30 * 60 * 1000; // 30 minutes

export function getCached<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    if (!raw) return null;
    const { data, ts } = JSON.parse(raw);
    if (Date.now() - ts > TTL_MS) return null;
    return data as T;
  } catch { return null; }
}

export function setCached<T>(key: string, data: T): void {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify({ data, ts: Date.now() }));
  } catch {}
}

export function bustCache(key: string): void {
  localStorage.removeItem(PREFIX + key);
}
