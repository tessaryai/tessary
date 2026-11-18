// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { AlertTriangle, XCircle } from "lucide-react";
import { Badge } from "../../ui";

/** `production` is the label applications fetch by default, so it alone wears the accent. */
export function LabelBadge({ label, text }: { label: string; text: string }) {
  return <Badge tone={label === "production" ? "accent" : "neutral"}>{text}</Badge>;
}

const NOTE = {
  error: { Icon: XCircle, color: "text-error" },
  warning: { Icon: AlertTriangle, color: "text-warning" },
};

/** Grey text with a status icon: a status hue is never body text, and warning shares info's blue. */
export function Note({ tone, children }: { tone: "error" | "warning"; children: ReactNode }) {
  const { Icon, color } = NOTE[tone];
  return (
    <p className="flex items-start gap-1.5 text-small text-fg-secondary">
      <Icon size={13} strokeWidth={1.75} aria-hidden="true" className={`mt-0.5 shrink-0 ${color}`} />
      <span>{children}</span>
    </p>
  );
}
