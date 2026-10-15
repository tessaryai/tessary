// SPDX-License-Identifier: Apache-2.0
/** groundedness keeps only the calibrated score, so there is no fitted state beyond Sweep. */
export function EncoderDebug() {
  return (
    <p className="text-subtle m-0 text-small">
      Encoder classifier: scored per observation by the groundedness encoder. Only the calibrated
      score is persisted, so a detection's own evidence (see a detection's Raw view above) is the full record.
    </p>
  );
}
