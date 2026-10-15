// SPDX-License-Identifier: Apache-2.0
/*
 * Renders only the rows of a long table body that are near the screen.
 *
 * The traces list appends a page per scroll and never drops one, so a reader who scrolls back a few
 * days holds well over a thousand rows. Every one of them stays in the layout and paint the browser
 * redoes as the pointer crosses rows, and scrolling got steadily rougher with each page: at 2x CPU
 * slowdown the slowest 5% of frames took 17 ms with 200 rows, 67 ms with 600 and 167 ms with 1,200.
 *
 * Rows are one line each (every cell truncates), so the row pitch is measured once off two rendered
 * rows and the rows out of reach are replaced by two spacer heights. The range moves in steps of
 * STEP rows, so a scroll re-renders the body once per STEP rows rather than once per row.
 */
import { useLayoutEffect, useRef, useState } from "react";

/** Below this many rows the whole list renders: nothing to save, and nothing to measure against. */
const MIN_ROWS = 200;
/** Rows kept rendered past each edge of the screen. */
const OVERSCAN = 30;
const STEP = 10;

export type RowWindow = { start: number; end: number; top: number; bottom: number };

export function useRowWindow(count: number) {
  const bodyRef = useRef<HTMLTableSectionElement>(null);
  const pitch = useRef(0);
  const [range, setRange] = useState<{ start: number; end: number } | null>(null);
  const windowed = count >= MIN_ROWS;

  useLayoutEffect(() => {
    const body = bodyRef.current;
    const scroller = body?.closest("main");
    if (!windowed || !body || !scroller) {
      setRange(null);
      return;
    }
    if (!pitch.current) {
      const rows = body.querySelectorAll<HTMLTableRowElement>(":scope > tr[data-row]");
      if (rows.length >= 2) pitch.current = rows[1].offsetTop - rows[0].offsetTop;
    }
    let frame = 0;
    const update = () => {
      frame = 0;
      const h = pitch.current;
      if (h <= 0) return;
      const above = scroller.getBoundingClientRect().top - body.getBoundingClientRect().top;
      const start = Math.max(0, Math.floor((above / h - OVERSCAN) / STEP) * STEP);
      const end = Math.min(count, Math.ceil(((above + scroller.clientHeight) / h + OVERSCAN) / STEP) * STEP);
      setRange((prev) => (prev && prev.start === start && prev.end === end ? prev : { start, end }));
    };
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(update);
    };
    update();
    scroller.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", schedule);
    return () => {
      scroller.removeEventListener("scroll", schedule);
      window.removeEventListener("resize", schedule);
      cancelAnimationFrame(frame);
    };
  }, [count, windowed]);

  const h = pitch.current;
  const win: RowWindow =
    range && h > 0
      ? { start: range.start, end: range.end, top: range.start * h, bottom: (count - range.end) * h }
      : { start: 0, end: count, top: 0, bottom: 0 };
  return { bodyRef, win };
}
