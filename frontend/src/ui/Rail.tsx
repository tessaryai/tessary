// SPDX-License-Identifier: Apache-2.0
// THE right rail. Width is drag-resizable and remembered per browser, not per rail. A rail never opens
// a second rail; the opener drives `open` from the URL. A plain <aside>, not <dialog>, so the page
// behind stays scrollable; focus is trapped by useFocusTrap.
import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { createPortal } from "react-dom";
import { ExternalLink, X } from "lucide-react";
import { cn } from "./cn";
import { useEscToClose } from "./escStack";
import { useFocusTrap } from "./useFocusTrap";

const WIDTH_STORAGE_KEY = "tsy-rail-width";
const MIN_WIDTH = 520;
const DEFAULT_WIDTH_RATIO = 0.44;

/** On the primitive, not each consumer: when it was the consumer's job all three forgot it. */
const BODY_PADDING = "px-[18px] pt-[18px] pb-10";

function clamp(n: number, min: number, max: number): number {
  return Math.min(Math.max(n, min), max);
}

function maxWidth(): number {
  return Math.max(MIN_WIDTH, window.innerWidth * 0.85);
}

function readStoredWidth(): number | null {
  try {
    const raw = window.localStorage.getItem(WIDTH_STORAGE_KEY);
    const n = raw ? Number(raw) : NaN;
    return Number.isFinite(n) ? clamp(n, MIN_WIDTH, maxWidth()) : null;
  } catch {
    return null;
  }
}

function useRailWidth(open: boolean) {
  const [width, setWidth] = useState<number>(
    () => readStoredWidth() ?? clamp(window.innerWidth * DEFAULT_WIDTH_RATIO, MIN_WIDTH, maxWidth()),
  );
  const widthRef = useRef(width);
  widthRef.current = width;
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    if (!open) return;
    const stored = readStoredWidth();
    if (stored != null) setWidth(stored);
  }, [open]);

  const onHandlePointerDown = useCallback((e: React.PointerEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startWidth = widthRef.current;
    setDragging(true);

    function onMove(ev: PointerEvent) {
      const dx = startX - ev.clientX;
      setWidth(clamp(startWidth + dx, MIN_WIDTH, maxWidth()));
    }
    function onUp() {
      setDragging(false);
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      try {
        window.localStorage.setItem(WIDTH_STORAGE_KEY, String(widthRef.current));
      } catch {
      }
    }
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  }, []);

  return { width, dragging, onHandlePointerDown };
}

export type RailProps = {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  monoTitle?: boolean;
  meta?: ReactNode;
  onExpand?: () => void;
  expandLabel?: string;
  children: ReactNode;
  "aria-label"?: string;
};

export function Rail({
  open,
  onClose,
  title,
  monoTitle,
  meta,
  onExpand,
  expandLabel,
  children,
  "aria-label": ariaLabel,
}: RailProps) {
  const trapRef = useFocusTrap<HTMLElement>(open);
  const { width, dragging, onHandlePointerDown } = useRailWidth(open);

  // Only the topmost overlay acts: in trace-rail mode onClose navigates, so a rail under another must
  // not also close.
  useEscToClose(open, onClose);

  if (!open) return null;

  return createPortal(
    <aside
      ref={trapRef}
      role="dialog"
      aria-modal={false}
      aria-label={ariaLabel ?? (typeof title === "string" ? title : undefined)}
      tabIndex={-1}
      className="fixed inset-y-0 right-0 z-[60] flex flex-col bg-surface border-l border-border-strong outline-none"
      style={{
        width,
        minWidth: MIN_WIDTH,
        boxShadow: "var(--shadow-md)",
        animation: dragging ? undefined : "tsyRail .18s var(--ease-enter)" }}
    >
      <div
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize panel"
        onPointerDown={onHandlePointerDown}
        className="group absolute inset-y-0 left-0 z-10 -ml-1.5 w-3 cursor-col-resize touch-none select-none">
        <div
          className={cn("mx-auto h-full w-px transition-colors", dragging ? "bg-accent" : "bg-transparent group-hover:bg-accent")}
          style={{ transitionDuration: "var(--duration-micro)" }}
        />
      </div>
      <div className="flex items-center gap-3 px-[18px] py-3.5 border-b border-border shrink-0">
        <div className={cn("min-w-0 truncate text-code text-fg", monoTitle && "font-mono")}>{title}</div>
        {meta != null && <div className="min-w-0 truncate text-small text-subtle">{meta}</div>}
        <div className="flex-1" />
        {onExpand && (
          <button
            type="button"
            onClick={onExpand}
            aria-label={expandLabel ?? "Expand"}
            title={expandLabel ?? "Expand"}
            className="shrink-0 rounded-control p-1 text-muted hover:text-fg hover:bg-hover transition-colors"
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            <ExternalLink size={14} strokeWidth={1.75} aria-hidden="true" />
          </button>
        )}
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          title="Close (Esc)"
          className="shrink-0 rounded-control border border-border-strong p-1 text-muted hover:text-fg hover:bg-hover transition-colors"
          style={{ transitionDuration: "var(--duration-micro)" }}
        >
          <X size={14} strokeWidth={1.75} aria-hidden="true" />
        </button>
      </div>
      <div className={cn("flex-1 min-h-0 overflow-y-auto", BODY_PADDING)}>{children}</div>
    </aside>,
    document.body,
  );
}
