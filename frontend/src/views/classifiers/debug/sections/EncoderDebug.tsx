// SPDX-License-Identifier: Apache-2.0
/** frustration / groundedness: scored per observation by classify-service. Only the calibrated
 *  score survives into the persisted verdict; the raw pre-calibration score and model version are
 *  not retained anywhere, so there is no additional fitted state this family can show beyond Sweep. */
export function EncoderDebug() {
  return (
    <p className="text-subtle m-0 text-small">
      Encoder classifier: scored by classify-service per observation. Only the calibrated score is
      persisted, so a detection's own evidence (see a detection's Raw view above) is the full record.
    </p>
  );
}
