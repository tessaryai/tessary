// SPDX-License-Identifier: Apache-2.0
import type { Classifier } from "../api/types";

/**
 * Where a trace opens, and the span within it when there is one. Absolute, from the project's base path:
 * these hand-build the href for a plain `<a>`, and a relative href on one resolves against the URL rather
 * than the route tree.
 */
export function traceLinker(basePath: string) {
  return (traceId: string, spanId?: string | null) =>
    `${basePath}/traces/${encodeURIComponent(traceId)}${spanId ? `#${encodeURIComponent(spanId)}` : ""}`;
}

/** Where a classifier opens: its configure page, absolute from the project's base path. */
export function classifierHref(classifier: Pick<Classifier, "id">, basePath: string): string {
  return `${basePath}/classifiers/${encodeURIComponent(classifier.id)}`;
}
