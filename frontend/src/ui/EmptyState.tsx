// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";

export function EmptyState({
  title,
  body,
  action,
}: {
  title: ReactNode;
  body?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center text-center px-6 py-16 mx-auto max-w-md">
      <h3 className="text-h2 text-fg mb-2">{title}</h3>
      {body && <p className="text-body text-muted mb-6">{body}</p>}
      {action && <div className="flex items-center gap-3">{action}</div>}
    </div>
  );
}
