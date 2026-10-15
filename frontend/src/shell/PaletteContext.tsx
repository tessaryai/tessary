// SPDX-License-Identifier: Apache-2.0
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";

type PaletteApi = {
  isOpen: boolean;
  open: () => void;
  close: () => void;
};

const PaletteCtx = createContext<PaletteApi | null>(null);

export function usePalette(): PaletteApi {
  const ctx = useContext(PaletteCtx);
  if (!ctx) throw new Error("usePalette must be used within <PaletteProvider>");
  return ctx;
}

function isEditableTarget(t: EventTarget | null): boolean {
  const el = t as HTMLElement;
  const tag = el.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || el.isContentEditable;
}

export function PaletteProvider({ children }: { children: ReactNode }) {
  const [isOpen, setIsOpen] = useState(false);

  const open = useCallback(() => setIsOpen(true), []);
  const close = useCallback(() => setIsOpen(false), []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const k = (e.key || "").toLowerCase();
      if ((e.metaKey || e.ctrlKey) && k === "k") {
        // ⌘K is ours even from inside inputs.
        e.preventDefault();
        setIsOpen((v) => !v);
        return;
      }
      if (k === "/" && !e.metaKey && !e.ctrlKey && !e.altKey && !isEditableTarget(e.target)) {
        e.preventDefault();
        setIsOpen(true);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const api = useMemo<PaletteApi>(() => ({ isOpen, open, close }), [isOpen, open, close]);

  return <PaletteCtx.Provider value={api}>{children}</PaletteCtx.Provider>;
}
