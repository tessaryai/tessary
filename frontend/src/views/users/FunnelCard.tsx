// SPDX-License-Identifier: Apache-2.0
import { useId } from "react";
import { Link } from "react-router-dom";
import type { EndUserStageCounts, EndUserStopStageState } from "../../api/types";
import { Card } from "../../ui";
import { StageLink, StopStage } from "./StopStage";
import {
  activeCaption,
  ARROW,
  FUNNEL_HEADING,
  funnelAria,
  SEE_USER_IDS,
  SILENT_CAPTION,
  STAGE_LABELS,
  thousands,
} from "./usersWords";

const HEIGHT = 120;
const BAR_WIDTH = 260;
const BAR_X = [0, 370, 740];

type Bar = { part: string; count: number; fill: string };

/** Bars are centred on one line; a bar's height is the square root of its share of the largest stage, as on P1. */
function FunnelPicture({ bars, label }: { bars: Bar[]; label: string }) {
  const largest = Math.max(...bars.map((b) => b.count), 1);
  const shapes = bars.map((b, i) => {
    const h = HEIGHT * Math.sqrt(b.count / largest);
    return { ...b, x: BAR_X[i], top: (HEIGHT - h) / 2, h };
  });
  const bands = shapes.slice(1).map((b, i) => {
    const a = shapes[i];
    const ax = a.x + BAR_WIDTH;
    return `${ax},${a.top} ${b.x},${b.top} ${b.x},${b.top + b.h} ${ax},${a.top + a.h}`;
  });
  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 1000 ${HEIGHT}`}
      preserveAspectRatio="none"
      className="col-span-full h-24 w-full"
    >
      {bands.map((points) => (
        <polygon key={points} points={points} fill="var(--color-raised)" />
      ))}
      {shapes.map((b) => (
        <rect key={b.part} data-part={b.part} x={b.x} y={b.top} width={BAR_WIDTH} height={b.h} fill={b.fill} />
      ))}
    </svg>
  );
}

/** Board P1: the three stages, each opening its tab of the ID list. A withheld stop count draws no stop bar. */
export function FunnelCard({
  stages,
  stopState,
  stopNumbers,
}: {
  stages: EndUserStageCounts;
  stopState: EndUserStopStageState;
  stopNumbers: Record<string, number>;
}) {
  const headingId = useId();
  const named = stopState === "expected_and_named" ? stages.stopped_named : null;
  const bars: Bar[] = [
    { part: "bar-active", count: stages.active, fill: "var(--color-subtle)" },
    { part: "bar-silent", count: stages.silent, fill: "var(--color-muted)" },
    ...(named == null ? [] : [{ part: "bar-stopped", count: named, fill: "var(--color-error)" }]),
  ];
  return (
    <Card aria-labelledby={headingId} role="region" className="flex flex-col gap-4 border border-border px-6 py-5">
      <div className="flex items-center gap-4">
        <h2 id={headingId} className="text-h3 text-fg">
          {FUNNEL_HEADING}
        </h2>
        <Link to="ids" className="ml-auto text-small text-link hover:text-link-hover">
          {`${SEE_USER_IDS} ${ARROW}`}
        </Link>
      </div>
      <div className="grid grid-cols-[26fr_11fr_26fr_11fr_26fr] gap-y-3">
        <div className="col-start-1 row-start-1">
          <StageLink
            stage="active"
            label={STAGE_LABELS.active}
            value={thousands(stages.active)}
            unit={null}
            caption={activeCaption(stages.new)}
          />
        </div>
        <div className="col-start-3 row-start-1">
          <StageLink
            stage="silent"
            label={STAGE_LABELS.silent}
            value={thousands(stages.silent)}
            unit={null}
            caption={SILENT_CAPTION}
          />
        </div>
        <div className="col-start-5 row-start-1">
          <StopStage state={stopState} numbers={stopNumbers} />
        </div>
        <FunnelPicture bars={bars} label={funnelAria(stages.active, stages.silent, named)} />
      </div>
    </Card>
  );
}
