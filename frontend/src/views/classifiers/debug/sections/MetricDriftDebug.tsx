// SPDX-License-Identifier: Apache-2.0
import type { ClassifierDebugMetricBaseline, ClassifierDebugSketch } from "../../../../api/types";

/**
 * cost_drift / duration_drift's per-(bucket × measure) window state: `metric_baseline` rows that no
 * other endpoint exposes. This is the family that prompted the debug screen: "0 baselines armed" on
 * the Classifiers page is a fact about these rows, and this is where you see which bucket, which
 * measure, and why.
 */
export function MetricDriftDebug({ baselines }: { baselines: ClassifierDebugMetricBaseline[] }) {
  if (baselines.length === 0) {
    return (
      <p className="text-subtle m-0 text-small">
        No metric_baseline rows yet. No bucket has produced a sample for this classifier.
      </p>
    );
  }
  return (
    <section>
      <h4 className="font-mono text-label uppercase text-muted mb-2">
        Metric baselines · {baselines.length}
      </h4>
      <div className="flex flex-col gap-2.5">
        {baselines.map((b, i) => (
          <div
            key={`${b.measure}:${b.bucket_kind}:${b.bucket_key}:${i}`}
            className="rounded-control border border-border py-2 px-2.5 text-small">
            <div className="flex items-baseline justify-between gap-2">
              <span className="min-w-0 truncate font-mono text-fg">
                {b.measure} · {b.bucket_key}
              </span>
              <span className="shrink-0 text-subtle">{b.state}</span>
            </div>
            <div className="text-muted mt-1">
              w1_floor {b.w1_floor} · {b.current_count} in current window
              {b.current_opened_at && ` · opened ${b.current_opened_at}`}
            </div>
            <SketchRow label="pinned" sketch={b.pinned} extra={b.pinned_at ? `pinned ${b.pinned_at}` : undefined} />
            <SketchRow label="prev" sketch={b.prev} />
            <SketchRow label="current" sketch={b.current} />
          </div>
        ))}
      </div>
    </section>
  );
}

function SketchRow({
  label,
  sketch,
  extra,
}: {
  label: string;
  sketch: ClassifierDebugSketch | null;
  extra?: string;
}) {
  return (
    <div className="text-subtle mt-0.5">
      {label}: {sketch ? `${sketch.count} samples · grid ${sketch.grid_id}` : "empty"}
      {extra ? ` · ${extra}` : ""}
    </div>
  );
}
