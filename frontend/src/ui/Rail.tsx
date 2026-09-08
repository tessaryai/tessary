// SPDX-License-Identifier: Apache-2.0
/*
 * Rail — THE right rail. One system, everywhere (DESIGN-DIRECTION §6):
 *
 *   - one shape for every rail: defaults to 44% of the viewport (min 520px),
 *     but the left edge is drag-to-resize — width is remembered per browser
 *     (localStorage), not per-rail, so every rail opens at the size you left it;
 *   - opens from any row click; ESC closes; slide-in via the `tsyRail` keyframes;
 *   - optional header slot: title (mono when it's an identifier) + dim meta +
 *     an external-link expand + a close (X) button — icons, not text glyphs;
 *   - the body carries its own inset (see BODY_PADDING) — consumers pass content,
 *     never padding;
 *   - a rail never opens a second rail — drilling deeper navigates the rail's
 *     own content (render a breadcrumb back inside `children`);
 *   - rail states should be URL-addressable — the OPENER owns that (drive `open`
 *     from the URL); the primitive only renders.
 *
 * Modals survive only as confirmations; everything else is this rail.
 * Built from a plain <aside> (not <dialog>) so the page behind stays visible and
 * scrollable; focus is trapped by useFocusTrap and restored on close.
 */
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

/**
 * The body's inset. Horizontal matches the header's `px-[18px]` so a rail reads as one
 * column from title to last row; the deep bottom is scroll breathing room, so the final
 * section clears the viewport edge instead of ending flush against it.
 *
 * This lives on the primitive, not on each consumer: every rail wants the same inset, and
 * when it was the consumer's job all three of them forgot and rendered text against the
 * panel edge.
 */
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

/** Drag-to-resize handle on the rail's left edge. Persists to `WIDTH_STORAGE_KEY` on release. */
function useRailWidth(open: boolean) {
  const [width, setWidth] = useState<number>(
    () => readStoredWidth() ?? clamp(window.innerWidth * DEFAULT_WIDTH_RATIO, MIN_WIDTH, maxWidth()),
  );
  const widthRef = useRef(width);
  widthRef.current = width;
  const [dragging, setDragging] = useState(false);

  // Re-read on every open so a width persisted from another rail applies here too.
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
      // Rail is anchored to the right edge, so dragging left (dx > 0) grows it.
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
        // Storage unavailable (private mode, quota) — width just won't persist.
      }
    }
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  }, []);

  return { width, dragging, onHandlePointerDown };
}

export type RailProps = {
  open: boolean;
  /** Called on ESC (key or button) and when the ⤢/consumer wants to dismiss. */
  onClose: () => void;
  /** Header title. Set `monoTitle` when it is an identifier (`tr_8VQZ…`, `C-118`). */
  title?: ReactNode;
  monoTitle?: boolean;
  /** Dim meta beside the title (12px, subtle) — timestamps, counts, ⌘J hints. */
  meta?: ReactNode;
  /** ⤢ expand — navigate to the full-screen version of the rail's subject. */
  onExpand?: () => void;
  /** Accessible label for ⤢, e.g. "Open full trace". */
  expandLabel?: string;
  /** Pinned below the scrolling body (e.g. the Ask composer). */
  footer?: ReactNode;
  children: ReactNode;
  /**
   * Extra classes for the scrolling body — ADDITIVE, not an override: `cn` is a plain
   * join, so a padding utility passed here collides with {@link BODY_PADDING} rather than
   * replacing it. For a full-bleed body, change the default here instead.
   */
  bodyClassName?: string;
  /** Accessible name for the rail when `title` is not plain text. */
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
  footer,
  children,
  bodyClassName,
  "aria-label": ariaLabel,
}: RailProps) {
  const trapRef = useFocusTrap<HTMLElement>(open);
  const { width, dragging, onHandlePointerDown } = useRailWidth(open);

  // ESC closes — global while open, so it works wherever focus sits. The
  // shared overlay stack makes sure only the topmost overlay acts: a rail
  // under the Ask drawer (or another rail) must not also close — in trace-rail
  // mode onClose NAVIGATES.
  useEscToClose(open, onClose);

  if (!open) return null;

  const hasHeader = title != null || meta != null || onExpand != null;

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
      {/* Drag handle — centered on the left border, wide hit area for an easy grab. */}
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
      {hasHeader && (
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
      )}
      <div className={cn("flex-1 min-h-0 overflow-y-auto", BODY_PADDING, bodyClassName)}>{children}</div>
      {footer && <div className="shrink-0 border-t border-border">{footer}</div>}
    </aside>,
    document.body,
  );
}
