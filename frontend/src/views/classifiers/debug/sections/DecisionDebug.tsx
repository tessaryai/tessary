// SPDX-License-Identifier: Apache-2.0
/** frustration keeps each call's request and response on the assessment row; no fitted state beyond Sweep. */
export function DecisionDebug() {
  return (
    <p className="text-subtle m-0 text-small">
      Decision-model classifier: each eligible user turn is scored by the provider on your key. Every call
      is recorded with its exact request and response, and each call site's learned rate is in Tuning.
    </p>
  );
}
