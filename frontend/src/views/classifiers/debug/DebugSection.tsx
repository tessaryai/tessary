// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { Classifier, ClassifierDebug } from "../../../api/types";
import { useTenant } from "../../../tenant/TenantContext";
import { Collapsible, ErrorNote, LoadingRow } from "../../../ui";
import { PayloadViewer } from "../../components/PayloadViewer";
import { CommonStatus } from "./sections/CommonStatus";
import { DeterministicDebug } from "./sections/DeterministicDebug";
import { EncoderDebug } from "./sections/EncoderDebug";
import { MetricDriftDebug } from "./sections/MetricDriftDebug";
// The alias resolves to the stub next door in this build; a build may override it.
import { paid } from "@paid";

/**
 * Everything the platform already computes for this classifier that the rail above doesn't render:
 * sweep cursor/lease detail plus family-specific fitted state. Collapsed by default: the query only
 * runs once opened (`enabled: open`), so browsing the classifier list costs nothing extra, and this
 * whole subtree is a separate lazy chunk (see its `React.lazy` import site in `ClassifiersPage.tsx`)
 * so its code isn't fetched until then either.
 *
 * Not part of the product surface: deleting this directory and its one mount point in
 * `ClassifiersPage.tsx` removes the feature cleanly, with no flag, no entitlement, and no route to
 * unregister.
 */
export default function DebugSection({ classifier }: { classifier: Classifier }) {
  const { api } = useTenant();
  const [open, setOpen] = useState(false);

  const debugQ = useQuery({
    queryKey: ["classifier-debug", api.base, classifier.id],
    queryFn: () => api.getClassifierDebug(classifier.id),
    enabled: open,
  });

  return (
    <Collapsible
      title="Debug"
      meta={
        <span className="text-subtle text-label">
          internal
        </span>
      }
      onToggle={setOpen}
    >
      {debugQ.isLoading && <LoadingRow />}
      {debugQ.isError && <ErrorNote error={debugQ.error} />}
      {debugQ.data && (
        <div className="flex flex-col gap-4">
          <CommonStatus sweep={debugQ.data.sweep} />
          <FamilySection debug={debugQ.data} />
          <RawFallback debug={debugQ.data} />
        </div>
      )}
    </Collapsible>
  );
}

/** Dispatches on the same three execution tiers the backend catalog itself dispatches on. */
function FamilySection({ debug }: { debug: ClassifierDebug }) {
  switch (debug.family) {
    case "metric_drift":
      return <MetricDriftDebug baselines={debug.metric_baselines ?? []} />;
    // Kept deliberately: deleting this arm would not fail to compile, it would fall through to
    // `default` and render `DeterministicDebug` for a drift classifier, a wrong panel rather than a
    // missing one. Answering `null` through the seam renders nothing, and `RawFallback` below still
    // prints the whole payload.
    case "behavior_drift":
      return paid.debugSection(debug);
    case "encoder":
      return <EncoderDebug />;
    default:
      return <DeterministicDebug />;
  }
}

/** Full JSON, always available: covers any field a family section above doesn't render yet. */
function RawFallback({ debug }: { debug: ClassifierDebug }) {
  return <PayloadViewer label="Raw" maxHeight={280} payload={JSON.stringify(debug, null, 2)} />;
}
