// SPDX-License-Identifier: Apache-2.0
import type { Classifier } from "../api/types";

/** Absolute: a relative href on a plain `<a>` resolves against the URL, not the route tree. */
export function traceLinker(basePath: string) {
  return (traceId: string, spanId?: string | null) =>
    `${basePath}/traces/${encodeURIComponent(traceId)}${spanId ? `#${encodeURIComponent(spanId)}` : ""}`;
}

export function classifierHref(classifier: Pick<Classifier, "id">, basePath: string): string {
  return `${basePath}/classifiers/${encodeURIComponent(classifier.id)}`;
}
