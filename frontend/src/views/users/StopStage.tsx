// SPDX-License-Identifier: Apache-2.0
import { Link } from "react-router-dom";
import type { EndUserStage, EndUserStopStageState } from "../../api/types";
import { cn } from "../../ui";
import { stopStage } from "./usersWords";

/** A stage that shows no count draws a dash: a mark, not text, so no string holds a dash and no reader hears one. */
function Dash() {
  return (
    <svg data-part="dash" aria-hidden="true" viewBox="0 0 24 4" className="my-4 h-1 w-6">
      <rect width="24" height="4" fill="var(--color-fg)" />
    </svg>
  );
}

export type StageLinkProps = {
  stage: EndUserStage;
  label: string;
  value: string | null;
  unit: string | null;
  caption: string;
  loss?: boolean;
};

/** One funnel stage; it opens the ID list on its own tab. */
export function StageLink({ stage, label, value, unit, caption, loss = false }: StageLinkProps) {
  return (
    <Link to={`ids?stage=${stage}`} className="flex flex-col gap-0.5 text-fg no-underline">
      <span className="font-mono text-label uppercase text-muted">{label}</span>
      {value == null ? (
        <Dash />
      ) : (
        <span className="flex items-baseline gap-2">
          <span className={cn("text-metric", loss ? "text-error" : "text-fg")}>{value}</span>
          {unit && <span className="text-body text-fg-secondary">{unit}</span>}
        </span>
      )}
      <span className="text-small text-muted">{caption}</span>
    </Link>
  );
}

/** The Probably stopped stage in one of its seven states; only the full state names a count in red. */
export function StopStage({ state, numbers }: { state: EndUserStopStageState; numbers: Record<string, number> }) {
  const card = stopStage(state, numbers);
  return (
    <div className="flex flex-col gap-1">
      <StageLink
        stage="stopped"
        label={card.label}
        value={card.value}
        unit={card.unit}
        caption={card.caption}
        loss={state === "expected_and_named"}
      />
      {card.note && <p className="text-small text-muted">{card.note}</p>}
    </div>
  );
}
