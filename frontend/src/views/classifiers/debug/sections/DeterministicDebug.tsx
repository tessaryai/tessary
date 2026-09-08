// SPDX-License-Identifier: Apache-2.0
/** secret_leak / malformed_output: no model call, no fitted state; the detection's own evidence
 *  (matched pattern for secret_leak, schema diff for malformed_output) already carries the whole
 *  decision, so there is nothing this family adds beyond the common Sweep section. */
export function DeterministicDebug() {
  return (
    <p className="text-subtle m-0 text-small">
      Deterministic classifier: no model call and no fitted baseline to inspect. Every decision lives
      in the detection's own evidence (see a detection's Raw view above).
    </p>
  );
}
