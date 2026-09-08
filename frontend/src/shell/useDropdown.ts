// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef, useState } from "react";

/**
 * Shell dropdown behavior shared by the project/environment switchers and the account menu:
 * open/close state plus dismissal on outside-click and Escape while open.
 *
 * Returns a ref to attach to the dropdown's outer container; clicks outside that
 * container (and the Escape key) close it.
 */
export function useDropdown<T extends HTMLElement = HTMLDivElement>() {
  const [open, setOpen] = useState(false);
  const ref = useRef<T>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return { open, setOpen, ref };
}
