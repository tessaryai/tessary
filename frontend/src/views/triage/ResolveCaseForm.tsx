// SPDX-License-Identifier: Apache-2.0
/*
 * The body of the case page's Resolve dialog: a one-line reason, and on a frustration or
 * groundedness case the disposition that decides what the classifier counts next.
 *
 * Only those two offer the choice, because only there does the answer change anything: both
 * restart the call site's learned rate, and a false alarm also clears what the case cites, the
 * sessions of a frustration case or the flagged answers of a groundedness one. Every other case
 * closes on the reason alone, and the server refuses a disposition on one.
 */
import { useState } from "react";
import type { CaseDisposition } from "../../api/types";
import { Button, ErrorNote, Input } from "../../ui";
import { GROUNDEDNESS_DETECTOR } from "../classifiers/groundedness";

const FIXED_HINT = "The accumulator restarts and the call site re-learns its normal rate from here.";

/** What a false alarm clears, per detector that offers one. */
const FALSE_ALARM_HINTS: Record<string, string> = {
  frustration: "The same, and the sessions this case cites stop counting as frustrated and become scorable again.",
  [GROUNDEDNESS_DETECTOR]: "The same, and the answers this case cites are no longer flagged.",
};

export function ResolveCaseForm({
  detector,
  pending,
  error,
  onCancel,
  onResolve,
}: {
  detector: string;
  pending: boolean;
  error: unknown;
  onCancel: () => void;
  onResolve: (reason: string, disposition?: CaseDisposition) => void;
}) {
  const [reason, setReason] = useState("");
  const falseAlarmHint = FALSE_ALARM_HINTS[detector];
  const offersDisposition = falseAlarmHint != null;
  const [disposition, setDisposition] = useState<CaseDisposition>("fixed");

  return (
    <>
      <p className="text-muted mt-0 mx-0 mb-3.5 text-body">
        One line on what this turned out to be. It is the only thing that makes a resolved case worth reading later.
      </p>
      <Input
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder="Traffic mix shifted toward enterprise leads"
        aria-label="Reason"
        autoFocus
      />
      {offersDisposition && (
        <div className="mt-3.5 rounded-card border border-border divide-y divide-border" role="radiogroup">
          {(
            [
              ["fixed", "Fixed", FIXED_HINT],
              ["false_alarm", "False alarm", falseAlarmHint],
            ] as [CaseDisposition, string, string][]
          ).map(([value, label, hint]) => (
            <label key={value} className="flex items-start gap-3 px-4 py-3 cursor-pointer">
              <input
                type="radio"
                name="case-disposition"
                value={value}
                checked={disposition === value}
                disabled={pending}
                onChange={() => setDisposition(value)}
                className="mt-1"
              />
              <span className="min-w-0">
                <span className="block text-small text-fg">{label}</span>
                <span className="block text-label text-muted">{hint}</span>
              </span>
            </label>
          ))}
        </div>
      )}
      {error != null && <ErrorNote error={error} />}
      <div className="flex justify-end gap-2 mt-4.5">
        <Button variant="ghost" size="sm" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          size="sm"
          onClick={() => onResolve(reason, offersDisposition ? disposition : undefined)}
          disabled={reason.trim().length === 0 || pending}
        >
          {pending ? "Resolving…" : "Resolve case"}
        </Button>
      </div>
    </>
  );
}

/** How a resolved case's disposition reads in its closing line, or nothing for a case without one. */
export function dispositionPhrase(disposition: string | null | undefined): string {
  if (disposition === "fixed") return " as fixed";
  if (disposition === "false_alarm") return " as a false alarm";
  return "";
}
