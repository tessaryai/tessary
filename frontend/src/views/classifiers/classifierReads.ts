// SPDX-License-Identifier: Apache-2.0
import type { QueryClient } from "@tanstack/react-query";

/** Nothing on the configure page observes the chart reads, so without this they stay stale after a write. */
export function invalidateClassifierReads(qc: QueryClient, base: string) {
  for (const key of ["classifiers", "classifier-chart-scopes", "classifier-charts"]) {
    void qc.invalidateQueries({ queryKey: [key, base] });
  }
}
