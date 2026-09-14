// SPDX-License-Identifier: Apache-2.0
/*
 * A malformed-output finding told two ways: as a rate (reusing `rateStory`, worded for outputs
 * rather than calls) and as a schema, field by field, beside one failing output at a time.
 *
 * <h2>Why the schema tree is the failure signature</h2>
 * A tool-error finding's failure signature is a normalized error message — there is no other
 * structure to a tool call's failure. A schema violation has real structure: it always names a field
 * of a document the customer already declared, so the tree IS the breakdown, annotated with each
 * field's own failure count, rather than a flat list of message strings standing in for one.
 *
 * <h2>Forward-only detail (decision: no backfill)</h2>
 * `SchemaFieldView.failing` and the `notJson`/`other` buckets only count detections written after
 * this classifier started recording structured violations. `other` exists for exactly the detections
 * that predate it: real failures whose field cannot be recovered, kept visible rather than dropped.
 */
import { useEffect, useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight } from "lucide-react";
import type { components } from "../../api/generated/schema";
import type { BehaviorFinding, MalformedOutputDetail, MalformedOutputRow, MalformedOutputSchemaField } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { ErrorNote, IconButton, PageHeader, TableSkeleton, cn } from "../../ui";
import { detectorLabel, triageState, VerbButton } from "./shared";
import { RateChart, RatePins } from "./rateStory";

type RateDetail = components["schemas"]["RateDetail"];

/** The two buckets no declared field owns, as pseudo-fields the tree renders the same way a real
 *  field is rendered. Their ids are exactly the `field` query values the endpoint recognises. */
const NOT_JSON = "not_json";
const OTHER = "other";

/**
 * The finding's own header. Same anatomy as `RateHeader` in `FindingPage.tsx` — a rate finding with
 * one more word in the title, since "the rate" here is specifically a schema failure rate.
 */
export function MalformedHeader({
  rate,
  finding,
  basePath,
  inFlight,
  busy,
  onAnalyze,
}: {
  rate: RateDetail;
  finding: BehaviorFinding;
  basePath: string;
  inFlight: boolean;
  busy: boolean;
  onAnalyze: () => void;
}) {
  const rose = rate.curRate > rate.refRate;
  const since = rate.onsetAt
    ? new Date(rate.onsetAt).toLocaleDateString(undefined, { day: "numeric", month: "short" })
    : null;
  return (
    <PageHeader
      breadcrumb={[{ label: "Classifiers", to: `${basePath}/classifiers` }, { label: "Finding" }]}
      kicker={
        <span className="flex flex-wrap items-center gap-2">
          <span className="text-muted">{finding.detector ? detectorLabel(finding.detector) : "Finding"}</span>
          {finding.callSiteId && <span className="text-muted">{finding.callSiteId}</span>}
        </span>
      }
      title={
        <span>
          Schema failure rate{" "}
          <span className="font-mono font-medium text-subtle">{formatPct(rate.refRate)}</span>{" "}
          <span className="text-border-strong">→</span>{" "}
          <span className={cn("font-mono font-medium", rose ? "text-error" : "text-success")}>
            {formatPct(rate.curRate)}
          </span>
        </span>
      }
      subtitle={
        since ? <span title={finding.causeKey}>since {since} vs the rate it was fitted at</span> : undefined
      }
      actions={
        finding.triageStatus === "done" ? (
          <span className="font-mono text-label uppercase text-muted rounded-control border border-border-strong bg-raised py-1.25 px-2.75">
            {triageState(finding).label}
          </span>
        ) : (
          <VerbButton kind="filled" disabled={busy || inFlight} onClick={onAnalyze}>
            {inFlight ? "Triaging…" : "Run triage"}
          </VerbButton>
        )
      }
    />
  );
}

function formatPct(r: number): string {
  return `${(r * 100).toFixed(2)}%`;
}

/** "What moved" for a malformed-output finding: the same rate chart tool error uses, worded for the
 *  population this classifier actually measures. */
export function MalformedRate({ rate }: { rate: RateDetail }) {
  return (
    <>
      <RateChart rate={rate} label="Schema failure rate" />
      <RatePins rate={rate} unit="output" />
    </>
  );
}

type FieldId = string;

/** One row of the declared schema, indented to its depth, with its own failure count. Selectable and
 *  keyboard-reachable — it is a `<button>`, not a `<div onClick>`. */
function FieldRow({
  field,
  selected,
  onSelect,
}: {
  field: MalformedOutputSchemaField;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className={cn(
        "grid items-center text-left gap-3 py-2 px-3.5 transition-colors",
        selected ? "bg-raised" : "bg-surface hover:bg-hover",
      )}
      style={{ gridTemplateColumns: "minmax(0, 1fr) 84px 72px 84px", transitionDuration: "var(--duration-micro)" }}
    >
      <span
        className={cn("font-mono truncate text-small", selected ? "text-fg" : "text-fg-secondary")}
        style={{ paddingLeft: field.depth * 18 }}
      >
        {field.name}
      </span>
      <span className="font-mono text-muted text-small">{field.type}</span>
      <span className="text-subtle text-small">{field.required ? "required" : ""}</span>
      <span className={cn("text-right tabular-nums text-small", field.failing > 0 ? "text-error" : "text-subtle")}>
        {field.failing > 0 ? `${field.failing.toLocaleString()} failing` : ""}
      </span>
    </button>
  );
}

/**
 * The declared schema as a tree, each field annotated with its own failure count since the onset,
 * plus the two buckets no declared field owns. Selecting a row is the only interaction; the
 * "not JSON" and "other" rows behave exactly like a field row so a reader has one motion to learn.
 */
export function SchemaFieldTree({
  detail,
  selected,
  onSelect,
  caption,
}: {
  detail: MalformedOutputDetail;
  selected: FieldId;
  onSelect: (field: FieldId) => void;
  caption?: string;
}) {
  return (
    <div>
      {caption && (
        <p className="text-subtle mt-0 mx-0 mb-2 text-small">{caption}</p>
      )}
      <div className="rounded-card border border-border overflow-hidden">
        <div className="flex flex-col gap-px bg-border">
          {detail.fields.map((f) => (
            <FieldRow key={f.path} field={f} selected={selected === f.path} onSelect={() => onSelect(f.path)} />
          ))}
        </div>
      </div>
      {detail.notJson > 0 && (
        <BucketRow
          label="Not JSON at all"
          failing={detail.notJson}
          selected={selected === NOT_JSON}
          onSelect={() => onSelect(NOT_JSON)}
        />
      )}
      {detail.other > 0 && (
        <BucketRow
          label="Other violations"
          failing={detail.other}
          selected={selected === OTHER}
          onSelect={() => onSelect(OTHER)}
        />
      )}
      <p className="text-subtle mt-2 mx-0 mb-0 text-small">
        Tessary read this schema from the connected repository. Counts are outputs since the onset.
      </p>
    </div>
  );
}

function BucketRow({
  label,
  failing,
  selected,
  onSelect,
}: {
  label: string;
  failing: number;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className={cn(
        "grid w-full items-center text-left gap-3 py-2.25 px-3.5 rounded-card border transition-colors mt-2",
        selected ? "bg-raised border-border-strong" : "bg-surface border-border hover:bg-hover",
      )}
      style={{ gridTemplateColumns: "minmax(0, 1fr) 84px", transitionDuration: "var(--duration-micro)" }}
    >
      <span className="text-fg-secondary text-small">{label}</span>
      <span className="text-error text-right tabular-nums text-small">{failing.toLocaleString()} failing</span>
    </button>
  );
}

/** Where to send a reader who clicks a trace id — see `secretStory.TraceLinker`'s own note. */
export type TraceLinker = (traceId: string, spanId?: string | null) => string;

const PAGE = 20;

function fieldLabel(detail: MalformedOutputDetail, field: FieldId): string {
  if (field === NOT_JSON) return "an output that was not JSON at all";
  if (field === OTHER) return "an output with an untracked violation";
  const f = detail.fields.find((x) => x.path === field);
  return f ? f.name : field;
}

/**
 * One failing output for the selected field: the validated document with its offending lines
 * highlighted, the violation message, and a step through every other output that failed the same
 * way. Paged rather than read whole — a widely-used field can fail on thousands of outputs.
 */
export function FailingOutput({
  findingId,
  field,
  detail,
  linkToTrace,
}: {
  findingId: string;
  field: FieldId;
  detail: MalformedOutputDetail;
  linkToTrace: TraceLinker;
}) {
  const { api } = useTenant();
  const [index, setIndex] = useState(0);

  const q = useInfiniteQuery({
    queryKey: ["malformed-outputs", api.base, findingId, field],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.getMalformedOutputs(findingId, field, { limit: PAGE, cursor: pageParam }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });

  // A field switch is a fresh population; the position a reader was at in the last one means
  // nothing here.
  useEffect(() => {
    setIndex(0);
  }, [field]);

  const rows: MalformedOutputRow[] = (q.data?.pages ?? []).flatMap((p) => p.rows);
  const total = q.data?.pages[0]?.total ?? 0;
  const row = rows[index];

  const next = async () => {
    if (index + 1 < rows.length) {
      setIndex(index + 1);
      return;
    }
    if (q.hasNextPage && !q.isFetchingNextPage) {
      await q.fetchNextPage();
      setIndex(index + 1);
    }
  };
  const prev = () => setIndex((i) => Math.max(0, i - 1));

  return (
    <div>
      <div className="flex items-center justify-between gap-3 mb-2">
        <span className="text-subtle text-small">
          A failing output for <span className="font-mono text-fg-secondary">{fieldLabel(detail, field)}</span>
        </span>
        <div className="flex items-center gap-2">
          <IconButton label="Previous output" variant="secondary" size="sm" onClick={prev} disabled={index === 0}>
            <ChevronLeft size={14} strokeWidth={1.75} />
          </IconButton>
          <span className="text-muted text-small" style={{ minWidth: 56, textAlign: "center" }}>
            {total > 0 ? `${index + 1} of ${total.toLocaleString()}` : "0 of 0"}
          </span>
          <IconButton
            label="Next output"
            variant="secondary"
            size="sm"
            onClick={() => void next()}
            disabled={index + 1 >= total || q.isFetchingNextPage}
          >
            <ChevronRight size={14} strokeWidth={1.75} />
          </IconButton>
        </div>
      </div>

      {q.isError ? (
        <ErrorNote error={q.error} />
      ) : q.isLoading ? (
        <TableSkeleton rows={4} cols={1} />
      ) : !row ? (
        <p className="text-subtle m-0 text-body" style={{ maxWidth: 560 }}>
          The outputs behind this field have aged out of retention.
        </p>
      ) : (
        <div className="rounded-card border border-border bg-surface pt-3.5 px-4 pb-4">
          <div className="flex items-center justify-between gap-3 mb-2.5">
            <span className="text-muted text-small">
              {row.name && <span className="font-mono text-fg">{row.name}</span>}
              {row.name && " · "}
              {new Date(row.startedAt).toLocaleString()}
            </span>
            <a
              className="font-mono text-link hover:text-link-hover transition-colors text-small"
              href={linkToTrace(row.traceId, row.spanId)}
            >
              {row.traceId.slice(0, 8)}…
            </a>
          </div>
          <DocumentView document={row.document} highlightLines={row.highlightLines} />
          {row.message && (
            <p className="font-mono text-error mt-2.5 mx-0 mb-0 text-small">{row.message}</p>
          )}
        </div>
      )}
    </div>
  );
}

function DocumentView({ document, highlightLines }: { document: string | null; highlightLines: number[] }) {
  if (document == null) {
    return <p className="text-subtle m-0 text-body">This output was not recorded.</p>;
  }
  const highlighted = new Set(highlightLines);
  const lines = document.split("\n");
  return (
    <pre
      className="font-mono text-fg-secondary rounded-control bg-raised m-0 py-2 px-0 text-code"
      style={{ overflowX: "auto" }}
    >
      {lines.map((line, i) => {
        const lineNo = i + 1;
        const bad = highlighted.has(lineNo);
        return (
          <div
            key={lineNo}
            className={bad ? "text-error" : undefined}
            style={{ padding: "0 12px", background: bad ? "var(--color-error-subtle)" : undefined }}
          >
            {line.length > 0 ? line : " "}
          </div>
        );
      })}
    </pre>
  );
}

/**
 * "How outputs broke": the schema tree beside one failing output for whichever field is selected.
 * Exported as one block so `CasePage.tsx` can drop it in unchanged — the finding page and the case
 * page must never disagree about which field is broken or what a failing output for it looks like,
 * and reading them off the same `MalformedOutputDetail` is what guarantees that.
 */
export function HowOutputsBroke({
  findingId,
  detail,
  linkToTrace,
  caption,
}: {
  findingId: string;
  detail: MalformedOutputDetail;
  linkToTrace: TraceLinker;
  caption?: string;
}) {
  const initial: FieldId =
    detail.fields.find((f) => f.failing > 0)?.path ??
    (detail.notJson > 0 ? NOT_JSON : detail.other > 0 ? OTHER : (detail.fields[0]?.path ?? NOT_JSON));
  const [selected, setSelected] = useState<FieldId>(initial);

  return (
    <div className="grid gap-5 items-start" style={{ gridTemplateColumns: "repeat(2, minmax(0, 1fr))" }}>
      <SchemaFieldTree detail={detail} selected={selected} onSelect={setSelected} caption={caption} />
      <FailingOutput findingId={findingId} field={selected} detail={detail} linkToTrace={linkToTrace} />
    </div>
  );
}
