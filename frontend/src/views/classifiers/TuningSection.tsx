// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { Classifier } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, ErrorNote, Input, LoadingRow, useToast } from "../../ui";

/** The two detectors {@code MetricDriftConfig} actually governs: the classifiers this form applies to. */
export const METRIC_DRIFT_DETECTORS: ReadonlySet<string> = new Set(["cost_drift", "duration_drift"]);

type FormState = {
  windowTargetCount: string;
  windowMaxHours: string;
  minSample: string;
  w1Floor: string;
};

/**
 * The operating point editor for cost_drift / duration_drift: the five fields `MetricDriftConfig`
 * clamps server-side. Editing here doesn't rewind any history: it only changes when the classifier's
 * NEXT window closes and how big a shift has to be to count, so the effect of a change is visible
 * after that window closes, not immediately.
 *
 * The dial is the MOVE, not a false-alarm rate. The two are interchangeable arithmetic but not
 * interchangeable promises: "tell me about 15% moves" is something you can hold in your head and
 * predict, and a rate is not. What the chosen move costs in false alarms on this project's own traffic
 * is shown beside it, read-only, so the consequence is visible where the choice is made rather than
 * surfacing as noise a week later.
 */
export function TuningSection({ classifier }: { classifier: Classifier }) {
  const { api } = useTenant();
  const qc = useQueryClient();
  const toast = useToast();

  const tuningQ = useQuery({
    queryKey: ["classifier-tuning", api.base, classifier.id],
    queryFn: () => api.getClassifierTuning(classifier.id),
  });

  const [form, setForm] = useState<FormState | null>(null);

  // Seed the form once from the fetched value; a later refetch (e.g. after save) doesn't clobber
  // whatever the user is mid-typing.
  useEffect(() => {
    if (tuningQ.data && form === null) setForm(fromTuning(tuningQ.data));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tuningQ.data]);

  const saveM = useMutation({
    mutationFn: (body: {
      window_target_count: number;
      window_max_hours: number;
      min_sample: number;
      w1_floor: number;
    }) =>
      api.setClassifierTuning(classifier.id, body),
    onSuccess: (result) => {
      qc.setQueryData(["classifier-tuning", api.base, classifier.id], result);
      setForm(fromTuning(result));
      toast.success("Tuning saved", "It applies from the next window this classifier closes.");
    },
  });

  if (tuningQ.isLoading) return <LoadingRow />;
  if (tuningQ.isError) return <ErrorNote error={tuningQ.error} />;
  if (!form) return null;

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const parsed = {
      window_target_count: Number(form.windowTargetCount),
      window_max_hours: Number(form.windowMaxHours),
      min_sample: Number(form.minSample),
      w1_floor: Number(form.w1Floor),
    };
    if (Object.values(parsed).some((v) => !Number.isFinite(v))) return;
    saveM.mutate(parsed);
  };

  return (
    <form onSubmit={submit} className="flex flex-col gap-2.5">
      <p className="text-subtle m-0 text-small">
        Clamped server-side to a safe range, so what saves may differ slightly from what you type. Changes
        apply from the next window this classifier closes, not retroactively.
      </p>
      <TuningField
        label="Target samples to close a window"
        hint="50–100,000 · default 500"
        value={form.windowTargetCount}
        onChange={(v) => setForm({ ...form, windowTargetCount: v })}
      />
      <TuningField
        label="Max hours before closing anyway"
        hint="1–2,160 (90d) · default 24"
        value={form.windowMaxHours}
        onChange={(v) => setForm({ ...form, windowMaxHours: v })}
      />
      <TuningField
        label="Min samples before comparing at all"
        hint="30–target · default 100"
        value={form.minSample}
        onChange={(v) => setForm({ ...form, minSample: v })}
      />
      <TuningField
        label="Smallest shift worth reporting (log W1)"
        hint="0.01–3.0 · default 0.139 ≈ a 15% move. A thinner window is held to more, because it cannot measure that reliably."
        value={form.w1Floor}
        onChange={(v) => setForm({ ...form, w1Floor: v })}
        step="0.01"
      />
      <ImpliedRate rate={tuningQ.data?.implied_false_alarm_rate ?? null} />
      {saveM.isError && <ErrorNote error={saveM.error} />}
      <div>
        <Button type="submit" variant="primary" size="sm" loading={saveM.isPending}>
          Save changes
        </Button>
      </div>
    </form>
  );
}

function fromTuning(t: { window_target_count: number; window_max_hours: number; min_sample: number; w1_floor: number }): FormState {
  return {
    windowTargetCount: String(t.window_target_count),
    windowMaxHours: String(t.window_max_hours),
    minSample: String(t.min_sample),
    w1Floor: String(t.w1_floor),
  };
}

/**
 * What the chosen move costs in false alarms on this project's traffic. Read-only, and null until some
 * bucket has enough traffic to say. A made-up number here would be worse than no number, because it is
 * the only thing telling someone what their setting actually does.
 */
function ImpliedRate({ rate }: { rate: number | null }) {
  return (
    <p className="text-subtle m-0 text-label">
      {rate === null
        ? "Not enough traffic yet to say what this costs in false alarms."
        : `On this project's traffic that is roughly ${formatRate(rate)} of comparisons firing on windows that did not actually change.`}
    </p>
  );
}

function formatRate(rate: number): string {
  const pct = rate * 100;
  return pct >= 1 ? `${pct.toFixed(0)}%` : `${pct.toFixed(1)}%`;
}

function TuningField({
  label,
  hint,
  value,
  onChange,
  step,
}: {
  label: string;
  hint: string;
  value: string;
  onChange: (v: string) => void;
  step?: string;
}) {
  return (
    <label className="flex flex-col gap-0.75">
      <span className="text-muted text-small">
        {label}
      </span>
      <Input type="number" inputMode="decimal" step={step ?? "1"} value={value} onChange={(e) => onChange(e.target.value)} />
      <span className="text-subtle text-label">
        {hint}
      </span>
    </label>
  );
}
