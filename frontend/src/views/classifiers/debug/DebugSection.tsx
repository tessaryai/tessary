// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { Classifier, ClassifierDebug } from "../../../api/types";
import { useTenant } from "../../../tenant/TenantContext";
import { Collapsible, ErrorNote, LoadingRow } from "../../../ui";
import { PayloadViewer } from "../../components/PayloadViewer";
import { CommonStatus } from "./sections/CommonStatus";
import { DecisionDebug } from "./sections/DecisionDebug";
import { DeterministicDebug } from "./sections/DeterministicDebug";
import { EncoderDebug } from "./sections/EncoderDebug";
import { MetricDriftDebug } from "./sections/MetricDriftDebug";

/**
 * Not product surface: the query runs only once opened and the subtree is its own lazy chunk. Deleting
 * this directory and its one mount removes the feature cleanly.
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

function FamilySection({ debug }: { debug: ClassifierDebug }) {
  switch (debug.family) {
    case "metric_drift":
      return <MetricDriftDebug baselines={debug.metric_baselines ?? []} />;
    case "encoder":
      return <EncoderDebug />;
    case "decision":
      return <DecisionDebug />;
    default:
      return <DeterministicDebug />;
  }
}

function RawFallback({ debug }: { debug: ClassifierDebug }) {
  return <PayloadViewer label="Raw" maxHeight={280} payload={JSON.stringify(debug, null, 2)} />;
}
