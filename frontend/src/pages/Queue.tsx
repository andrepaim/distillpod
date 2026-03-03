import { useAudio } from "../context/AudioContext";
import { useQueue, type QueueItem } from "../stores/queueStore";
import {
  DndContext,
  closestCenter,
  PointerSensor,
  TouchSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

function fmtDur(secs?: number) {
  if (!secs) return null;
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  const s = Math.floor(secs % 60);
  if (h > 0) return `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  return `${m}:${String(s).padStart(2, "0")}`;
}

function SortableRow({ item }: { item: QueueItem }) {
  const { remove } = useQueue();
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: item.episodeId });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    ...(isDragging
      ? { transform: `${CSS.Transform.toString(transform)} scale(1.02)`, boxShadow: "0 4px 20px rgba(255,215,0,0.2)", zIndex: 50 }
      : {}),
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className="flex items-center gap-3 bg-[#242424] rounded-xl px-3 py-2.5"
    >
      {/* Drag handle */}
      <button
        {...attributes}
        {...listeners}
        className="flex-shrink-0 cursor-grab active:cursor-grabbing touch-none w-11 h-11 flex items-center justify-center"
        style={{ color: "#FFD700" }}
      >
        <span className="text-xl leading-none select-none">⠿</span>
      </button>

      {/* Thumbnail */}
      {item.imageUrl ? (
        <img src={item.imageUrl} className="w-10 h-10 rounded-lg object-cover flex-shrink-0" alt="" />
      ) : (
        <div className="w-10 h-10 rounded-lg bg-gray-700 flex-shrink-0 flex items-center justify-center text-base">🎧</div>
      )}

      {/* Info */}
      <div className="flex-1 min-w-0">
        <div className="text-sm font-bold text-white line-clamp-2 leading-snug">{item.title}</div>
        <div className="text-xs text-gray-500 truncate mt-0.5">{item.podcastTitle}</div>
      </div>

      {/* Duration */}
      {item.durationSeconds ? (
        <span className="text-xs text-gray-500 font-mono flex-shrink-0">{fmtDur(item.durationSeconds)}</span>
      ) : null}

      {/* Remove */}
      <button
        onClick={() => remove(item.episodeId)}
        className="text-gray-500 hover:text-red-400 flex-shrink-0 w-8 h-8 flex items-center justify-center rounded-full hover:bg-gray-700 transition-colors"
      >
        ✕
      </button>
    </div>
  );
}

export default function Queue() {
  const { queue, reorder, clear } = useQueue();
  const { episode, audioReady } = useAudio();

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 150, tolerance: 5 } }),
  );

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    const from = queue.findIndex(q => q.episodeId === active.id);
    const to = queue.findIndex(q => q.episodeId === over.id);
    if (from !== -1 && to !== -1) reorder(from, to);
  };

  const currentEpisode = episode && audioReady ? episode : null;

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-bold text-white">Up Next</h1>
        {queue.length > 0 && (
          <button
            onClick={clear}
            className="text-sm font-medium transition-colors"
            style={{ color: "#FFD700" }}
          >
            Clear all
          </button>
        )}
      </div>

      {/* Currently playing */}
      {currentEpisode && (
        <div className="bg-[#242424] rounded-xl px-4 py-3 flex items-center gap-3 border border-[#333]">
          <span
            className="text-xs font-bold px-2 py-0.5 rounded-full flex-shrink-0"
            style={{ color: "#FFD700", background: "rgba(255,215,0,0.15)" }}
          >
            ▶ Now playing
          </span>
          {currentEpisode.podcast_image ? (
            <img src={currentEpisode.podcast_image} className="w-10 h-10 rounded-lg object-cover flex-shrink-0" alt="" />
          ) : (
            <div className="w-10 h-10 rounded-lg bg-gray-700 flex-shrink-0 flex items-center justify-center text-base">🎧</div>
          )}
          <div className="flex-1 min-w-0">
            <div className="text-sm font-bold text-white truncate">{currentEpisode.title}</div>
            {currentEpisode.podcast_title && (
              <div className="text-xs text-gray-500 truncate">{currentEpisode.podcast_title}</div>
            )}
          </div>
        </div>
      )}

      {/* Queue list or empty state */}
      {queue.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <span className="text-5xl text-gray-600 mb-4">≡</span>
          <p className="text-white font-medium text-base">Your queue is empty</p>
          <p className="text-gray-500 text-sm mt-1">Add episodes from the feed or player</p>
        </div>
      ) : (
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
          <SortableContext items={queue.map(q => q.episodeId)} strategy={verticalListSortingStrategy}>
            <div className="space-y-2">
              {queue.map(item => (
                <SortableRow key={item.episodeId} item={item} />
              ))}
            </div>
          </SortableContext>
        </DndContext>
      )}
    </div>
  );
}
