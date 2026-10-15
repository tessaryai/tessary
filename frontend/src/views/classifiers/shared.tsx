// SPDX-License-Identifier: Apache-2.0
import type { BehaviorFinding } from "../../api/types";
import { cn } from "../../ui";

export const CONTAINER: React.CSSProperties = { padding: "36px 40px 56px" };

export const PROVIDER_PAUSES: Record<string, { label: string; explained: string }> = {
  provider_rejected: {
    label: "Provider rejected the key",
    explained:
      "The provider rejected the stored key, so no messages are being scored. Fix the key under Settings, Providers, then retry.",
  },
  request_refused: {
    label: "Provider refused the request",
    explained:
      "The provider accepted the stored key but refused the request itself, so no messages are being scored. Usually the model this classifier is set to is not one the provider serves: check it under Settings, Models, then retry.",
  },
  no_provider: {
    label: "No provider key",
    explained:
      "There is no key for the provider this classifier runs on, so no messages are being scored. Add one under Settings, Providers, then retry.",
  },
  no_credit: {
    label: "No credit left",
    explained:
      "This organization has used all of its credit on the provider this classifier runs on, so no messages are being scored. Top up that provider, or add another key under Settings, Providers, then retry.",
  },
  platform_unavailable: {
    label: "Provider unavailable",
    explained:
      "The provider this classifier runs on is not accepting requests, so no messages are being scored. Nothing needs to change on your side. It retries on its own.",
  },
};

export function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-subtle text-small">
        {label}
      </dt>
      <dd className="min-w-0 text-muted m-0 text-small">
        {children}
      </dd>
    </>
  );
}

/** Absorbing re-pins a population's reference and closes the finding, so it is never the filled default. */
export function VerbButton({
  kind,
  onClick,
  disabled,
  children,
}: {
  kind: "filled" | "outline";
  onClick: () => void;
  disabled?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={cn(
        "rounded-control border border-border-strong px-3 text-small whitespace-nowrap transition-colors disabled:opacity-50",
        kind === "filled" ? "bg-raised text-fg hover:bg-overlay" : "bg-transparent text-muted hover:text-fg",
      )}
      style={{ height: 29, transitionDuration: "var(--duration-micro)" }}
    >
      {children}
    </button>
  );
}

/** A run that gave up says so and offers the run again rather than staying on "Triaging…". */
export function RunTriageButton({
  finding,
  busy,
  onAnalyze,
}: {
  finding: BehaviorFinding;
  busy: boolean;
  onAnalyze: () => void;
}) {
  const inFlight = finding.triageStatus === "in_flight";
  const failed = finding.triageStatus === "failed";
  const button = (
    <VerbButton kind="filled" disabled={busy || inFlight} onClick={onAnalyze}>
      {inFlight ? "Triaging…" : failed ? "Run triage again" : "Run triage"}
    </VerbButton>
  );
  if (!failed) return button;
  return (
    <span className="flex flex-wrap items-center gap-2.5">
      <span className="text-error text-small">{triageState(finding).label}</span>
      {button}
    </span>
  );
}

/**
 * Rendered only while the finding is unruled: the first ruling, triage's or a person's, freezes it and
 * every later verb 409s.
 */
export function ResolveVerbs({
  busy,
  onResolve,
}: {
  busy: boolean;
  onResolve: (action: "expected" | "not_expected") => void;
}) {
  return (
    <>
      <VerbButton kind="outline" disabled={busy} onClick={() => onResolve("expected")}>
        Absorb as legitimate
      </VerbButton>
      <VerbButton kind="outline" disabled={busy} onClick={() => onResolve("not_expected")}>
        Confirm and open a case
      </VerbButton>
    </>
  );
}

/**
 * `positive` is the only state that pages a person, so it alone is emphatic. A `done` status with no
 * verdict cannot happen; the fallback avoids a blank cell.
 */
export type TriageState = { label: string; tone: "positive" | "closed" | "waiting" | "failed" };

export function triageState(finding: BehaviorFinding): TriageState {
  if (finding.triageStatus === "pending") return { label: "Pending", tone: "waiting" };
  if (finding.triageStatus === "in_flight") return { label: "Triaging", tone: "waiting" };
  if (finding.triageStatus === "failed") return { label: "Triage failed", tone: "failed" };
  // `humanVerdictAt` means a person's ruling landed first, so name the verb they pressed.
  const byPerson = finding.humanVerdictAt != null;
  if (finding.triageVerdict === "positive") {
    return byPerson ? { label: "Real deviation", tone: "positive" } : { label: "Positive", tone: "positive" };
  }
  if (finding.triageVerdict === "negative") {
    return byPerson
      ? { label: "Legitimate, absorb", tone: "closed" }
      : { label: "Closed · negative", tone: "closed" };
  }
  return { label: "Closed", tone: "closed" };
}

export function isClosedByTriage(finding: BehaviorFinding): boolean {
  return finding.triageAction === "closed";
}

export function chainWords(finding: BehaviorFinding): string {
  return [
    `seen ${finding.traceCount}×`,
    finding.triageStatus === "done"
      ? `${finding.humanVerdictAt != null ? "a person" : "triage"} ruled ${finding.triageVerdict ?? "unknown"}`
      : finding.triageStatus === "in_flight"
        ? "triage running"
        : finding.triageStatus === "failed"
          ? "triage failed"
          : "not triaged yet",
  ]
    .filter(Boolean)
    .join(" · ");
}

/** The server sends the key; this only words it. Never re-derive it from the cause. */
export function detectorLabel(key: string): string {
  return key
    .split("_")
    .map((word, i) => (i === 0 ? word.charAt(0).toUpperCase() + word.slice(1) : word))
    .join(" ");
}

export function stamp(iso: string): string {
  const d = new Date(iso);
  return `${d.toLocaleDateString(undefined, { day: "numeric", month: "short" })} ${d.toLocaleTimeString(
    undefined,
    { hour: "2-digit", minute: "2-digit", hour12: false },
  )}`;
}

export function ago(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  if (ms < 0) return absoluteDate(iso);
  const mins = Math.floor(ms / 60_000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return days < 30 ? `${days}d ago` : absoluteDate(iso);
}

function absoluteDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric" });
}
