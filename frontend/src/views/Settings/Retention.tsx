// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useProjectApi } from "../../tenant/TenantContext";
import { ApiError } from "../../api/types";
import type { RetentionClassView } from "../../api/types";
import { Button, Field, Input, PageBody, PageHeader, Section, Spinner, Toggle, useToast } from "../../ui";

/**
 * Settings → Data retention (#1205). How long this project keeps traces and classifier detections.
 * The install default comes from the deployment's environment (`EVALS_RETENTION_*_TTL_DAYS`); each
 * class here can override it, and clearing the override returns to the default. 0 keeps forever.
 */

const LABELS: Record<RetentionClassView["data_class"], { title: string; hint: string }> = {
  traces: {
    title: "Traces",
    hint: "The conversations and spans your application sends, with their content. A trace an open finding cites is kept until the finding closes.",
  },
  detections: {
    title: "Detections",
    hint: "What the classifiers found on those traces.",
  },
};

function describe(days: number): string {
  return days === 0 ? "kept forever" : `${days} day${days === 1 ? "" : "s"}`;
}

export function Retention() {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();

  const view = useQuery({ queryKey: ["retention", api.base], queryFn: api.getRetention });

  // Per class: null = follow the install default; a string = the override being edited.
  const [drafts, setDrafts] = useState<Partial<Record<RetentionClassView["data_class"], string | null>>>({});

  const classes = view.data?.classes ?? [];
  const canManage = view.data?.can_manage ?? false;
  const draftFor = (c: RetentionClassView): string | null =>
    c.data_class in drafts ? (drafts[c.data_class] ?? null) : c.from_policy ? String(c.ttl_days) : null;
  // Dirty only when the effective value would change, so toggling an override on and off again is a no-op.
  const dirty = classes.some((c) => {
    const d = draftFor(c);
    if (d === null) return c.from_policy;
    return !c.from_policy || Number(d.trim()) !== c.ttl_days;
  });
  const invalid = classes.some((c) => {
    const d = draftFor(c);
    return d !== null && (!/^\d+$/.test(d.trim()) || Number(d) > 36_500);
  });

  const save = useMutation({
    mutationFn: () => {
      const value = (name: RetentionClassView["data_class"]) => {
        const c = classes.find((x) => x.data_class === name);
        const d = c ? draftFor(c) : null;
        return d === null ? null : Number(d.trim());
      };
      return api.updateRetention({ traces: value("traces"), detections: value("detections") });
    },
    onSuccess: () => {
      setDrafts({});
      toast.success("Retention saved");
      qc.invalidateQueries({ queryKey: ["retention", api.base] });
    },
    onError: (err) => toast.error("Could not save retention", (err as ApiError).message),
  });

  return (
    <PageBody size="narrow">
      <PageHeader
        eyebrow="Security & access"
        title="Data retention"
        subtitle="How long this project keeps what it ingests. An hourly sweep deletes anything older. The install default is set by whoever runs the deployment; an owner or admin can override it here for this project only."
        actions={
          <Button
            variant="primary"
            disabled={!dirty || invalid || !canManage}
            loading={save.isPending}
            onClick={() => save.mutate()}
          >
            Save
          </Button>
        }
      />

      <Section>
        {view.isLoading ? (
          <div className="flex items-center gap-2 py-12 text-small text-muted">
            <Spinner size="sm" />
            Loading retention…
          </div>
        ) : (
          <div className="rounded-card border border-border divide-y divide-border">
            {classes.map((c) => {
              const draft = draftFor(c);
              const overriding = draft !== null;
              return (
                <div key={c.data_class} className="px-4 py-4 flex flex-col gap-3">
                  <div className="flex items-start justify-between gap-4">
                    <div className="min-w-0">
                      <div className="text-small text-fg">{LABELS[c.data_class].title}</div>
                      <div className="text-label text-muted mt-0.5">{LABELS[c.data_class].hint}</div>
                      <div className="text-label text-muted mt-1">
                        Install default: <span className="text-fg">{describe(c.platform_default_days)}</span>
                        {" · "}currently <span className="text-fg">{describe(c.ttl_days)}</span>
                      </div>
                    </div>
                    <Toggle
                      checked={overriding}
                      disabled={!canManage || save.isPending}
                      onChange={(on: boolean) =>
                        setDrafts((d) => ({
                          ...d,
                          [c.data_class]: on ? String(c.from_policy ? c.ttl_days : c.platform_default_days) : null,
                        }))
                      }
                      label="Override for this project"
                    />
                  </div>
                  {overriding && (
                    <Field label="Days to keep" hint="0 keeps forever.">
                      {(p) => (
                        <Input
                          {...p}
                          inputMode="numeric"
                          value={draft ?? ""}
                          disabled={!canManage}
                          onChange={(e) => setDrafts((d) => ({ ...d, [c.data_class]: e.target.value }))}
                          className="max-w-[160px]"
                        />
                      )}
                    </Field>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </Section>
    </PageBody>
  );
}
