// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { Classifier } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { invalidateClassifierReads } from "./classifierReads";
import { Button, ErrorNote, LoadingRow, SegmentedControl, Toggle, useToast } from "../../ui";

/** Tool error buckets by tool across call sites, so the server refuses a call-site list for it. */
export const UNSCOPED_DETECTORS: ReadonlySet<string> = new Set(["tool_error"]);

type Scope = "all" | "some";

/**
 * Which call sites a classifier runs on: every one (the default), or a list. A list is never empty, because a
 * classifier that should run nowhere is turned off instead, so "Every call site" is sent as null.
 */
export function CallSitesSection({ classifier }: { classifier: Classifier }) {
  const { api } = useTenant();
  const qc = useQueryClient();
  const toast = useToast();
  const stored = classifier.call_site_ids ?? null;
  const [scope, setScope] = useState<Scope>(stored === null ? "all" : "some");
  const [picked, setPicked] = useState<Set<string>>(() => new Set(stored ?? []));
  const [triedEmpty, setTriedEmpty] = useState(false);

  const callSitesQ = useQuery({
    queryKey: ["classifier-call-sites", api.base],
    queryFn: () => api.listClassifierCallSites(),
  });

  const saveM = useMutation({
    mutationFn: (ids: string[] | null) => api.setClassifierCallSites(classifier.id, ids),
    onSuccess: (saved) => {
      invalidateClassifierReads(qc, api.base);
      const count = saved.call_site_ids?.length;
      toast.success(
        "Call sites saved",
        count === undefined ? "It runs on every call site." : `It runs on ${count} call site${count === 1 ? "" : "s"}.`,
      );
    },
  });

  if (callSitesQ.isLoading) return <LoadingRow />;
  if (callSitesQ.isError) return <ErrorNote error={callSitesQ.error} />;

  // A stored id stays listed after its traffic ages out, so it can still be removed.
  const options = [...new Set([...(callSitesQ.data ?? []), ...(stored ?? [])])].sort();
  const empty = scope === "some" && picked.size === 0;

  const flip = (callSite: string, on: boolean) => {
    const next = new Set(picked);
    if (on) next.add(callSite);
    else next.delete(callSite);
    setPicked(next);
  };

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (empty) {
      setTriedEmpty(true);
      return;
    }
    saveM.mutate(scope === "all" ? null : options.filter((c) => picked.has(c)));
  };

  return (
    <form onSubmit={submit} className="flex flex-col gap-2.5">
      <SegmentedControl<Scope>
        ariaLabel="Call sites this classifier runs on"
        value={scope}
        onChange={setScope}
        options={[
          { value: "all", label: "Every call site" },
          { value: "some", label: "Only some" },
        ]}
      />
      {scope === "some" &&
        (options.length === 0 ? (
          <p className="text-subtle m-0 text-small">
            No call sites yet. Tag your traces with a call site, or connect a repository, to pick one here.
          </p>
        ) : (
          <>
            <p className="text-subtle m-0 text-small">Spans with no call site are skipped.</p>
            <ul className="flex flex-col gap-1.5 m-0 p-0 list-none">
              {options.map((callSite) => (
                <li key={callSite} className="flex items-center justify-between gap-3">
                  <span className="font-mono text-small text-fg truncate">{callSite}</span>
                  <Toggle
                    checked={picked.has(callSite)}
                    onChange={(on) => flip(callSite, on)}
                    label={`Run on ${callSite}`}
                  />
                </li>
              ))}
            </ul>
          </>
        ))}
      {triedEmpty && empty && (
        <p role="alert" className="text-small text-error m-0">
          Pick at least one call site, or choose Every call site.
        </p>
      )}
      {saveM.isError && <ErrorNote error={saveM.error} />}
      <div>
        <Button type="submit" variant="primary" size="sm" loading={saveM.isPending}>
          Save call sites
        </Button>
      </div>
    </form>
  );
}
