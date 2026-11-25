// SPDX-License-Identifier: Apache-2.0
import { Link } from "react-router-dom";
import type { EndUserHitCount } from "../../api/types";
import { hitChip, hitChipTooltip, lossLabel } from "./usersWords";

/** A case that shifts a measure for all traffic, or one the read did not list, has no chip. */
export const hasHitChip = (count: EndUserHitCount | undefined): count is EndUserHitCount & { user_ids_hit: number } =>
  count != null && count.count_kind !== "not_applicable" && count.user_ids_hit != null;

/** Board P0: the user IDs a case hit, and its loss only when the Users page finds evidence of loss. */
export function UserIdsHitChip({ count, basePath }: { count: EndUserHitCount | undefined; basePath: string }) {
  if (!hasHitChip(count)) return null;
  const loss = count.lost != null;
  return (
    <Link
      to={loss ? `${basePath}/users/issues/${encodeURIComponent(count.case_id)}` : `${basePath}/users`}
      title={hitChipTooltip(count.count_kind, count.refusal)}
      className="flex-none inline-flex items-center gap-2 text-small tabular-nums"
    >
      <span className="bg-raised text-fg-secondary rounded-pill py-0.5 px-2.5">{hitChip(count.user_ids_hit)}</span>
      {loss && (
        <span className="bg-error-subtle text-error rounded-pill py-0.5 px-2.5">
          {lossLabel(count.lost!, count.low!, count.high!)}
        </span>
      )}
    </Link>
  );
}
