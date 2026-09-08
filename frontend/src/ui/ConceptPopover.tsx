// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { HelpCircle } from "lucide-react";
import { useTenant } from "../tenant/TenantContext";
import { CONCEPTS, type ConceptId } from "../concepts";
import { cn } from "./cn";

export function ConceptPopover({
  concept,
  children,
  className,
}: {
  concept: ConceptId;
  children?: ReactNode;
  className?: string;
}) {
  const c = CONCEPTS[concept];
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLSpanElement>(null);
  const { orgSlug, projectSlug } = useTenant();

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

  return (
    <span ref={ref} className={cn("relative inline-flex items-center gap-1", className)}>
      {children ?? c.label}
      <button
        type="button"
        aria-label={`What is a ${c.label}?`}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="inline-flex items-center justify-center size-4 rounded-pill text-subtle hover:text-fg hover:bg-hover transition-colors"
        style={{ transitionDuration: "var(--duration-micro)" }}
      >
        <HelpCircle size={10} strokeWidth={1.5} aria-hidden="true" />
      </button>
      {open && (
        <span
          role="dialog"
          className="absolute left-0 top-full mt-1.5 z-50 w-72 rounded-card bg-overlay border border-border-strong p-3.5 text-left"
          style={{ boxShadow: "var(--shadow-md)" }}
        >
          <span className="block text-label uppercase text-muted mb-1">{c.label}</span>
          <span className="block text-small text-fg mb-2">{c.gloss}</span>
          {c.long && <span className="block text-small text-muted mb-2">{c.long}</span>}
          <Link
            to={`/orgs/${orgSlug}/projects/${projectSlug}/concepts#${concept}`}
            className="text-small text-link hover:text-link-hover hover:underline"
            onClick={() => setOpen(false)}
          >
            See all concepts
          </Link>
        </span>
      )}
    </span>
  );
}
