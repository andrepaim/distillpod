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
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
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

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
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
        className="text-gray-500 hover:text-gray-300 flex-shrink-0 cursor-grab active:cursor-grabbing touch-none"
      >
        <span className="text-lg leading-none select-none">⠿</span>
      </button>

      {/* Thumbnail */}
      {item.imageUrl ? (
        <img src={item.imageUrl} className="w-10 h-10 rounded-lg object-cover flex-shrink-0" alt="" />
      ) : (
        <div className="w-10 h-10 rounded-lg bg-gray-700 flex-shrink-0 flex items-center justify-center text-base">🎧</div>
      )}

      {/* Info */}
      <div className="flex-1 min-w-0">
        <div className="text-sm font-medium text-white truncate">{item.title}</div>
        <div className="text-xs text-gray-500 truncate">
          {item.podcastTitle}
          {item.durationSeconds ? ` · ${fmtDur(item.durationSeconds)}` : ""}
        </div>
      </div>

      {/* Remove */}
      <button
        onClick={() => remove(item.episodeId)}
        className="text-gray-500 hover:text-red-400 flex-shrink-0 w-7 h-7 flex items-center justify-center rounded-full hover:bg-gray-700 transition-colors"
      >
        ✕
      </button>
    </div>
  );
}

export default function QueueSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { queue, reorder, clear } = useQueue();

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

  return (
    <>
      {/* Backdrop */}
      <div
        className={`fixed inset-0 bg-black/60 z-50 transition-opacity ${open ? "opacity-100" : "opacity-0 pointer-events-none"}`}
        onClick={onClose}
      />

      {/* Sheet */}
      <div
        className={`fixed left-0 right-0 bottom-0 z-50 bg-[#1A1A1A] rounded-t-2xl transition-transform duration-300 ${
          open ? "translate-y-0" : "translate-y-full"
        }`}
        style={{ maxHeight: "70vh", paddingBottom: "env(safe-area-inset-bottom)" }}
      >
        {/* Handle bar */}
        <div className="flex justify-center pt-3 pb-1">
          <div className="w-10 h-1 rounded-full bg-gray-600" />
        </div>

        {/* Header */}
        <div className="flex items-center justify-between px-4 pb-3">
          <h2 className="font-bold text-base" style={{ color: "#FFD700" }}>
            Up next{queue.length > 0 ? ` (${queue.length})` : ""}
          </h2>
          {queue.length > 0 && (
            <button
              onClick={clear}
              className="text-xs text-gray-400 hover:text-white transition-colors"
            >
              Clear all
            </button>
          )}
        </div>

        {/* List */}
        <div className="overflow-y-auto px-4 pb-4 space-y-2" style={{ maxHeight: "calc(70vh - 80px)" }}>
          {queue.length === 0 ? (
            <div className="text-center py-10 text-gray-500 text-sm">
              Your queue is empty
            </div>
          ) : (
            <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
              <SortableContext items={queue.map(q => q.episodeId)} strategy={verticalListSortingStrategy}>
                {queue.map(item => (
                  <SortableRow key={item.episodeId} item={item} />
                ))}
              </SortableContext>
            </DndContext>
          )}
        </div>
      </div>
    </>
  );
}
