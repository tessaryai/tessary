// SPDX-License-Identifier: Apache-2.0
/** secret_leak / malformed_output: no model call and no fitted state beyond Sweep. */
export function DeterministicDebug() {
  return (
    <p className="text-subtle m-0 text-small">
      Deterministic classifier: no model call and no fitted baseline to inspect. Every decision lives
      in the detection's own evidence (see a detection's Raw view above).
    </p>
  );
}
