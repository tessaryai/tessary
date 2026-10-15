// SPDX-License-Identifier: Apache-2.0
// A schema violation always names a declared field, so the annotated schema tree is the failure
// signature. Per-field counts only cover detections recorded after structured violations; `other`
// keeps older failures visible.
import { useEffect, useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight } from "lucide-react";
import type { components } from "../../api/generated/schema";
import type { BehaviorFinding, MalformedOutputDetail, MalformedOutputRow, MalformedOutputSchemaField } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { ErrorNote, IconButton, PageHeader, TableSkeleton, cn } from "../../ui";
import { RunTriageButton, detectorLabel, triageState } from "./shared";
import { RateChart, RatePins } from "./rateStory";

type RateDetail = components["schemas"]["RateDetail"];

/** Their ids are the `field` query values the endpoint recognises. */
const NOT_JSON = "not_json";
const OTHER = "other";

export function MalformedHeader({
  rate,
  finding,
  basePath,
  busy,
  onAnalyze,
}: {
  rate: RateDetail;
  finding: BehaviorFinding;
  basePath: string;
  busy: boolean;
  onAnalyze: () => void;
}) {
  const rose = rate.curRate > rate.refRate;
  const since = rate.onsetAt
    ? new Date(rate.onsetAt).toLocaleDateString(undefined, { day: "numeric", month: "short" })
    : null;
  return (
    <PageHeader
      breadcrumb={[{ label: "Triage", to: `${basePath}/triage` }, { label: "Finding" }]}
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
          <RunTriageButton finding={finding} busy={busy} onAnalyze={onAnalyze} />
        )
      }
    />
  );
}

function formatPct(r: number): string {
  return `${(r * 100).toFixed(2)}%`;
}

export function MalformedRate({ rate }: { rate: RateDetail }) {
  return (
    <>
      <RateChart rate={rate} label="Schema failure rate" />
      <RatePins rate={rate} unit="output" />
    </>
  );
}

type FieldId = string;

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

export function SchemaFieldTree({
  detail,
  selected,
  onSelect,
}: {
  detail: MalformedOutputDetail;
  selected: FieldId;
  onSelect: (field: FieldId) => void;
}) {
  return (
    <div>
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

export type TraceLinker = (traceId: string, spanId?: string | null) => string;

const PAGE = 20;

function fieldLabel(detail: MalformedOutputDetail, field: FieldId): string {
  if (field === NOT_JSON) return "an output that was not JSON at all";
  if (field === OTHER) return "an output with an untracked violation";
  const f = detail.fields.find((x) => x.path === field);
  return f ? f.name : field;
}

/** Paged: a widely used field can fail on thousands of outputs. */
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

/** One block shared with CasePage.tsx so the two pages never disagree about which field broke. */
export function HowOutputsBroke({
  findingId,
  detail,
  linkToTrace,
}: {
  findingId: string;
  detail: MalformedOutputDetail;
  linkToTrace: TraceLinker;
}) {
  const initial: FieldId =
    detail.fields.find((f) => f.failing > 0)?.path ??
    (detail.notJson > 0 ? NOT_JSON : detail.other > 0 ? OTHER : (detail.fields[0]?.path ?? NOT_JSON));
  const [selected, setSelected] = useState<FieldId>(initial);

  return (
    <div className="grid gap-5 items-start" style={{ gridTemplateColumns: "repeat(2, minmax(0, 1fr))" }}>
      <SchemaFieldTree detail={detail} selected={selected} onSelect={setSelected} />
      <FailingOutput findingId={findingId} field={selected} detail={detail} linkToTrace={linkToTrace} />
    </div>
  );
}
