// SPDX-License-Identifier: Apache-2.0
/*
 * The command palette (⌘K) — the keyboard-first front door to the whole IA.
 *
 * A context with a single window-level keydown listener mounted once in the shell, plus a
 * dock that renders the overlay. Open with ⌘K (or Ctrl+K), close with Escape. The handler is
 * ignored while the user is typing in another input, and only ever preventDefaults its own
 * chord.
 *
 * The palette is the project's "global search entry point" for this increment: it jumps to
 * any surface, switches context, and lists recents — all client-side. Server-backed
 * full-text search is deferred (see decision.md / commands.tsx).
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";

type PaletteApi = {
  isOpen: boolean;
  open: () => void;
  close: () => void;
  toggle: () => void;
};

const PaletteCtx = createContext<PaletteApi | null>(null);

export function usePalette(): PaletteApi {
  const ctx = useContext(PaletteCtx);
  if (!ctx) throw new Error("usePalette must be used within <PaletteProvider>");
  return ctx;
}

/** True when focus is in a field where ⌘K should defer to normal typing/editing. */
function isEditableTarget(t: EventTarget | null): boolean {
  const el = t as HTMLElement | null;
  if (!el) return false;
  const tag = el.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || el.isContentEditable;
}

export function PaletteProvider({ children }: { children: ReactNode }) {
  const [isOpen, setIsOpen] = useState(false);

  const open = useCallback(() => setIsOpen(true), []);
  const close = useCallback(() => setIsOpen(false), []);
  const toggle = useCallback(() => setIsOpen((v) => !v), []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const k = (e.key || "").toLowerCase();
      if ((e.metaKey || e.ctrlKey) && k === "k") {
        // ⌘K is ours even from inside inputs — it's a global launcher, like ⌘J.
        e.preventDefault();
        setIsOpen((v) => !v);
        return;
      }
      // Plain "/" is a convenience open, but only when not already typing somewhere.
      if (k === "/" && !e.metaKey && !e.ctrlKey && !e.altKey && !isEditableTarget(e.target)) {
        e.preventDefault();
        setIsOpen(true);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const api = useMemo<PaletteApi>(() => ({ isOpen, open, close, toggle }), [isOpen, open, close, toggle]);

  return <PaletteCtx.Provider value={api}>{children}</PaletteCtx.Provider>;
}
