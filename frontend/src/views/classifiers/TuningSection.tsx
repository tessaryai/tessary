// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { Classifier } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, ErrorNote, Input, LoadingRow, useToast } from "../../ui";
import { invalidateClassifierReads } from "./classifierReads";

export const METRIC_DRIFT_DETECTORS: ReadonlySet<string> = new Set(["cost_drift", "duration_drift"]);

type FormState = {
  windowTargetCount: string;
  windowMaxHours: string;
  minSample: string;
  w1Floor: string;
};

/**
 * The five fields `MetricDriftConfig` clamps server-side. A change applies from the next window close.
 * The dial is the move, not a false-alarm rate; the implied rate is shown beside it, read-only.
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
  const [triedBlank, setTriedBlank] = useState(false);

  // Seed once, so a refetch after save does not clobber what the user is typing.
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
      invalidateClassifierReads(qc, api.base);
      setForm(fromTuning(result));
      toast.success("Tuning saved", "It applies from the next window this classifier closes.");
    },
  });

  if (tuningQ.isLoading) return <LoadingRow />;
  if (tuningQ.isError) return <ErrorNote error={tuningQ.error} />;
  if (!form) return null;

  const blank = FIELDS.filter((f) => form[f.key].trim() === "").map((f) => f.label);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    // A cleared number input reports "", and Number("") is 0, so nothing saves until every field has a value.
    if (blank.length > 0) {
      setTriedBlank(true);
      return;
    }
    saveM.mutate({
      window_target_count: Number(form.windowTargetCount),
      window_max_hours: Number(form.windowMaxHours),
      min_sample: Number(form.minSample),
      w1_floor: Number(form.w1Floor),
    });
  };

  return (
    <form onSubmit={submit} className="flex flex-col gap-2.5">
      <p className="text-subtle m-0 text-small">
        Clamped server-side to a safe range, so what saves may differ slightly from what you type. Changes
        apply from the next window this classifier closes, not retroactively.
      </p>
      {FIELDS.map((f) => (
        <TuningField
          key={f.key}
          label={f.label}
          hint={f.hint}
          value={form[f.key]}
          onChange={(v) => setForm({ ...form, [f.key]: v })}
          step={f.step}
        />
      ))}
      <ImpliedRate rate={tuningQ.data?.implied_false_alarm_rate ?? null} />
      {triedBlank && blank.length > 0 && (
        <p role="alert" className="text-small text-error m-0">
          Fill in {blank.join(", ")} to save.
        </p>
      )}
      {saveM.isError && <ErrorNote error={saveM.error} />}
      <div>
        <Button type="submit" variant="primary" size="sm" loading={saveM.isPending}>
          Save changes
        </Button>
      </div>
    </form>
  );
}

const FIELDS: { key: keyof FormState; label: string; hint: string; step?: string }[] = [
  { key: "windowTargetCount", label: "Target samples to close a window", hint: "50–100,000 · default 500" },
  { key: "windowMaxHours", label: "Max hours before closing anyway", hint: "1–2,160 (90d) · default 24" },
  { key: "minSample", label: "Min samples before comparing at all", hint: "30–target · default 100" },
  {
    key: "w1Floor",
    label: "Smallest shift worth reporting (log W1)",
    hint: "0.01–3.0 · default 0.139 ≈ a 15% move. A thinner window is held to more, because it cannot measure that reliably.",
    step: "0.01",
  },
];

function fromTuning(t: { window_target_count: number; window_max_hours: number; min_sample: number; w1_floor: number }): FormState {
  return {
    windowTargetCount: String(t.window_target_count),
    windowMaxHours: String(t.window_max_hours),
    minSample: String(t.min_sample),
    w1Floor: String(t.w1_floor),
  };
}

/** Null until some bucket has enough traffic: a made-up number would be worse than none. */
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
