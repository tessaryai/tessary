// SPDX-License-Identifier: Apache-2.0
import type { EndUserOverview } from "../../api/types";
import { Card } from "../../ui";
import { countedLine, HOW_COUNTED_HIDE, HOW_COUNTED_SHOW, ISSUES_OVERLAP, trustLine } from "./usersWords";

/** One panel per page, so the toggle in the header and the panel under it share a fixed id. */
const PANEL_ID = "users-how-counted";

/** Board P1: the header control that opens and closes the panel. */
export function HowCountedToggle({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      aria-expanded={open}
      aria-controls={PANEL_ID}
      onClick={onToggle}
      className="cursor-pointer bg-transparent text-small text-link hover:text-link-hover"
    >
      {open ? HOW_COUNTED_HIDE : HOW_COUNTED_SHOW}
    </button>
  );
}

/** Board P1: how the stages are judged and counted; a count the page does not have is left out. */
export function HowCounted({ overview: o }: { overview: EndUserOverview }) {
  return (
    <Card
      id={PANEL_ID}
      role="region"
      aria-label={HOW_COUNTED_SHOW}
      className="flex flex-col gap-1.5 border border-border px-4.5 py-3.5 text-small text-fg-secondary"
    >
      <span>{trustLine(o.trust)}</span>
      {o.counted != null && <span>{countedLine(o.counted, o.set_aside, o.history_since)}</span>}
      <span>{ISSUES_OVERLAP}</span>
    </Card>
  );
}
