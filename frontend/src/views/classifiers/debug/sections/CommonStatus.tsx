// SPDX-License-Identifier: Apache-2.0
import type { ClassifierDebugSweep } from "../../../../api/types";

/**
 * The sweep-job row in full: cursor, lease, attempts. Renders for every classifier regardless of
 * family, since every built-in dispatches through the same job queue.
 */
export function CommonStatus({ sweep }: { sweep: ClassifierDebugSweep }) {
  return (
    <section>
      <h4 className="font-mono text-label uppercase text-muted mb-2">
        Sweep
      </h4>
      <dl
      className="gap-y-1.5 gap-x-3 m-0"
      style={{ display: "grid", gridTemplateColumns: "104px minmax(0, 1fr)" }}
      >
        <Fact label="Status">{sweep.status ?? "never enqueued"}</Fact>
        <Fact label="Attempts">{sweep.attempts}</Fact>
        {sweep.last_error && (
          <Fact label="Last error">
            <span className="text-warning">{sweep.last_error}</span>
          </Fact>
        )}
        <Fact label="Cursor at">{sweep.cursor_at ?? "–"}</Fact>
        <Fact label="Cursor id">{sweep.cursor_id ?? "–"}</Fact>
        <Fact label="Lease owner">{sweep.lease_owner ?? "–"}</Fact>
        <Fact label="Lease expires">{sweep.lease_expires_at ?? "–"}</Fact>
        <Fact label="Updated">{sweep.updated_at ?? "–"}</Fact>
      </dl>
    </section>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-subtle text-small">
        {label}
      </dt>
      <dd className="min-w-0 font-mono text-muted m-0 text-small" style={{ wordBreak: "break-all" }}>
        {children}
      </dd>
    </>
  );
}
