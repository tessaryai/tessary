// SPDX-License-Identifier: Apache-2.0
// The empty-queue screen: traces → findings → cases with the first unsatisfied stage lit.
// {@link resolveState} decides what it says; this decides only how it looks.
import { Link } from "react-router-dom";
import { CopyButton, useToast } from "../../ui";
import type { Action, EmptyState, NodeTone, PipelineNode } from "./emptyState";

/** `warn` is the only hue here, the palette's info blue, never red: nothing on this screen is an error. */
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

      {/* Wraps rather than overflows on a narrow main column. */}
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
 * Link actions are anchors, not navigate buttons, so middle-click and copy-link work. Styled to mirror
 * `Button`'s md primary/ghost, since `Button` renders a `<button>`.
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

  // Built here, not in `resolveState`: the origin is a property of the browser, not the project.
  const endpoint = `${window.location.origin}/v1/traces`;
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
