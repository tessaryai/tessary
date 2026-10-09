// SPDX-License-Identifier: Apache-2.0
/** frustration: each eligible user turn is scored by a decision model on the org's own key. The full
 *  request and response of every call are kept on the assessment row, and the per-call-site rate state
 *  is in the Tuning section, so there is no further fitted state this family can show beyond Sweep. */
export function DecisionDebug() {
  return (
    <p className="text-subtle m-0 text-small">
      Decision-model classifier: each eligible user turn is scored by the provider on your key. Every call
      is recorded with its exact request and response, and each call site's learned rate is in Tuning.
    </p>
  );
}
