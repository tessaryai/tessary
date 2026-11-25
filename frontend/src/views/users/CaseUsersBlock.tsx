// SPDX-License-Identifier: Apache-2.0
import { useQuery } from "@tanstack/react-query";
import { useProjectApi } from "../../tenant/TenantContext";
import { Card, ErrorNote, LoadingRow } from "../../ui";
import { endUserQueries } from "./usersQueries";
import { caseCountLine, NOT_APPLICABLE } from "./usersWords";

/** Board P4: the user IDs a case hit, read on its own so a failed read stays inside the block. */
export function CaseUsersBlock({ caseId }: { caseId: string }) {
  const api = useProjectApi();
  const q = useQuery(endUserQueries.caseBlock(api, caseId));
  if (q.isPending) return <LoadingRow />;
  if (q.isError) return <ErrorNote error={q.error} />;
  const block = q.data;
  return (
    <Card className="border border-border flex flex-col gap-3 p-5">
      {block.count_kind === "not_applicable" ? (
        <p className="m-0 text-body text-fg">{NOT_APPLICABLE}</p>
      ) : (
        <CountLine {...caseCountLine(block)} />
      )}
    </Card>
  );
}

function CountLine({ lead, rest }: { lead: string; rest: string }) {
  return (
    <p className="m-0 text-body text-fg tabular-nums">
      <strong className="font-medium">{lead}</strong>
      {rest}
    </p>
  );
}
