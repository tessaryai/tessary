// SPDX-License-Identifier: Apache-2.0
/*
 * The empty-queue screen: traces → findings → cases, drawn every time, with the first unsatisfied
 * stage lit. {@link resolveState} decides what it says; this decides only how it looks.
 *
 * The pipeline is what makes the four states one component. A reader who arrives at any of them sees
 * the same three nodes in the same places and reads the difference off which one is lit — rather
 * than four bespoke screens that happen to share a headline slot.
 */
import { Link } from "react-router-dom";
import { CopyButton, useToast } from "../../ui";
import type { Action, EmptyState, NodeTone, PipelineNode } from "./emptyState";

/**
 * The four tones, as the token layer already spells them.
 *
 * `warn` is the only hue on this screen, and it is the blue the palette reserves for info/warning —
 * never red. Nothing here is an error: a stopped exporter is a thing to go and check, and painting
 * it red would put a failure state on a screen whose whole job is to say that nothing is failing.
 */
const TONE: Record<NodeTone, { box: string; label: string; value: string; sub: string }> = {
  satisfied: {
    box: "bg-surface border border-border",
    label: "text-subtle",
    value: "text-fg-secondary",
    sub: "text-subtle",
  },
  focus: {
    box: "bg-surface border border-[color:var(--color-accent-edge)]",
    label: "text-muted",
    value: "text-fg",
    sub: "text-muted",
  },
  warn: {
    box: "bg-[color:var(--color-warning-subtle)] border border-border",
    label: "text-muted",
    value: "text-warning",
    sub: "text-muted",
  },
  empty: {
    box: "bg-bg border border-dashed border-border",
    label: "text-subtle",
    value: "text-subtle",
    sub: "text-subtle",
  },
};

export function PipelineEmpty({ state }: { state: EmptyState }) {
  const [first, second, third] = state.nodes;
  return (
    <div className="pt-16 px-0 pb-8 flex flex-col items-center">
      <h2 className="text-h1 text-fg text-center mt-0 mx-0 mb-0" style={{ maxWidth: 520 }}>
        {state.title}
      </h2>
      <p
        className="text-muted text-body mt-2.5 mx-0 mb-0 text-center"
        style={{ maxWidth: 470, textWrap: "pretty" }}
      >
        {state.body}
      </p>

      {/* Wraps rather than overflows: three fixed-width nodes and two rules do not fit a narrow
          main column, and a pipeline that runs off the edge tells the reader less than one that
          folds. */}
      <div className="flex flex-wrap items-stretch justify-center mt-10">
        <Node node={first} />
        <Rule />
        <Node node={second} />
        <Rule />
        <Node node={third} />
      </div>

      {state.note && (
        <div className="flex items-center gap-1.75 mt-4">
          <span className="size-1.5 rounded-pill bg-warning shrink-0" aria-hidden="true" />
          <span className="text-small text-warning">{state.note}</span>
        </div>
      )}

      <div className="flex items-center gap-3 mt-8">
        <ActionControl action={state.primary} primary />
        {state.secondary && <ActionControl action={state.secondary} />}
      </div>
    </div>
  );
}

function Node({ node }: { node: PipelineNode }) {
  const tone = TONE[node.tone];
  return (
    <div className={`w-[210px] rounded-card px-5 py-4.5 flex flex-col gap-1.5 ${tone.box}`}>
      <div className={`font-mono text-label uppercase ${tone.label}`}>{node.label}</div>
      <div
        className={`font-mono text-h2 ${tone.value}`}
        style={{ fontVariantNumeric: "tabular-nums" }}
      >
        {node.value}
      </div>
      <div className={`text-small ${tone.sub}`}>{node.sub}</div>
      {node.progress != null && (
        <div
          className="mt-1 h-1 rounded-pill bg-raised overflow-hidden"
          role="progressbar"
          aria-valuenow={Math.round(node.progress * 100)}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label="Baseline fitting progress"
        >
          <div
            className="h-full rounded-pill bg-accent"
            style={{
              width: `${Math.max(2, Math.round(node.progress * 100))}%`,
              transition: "width var(--duration-transition)",
            }}
          />
        </div>
      )}
    </div>
  );
}

/** The connector. Decorative, and short enough to be the thing that gives way when the row wraps. */
function Rule() {
  return (
    <div className="w-14 flex items-center justify-center" aria-hidden="true">
      <div className="w-full h-px bg-border" />
    </div>
  );
}

/**
 * A link action renders as an anchor, not a button with a navigate handler: middle-click, copy-link
 * and "open in new tab" all matter on a screen whose entire purpose is sending someone elsewhere.
 * The styling mirrors `Button`'s md size and primary/ghost variants rather than importing it, since
 * `Button` renders a `<button>` and cannot host an href.
 */
const LINK_BASE =
  "inline-flex items-center justify-center h-9 px-3.5 text-small rounded-control " +
  "whitespace-nowrap select-none transition-colors";

function ActionControl({ action, primary }: { action: Action; primary?: boolean }) {
  const toast = useToast();

  if (action.kind === "link") {
    return (
      <Link
        to={action.to}
        className={
          primary
            ? `${LINK_BASE} font-medium bg-accent text-[color:var(--color-accent-text-on)] hover:bg-accent-hover`
            : `${LINK_BASE} text-muted hover:bg-hover hover:text-fg`
        }
        style={{ transitionDuration: "var(--duration-micro)" }}
      >
        {action.label}
      </Link>
    );
  }

  // Built here rather than in `resolveState` because it is a property of the browser, not of the
  // project's state — the same reason ConnectGate builds it at render time.
  const endpoint = `${typeof window !== "undefined" ? window.location.origin : ""}/v1/traces`;
  return (
    <CopyButton
      value={endpoint}
      label={action.label}
      variant="secondary"
      size="md"
      icon={false}
      onCopied={() => toast.success("Endpoint copied", endpoint)}
      onCopyFailed={() => toast.error("Could not copy", endpoint)}
    />
  );
}
