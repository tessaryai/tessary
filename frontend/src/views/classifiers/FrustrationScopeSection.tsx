// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { Classifier } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, ErrorNote, LoadingRow, useToast } from "../../ui";

/**
 * Which call sites the Frustration classifier scores. One user turn can call a router, the reply and a
 * memory pass, each its own call site, and only the call that answers the user is a conversation worth
 * judging. Nothing is scored until a call site is picked. A new pick applies to turns that arrive after
 * it; turns already swept are not sent again, since that would spend provider credit on history.
 */
export function FrustrationScopeSection({ classifier }: { classifier: Classifier }) {
  const { api } = useTenant();
  const qc = useQueryClient();
  const toast = useToast();

  const scopeQ = useQuery({
    queryKey: ["frustration-scope", api.base, classifier.id],
    queryFn: () => api.getFrustrationScope(classifier.id),
  });
  const pipelineQ = useQuery({
    queryKey: ["pipeline", api.base],
    queryFn: api.getPipeline,
  });

  const [picked, setPicked] = useState<Set<string> | null>(null);

  // Seed once from the stored picks; a refetch after save does not undo a box the user just changed.
  useEffect(() => {
    if (scopeQ.data && picked === null) setPicked(new Set(scopeQ.data.call_site_ids));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scopeQ.data]);

  const saveM = useMutation({
    mutationFn: (callSiteIds: string[]) => api.setFrustrationScope(classifier.id, { call_site_ids: callSiteIds }),
    onSuccess: (result) => {
      qc.setQueryData(["frustration-scope", api.base, classifier.id], result);
      setPicked(new Set(result.call_site_ids));
      toast.success("Call sites saved", "They apply to turns that arrive from now on.");
    },
  });

  if (scopeQ.isLoading || pipelineQ.isLoading) return <LoadingRow />;
  if (scopeQ.isError) return <ErrorNote error={scopeQ.error} />;
  if (pipelineQ.isError) return <ErrorNote error={pipelineQ.error} />;
  if (!picked) return null;

  const callSites = pipelineQ.data?.pipeline.call_sites ?? [];
  const stored = scopeQ.data?.call_site_ids ?? [];

  if (callSites.length === 0) {
    return (
      <p className="text-subtle m-0 text-small">
        No call sites yet. They appear once traces tagged with a call site arrive.
      </p>
    );
  }

  const toggle = (id: string) => {
    const next = new Set(picked);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setPicked(next);
  };

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    saveM.mutate(callSites.map((c) => c.id).filter((id) => picked.has(id)));
  };

  return (
    <form onSubmit={submit} className="flex flex-col gap-2.5">
      <p className="text-subtle m-0 text-small">
        Pick the call sites that reply to the user. Leave out routers, memory passes and other calls the user
        never reads. Each picked call site is scored and counted on its own.
      </p>
      {stored.length === 0 && (
        <p role="status" className="text-warning m-0 text-small">
          Nothing is scored until you pick a call site.
        </p>
      )}
      <fieldset className="rounded-card border border-border divide-y divide-border m-0 p-0">
        <legend className="sr-only">Call sites to score</legend>
        {callSites.map((c) => (
          <label key={c.id} className="flex items-start gap-3 px-4 py-3 cursor-pointer">
            <input
              type="checkbox"
              checked={picked.has(c.id)}
              disabled={saveM.isPending}
              onChange={() => toggle(c.id)}
              className="mt-1"
            />
            <span className="flex flex-col gap-0.5 min-w-0">
              <span className={c.use_case ? "text-body" : "text-body font-mono"}>{c.use_case ?? c.id}</span>
              {(c.use_case || c.shape === "conversational_turn") && (
                <span className="text-subtle text-label flex gap-1.5">
                  {c.use_case && <span className="font-mono">{c.id}</span>}
                  {c.shape === "conversational_turn" && <span>Chat turn</span>}
                </span>
              )}
            </span>
          </label>
        ))}
      </fieldset>
      {saveM.isError && <ErrorNote error={saveM.error} />}
      <div>
        <Button type="submit" variant="primary" size="sm" loading={saveM.isPending}>
          Save call sites
        </Button>
      </div>
    </form>
  );
}
