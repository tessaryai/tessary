// SPDX-License-Identifier: Apache-2.0
/*
 * THE unified clickable § citation treatment (sheets/case.md, traces.md,
 * review.md, graders.md all cite it): mono, color inherited from the
 * surrounding prose, dotted underline with a 2px offset. It exists exactly
 * once — every surface that renders a clickable §N delegates here so the
 * treatment cannot drift.
 */
import type { CSSProperties } from "react";

/** Inline style for a clickable § citation. Apply with `className="font-mono"`. */
export const CITATION_STYLE: CSSProperties = {
  color: "inherit",
  textDecoration: "underline dotted",
  textUnderlineOffset: 2,
  cursor: "pointer",
};

/**
 * Renders a rationale sentence with §N citations as the unified clickable
 * citation. Click stops propagation and reports the section number.
 */
export function CitedText({
  text,
  onCite,
}: {
  text: string;
  onCite?: (section: number) => void;
}) {
  const parts = text.split(/(§\d+)/g);
  return (
    <>
      {parts.map((p, i) => {
        const m = /^§(\d+)$/.exec(p);
        if (!m) return <span key={i}>{p}</span>;
        const section = Number(m[1]);
        // A span, not a <button>: citations render inside whole-row buttons and
        // interactive elements must not nest.
        return (
          <span
            key={i}
            role="link"
            tabIndex={0}
            className="font-mono"
            style={CITATION_STYLE}
            onClick={(e) => {
              e.stopPropagation();
              onCite?.(section);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                e.stopPropagation();
                onCite?.(section);
              }
            }}
          >
            {p}
          </span>
        );
      })}
    </>
  );
}
