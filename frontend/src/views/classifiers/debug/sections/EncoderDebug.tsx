// SPDX-License-Identifier: Apache-2.0
/** groundedness: scored per observation by the groundedness encoder (serve.py). Only the calibrated
 *  score survives into the persisted verdict; the raw pre-calibration score and model version are not
 *  retained anywhere, so there is no additional fitted state this family can show beyond Sweep. */
export function EncoderDebug() {
  return (
    <p className="text-subtle m-0 text-small">
      Encoder classifier: scored per observation by the groundedness encoder. Only the calibrated
      score is persisted, so a detection's own evidence (see a detection's Raw view above) is the full record.
    </p>
  );
}
