// SPDX-License-Identifier: Apache-2.0
/*
 * The body of the case page's Resolve dialog: a one-line reason, and on a frustration case the
 * disposition that decides what the classifier counts next.
 *
 * Only a frustration case offers the choice, because only there does the answer change anything:
 * both restart the call site's learned rate, and a false alarm also clears the conversations the
 * case cites. Every other case closes on the reason alone, and the server refuses a disposition on
 * one.
 */
import { useState } from "react";
import type { CaseDisposition } from "../../api/types";
import { Button, ErrorNote, Input } from "../../ui";

const DISPOSITIONS: [CaseDisposition, string, string][] = [
  ["fixed", "Fixed", "The accumulator restarts and the call site re-learns its normal rate from here."],
  [
    "false_alarm",
    "False alarm",
    "The same, and the sessions this case cites stop counting as frustrated and become scorable again.",
  ],
];

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
  const offersDisposition = detector === "frustration";
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
          {DISPOSITIONS.map(([value, label, hint]) => (
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
