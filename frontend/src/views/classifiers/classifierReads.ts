// SPDX-License-Identifier: Apache-2.0
import type { QueryClient } from "@tanstack/react-query";

/**
 * Marks stale every read a classifier write can change: the classifier list, and what the Classifiers page charts
 * and its Configure menu says. Nothing on the configure page observes the chart reads, so without this the charts
 * stay as they were for the whole staleTime after a switch or a scope edit.
 */
export function invalidateClassifierReads(qc: QueryClient, base: string) {
  for (const key of ["classifiers", "classifier-chart-scopes", "classifier-charts"]) {
    void qc.invalidateQueries({ queryKey: [key, base] });
  }
}
