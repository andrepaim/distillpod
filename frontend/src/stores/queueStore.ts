import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface QueueItem {
  episodeId: string;
  title: string;
  podcastTitle: string;
  audioUrl: string;
  imageUrl?: string;
  durationSeconds?: number;
}

interface QueueStore {
  queue: QueueItem[];
  addNext: (item: QueueItem) => void;
  addToEnd: (item: QueueItem) => void;
  remove: (episodeId: string) => void;
  reorder: (from: number, to: number) => void;
  clear: () => void;
  shift: () => QueueItem | undefined;
}

export const useQueue = create<QueueStore>()(
  persist(
    (set, get) => ({
      queue: [],
      addNext: (item) => set((s) => {
        const filtered = s.queue.filter(q => q.episodeId !== item.episodeId);
        return { queue: [item, ...filtered] };
      }),
      addToEnd: (item) => set((s) => {
        const filtered = s.queue.filter(q => q.episodeId !== item.episodeId);
        return { queue: [...filtered, item] };
      }),
      remove: (id) => set((s) => ({ queue: s.queue.filter(q => q.episodeId !== id) })),
      reorder: (from, to) => set((s) => {
        const q = [...s.queue];
        const [moved] = q.splice(from, 1);
        q.splice(to, 0, moved);
        return { queue: q };
      }),
      clear: () => set({ queue: [] }),
      shift: () => {
        const q = get().queue;
        if (q.length === 0) return undefined;
        const next = q[0];
        set({ queue: q.slice(1) });
        return next;
      },
    }),
    { name: 'distillpod-queue' }
  )
);
