// SPDX-License-Identifier: Apache-2.0
/*
 * The answers a groundedness finding cites, and one of them at a time as the model was sent it.
 *
 * <h2>The list, then the answer</h2>
 * The left column is the evidence: each flagged answer with its score and its highest-scoring sentence,
 * newest first. Selecting one draws the question, the answer with every flagged sentence marked, and the
 * documents the answer was checked against. A mark is a flag, not proof: about a third of the sentences the
 * model marks are supported after all, so the documents sit right under the answer for the reader to check.
 *
 * <h2>Marks come from the offsets</h2>
 * The model returns each sentence's start and end in the exact string it scored, and the answer here is
 * that string. So a mark is a slice at those offsets and nothing else: re-splitting the text into sentences
 * here could disagree with the model's own split and mark a sentence it never flagged.
 *
 * <h2>Not FrustratedConversations</h2>
 * The paging, the filter and the frame are the same as the frustrated sessions list, and the pane is not:
 * that one reads the session's traces, this one draws the flagged-answer payload the finding carries, which
 * is what was scored rather than what the trace page would show.
 */
import { useEffect, useId, useState } from "react";
import { Link } from "react-router-dom";
import { FileText } from "lucide-react";
import { infiniteQueryOptions, keepPreviousData, useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import type { ProjectApi } from "../../api/client";
import type { FlaggedAnswer, FlaggedAnswerPage } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, Skeleton, cn } from "../../ui";
import { dateTime } from "./groundedness";
import { useListPaging } from "./useListPaging";
import { useSkeletonFlag } from "./useSkeletonFlag";

/** Answers read per page past the first. */
const PAGE_SIZE = 50;

/** The list and the answer side by side, at a fixed height so the page never moves as they load. */
const FRAME = "grid rounded-card border border-border overflow-hidden bg-surface";
const FRAME_STYLE = { gridTemplateColumns: "320px minmax(0, 1fr)", height: 720 };

const SECTION_LABEL = "font-mono text-label uppercase text-subtle";

/** One RCA cause's share of the answers: the report that found it and its 0-based position there. */
export type AnswerFilter = { rcaReport: string; index: number };

function answersQuery(api: ProjectApi, findingId: string, filter: AnswerFilter | undefined) {
  return infiniteQueryOptions({
    queryKey: ["flagged-answers", api.base, findingId, filter ? `${filter.rcaReport}:${filter.index}` : "all"],
    queryFn: ({ pageParam }: { pageParam: string | null }) =>
      api.getFlaggedAnswers(findingId, { limit: PAGE_SIZE, cursor: pageParam, cause: filter }),
    initialPageParam: null as string | null,
    getNextPageParam: (last: FlaggedAnswerPage) => last.nextCursor ?? undefined,
    staleTime: Infinity,
  });
}

/** The highest-scoring flagged sentence's text, cut from the answer at its offsets. */
function strongestSentence(row: FlaggedAnswer): string | null {
  if (!row.answer || row.flaggedSentences.length === 0) return null;
  const top = row.flaggedSentences.reduce((a, b) => (b.score > a.score ? b : a));
  const text = row.answer.slice(top.start, top.end).trim();
  return text || null;
}

export function FlaggedAnswers({
  findingId,
  first,
  traces,
  basePath,
  filter,
  readAhead = [],
}: {
  findingId: string;
  /** The page the finding came with: its first answers and where the next page starts. */
  first: { rows: FlaggedAnswer[]; nextCursor: string | null };
  /** How many flagged traces the list covers: the finding's, or the filtered cause's. */
  traces: number;
  basePath: string;
  /** Narrows the list to one cause; read from the server, since its answers may be past the first page. */
  filter?: AnswerFilter;
  /** The other filters the reader can switch to, whose first pages are read ahead. */
  readAhead?: AnswerFilter[];
}) {
  const { api } = useTenant();
  const filterKey = filter ? `${filter.rcaReport}:${filter.index}` : "all";
  const pages = useInfiniteQuery({
    ...answersQuery(api, findingId, filter),
    initialData: filter
      ? undefined
      : { pages: [{ rows: first.rows, nextCursor: first.nextCursor, total: first.rows.length }], pageParams: [null] },
    placeholderData: keepPreviousData,
  });
  const skeleton = useSkeletonFlag(pages.isPlaceholderData);

  const queryClient = useQueryClient();
  const readAheadKey = readAhead.map((f) => `${f.rcaReport}:${f.index}`).join(",");
  useEffect(() => {
    for (const f of readAhead) void queryClient.prefetchInfiniteQuery(answersQuery(api, findingId, f));
    // readAheadKey stands in for readAhead, which is a fresh array on every render.
  }, [queryClient, api, findingId, readAheadKey]);
  const answers = pages.data?.pages.flatMap((p) => p.rows) ?? [];

  const [picked, setPicked] = useState<{ filterKey: string; spanId: string } | null>(null);
  const pickedId = picked?.filterKey === filterKey ? picked.spanId : null;
  const selected = answers.find((a) => a.spanId === pickedId) ?? answers[0];

  // Read the next page when the end of the list scrolls into view.
  const { isFetchingNextPage, fetchNextPage } = pages;
  const { list, end, hasNextPage } = useListPaging(filterKey, pages);

  if (pages.isLoading || skeleton) {
    return <AnswersSkeleton />;
  }
  if (answers.length === 0) {
    return (
      <p className="text-subtle m-0 text-body" style={{ maxWidth: 560 }}>
        No flagged answers are stored for this finding. Their traces may have aged out.
      </p>
    );
  }

  // Counted in traces, as the rate is: a trace with two flagged answers is one flagged trace.
  const shown = new Set(answers.map((a) => a.traceId)).size;
  const noun = (n: number) => (n === 1 ? "trace" : "traces");

  return (
    <div className={FRAME} style={FRAME_STYLE}>
      <div className="flex flex-col min-h-0 border-r border-border">
        <ul ref={list} className="m-0 p-0 list-none overflow-y-auto flex-1" aria-label="Flagged answers">
          {answers.map((a) => {
            const on = a.spanId === selected?.spanId;
            const sentence = strongestSentence(a);
            const more = a.flaggedSentences.length - 1;
            return (
              <li key={`${a.traceId}:${a.spanId}`}>
                <button
                  type="button"
                  aria-pressed={on}
                  onClick={() => setPicked({ filterKey, spanId: a.spanId })}
                  className={cn(
                    "flex w-full flex-col gap-1 text-left cursor-pointer border-b border-border py-3 px-4 transition-colors",
                    on ? "bg-raised" : "bg-surface hover:bg-hover",
                  )}
                  style={{ transitionDuration: "var(--duration-micro)" }}
                >
                  <span className="flex justify-between font-mono text-small text-muted">
                    <span>{a.flaggedAt ? dateTime(a.flaggedAt) : "–"}</span>
                    <span className="tabular-nums">{a.score != null ? a.score.toFixed(2) : "–"}</span>
                  </span>
                  <span
                    className={cn("text-small wrap-anywhere", on ? "text-fg font-medium" : "text-fg-secondary")}
                    style={{ display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}
                  >
                    {sentence ?? <span className="font-mono text-muted">{a.traceId}</span>}
                  </span>
                  {sentence && more > 0 && <span className="text-small text-muted">and {more} more</span>}
                  {a.cleared && <span className="text-small text-muted">Cleared</span>}
                </button>
              </li>
            );
          })}
          {hasNextPage && (
            <li ref={end} className="py-2.5 px-4">
              <Button size="sm" variant="ghost" loading={isFetchingNextPage} onClick={() => void fetchNextPage()}>
                Load more answers
              </Button>
            </li>
          )}
        </ul>
        <p className="m-0 border-t border-border py-2.5 px-4 text-small text-muted">
          {hasNextPage && shown < traces
            ? `${shown.toLocaleString()} of ${traces.toLocaleString()} ${noun(traces)}`
            : `${shown.toLocaleString()} ${noun(shown)}`}
        </p>
      </div>
      {selected && <Answer key={`${selected.traceId}:${selected.spanId}`} row={selected} basePath={basePath} />}
    </div>
  );
}

/** The frame the answers will fill, drawn while a cause's first page loads. */
function AnswersSkeleton() {
  return (
    <div className={FRAME} style={FRAME_STYLE} role="status" aria-label="Loading answers">
      <div className="flex flex-col min-h-0 border-r border-border">
        <div className="flex-1 overflow-hidden">
          {[82, 64, 90, 70, 58, 76, 66, 84].map((width, i) => (
            <div key={i} className="border-b border-border py-3 px-4">
              <div className="flex justify-between">
                <Skeleton className="h-3 w-24" />
                <Skeleton className="h-3 w-8" />
              </div>
              <Skeleton className="mt-2 h-3.5" style={{ width: `${width}%` }} />
            </div>
          ))}
        </div>
        <div className="border-t border-border py-3 px-4">
          <Skeleton className="h-3 w-28" />
        </div>
      </div>
      <div className="flex flex-col min-w-0 min-h-0">
        <div className="border-b border-border py-3.5 px-5">
          <Skeleton className="h-3 w-40" />
        </div>
        <div className="flex flex-col gap-3 py-4.5 px-5">
          <Skeleton className="h-3 w-3/5" />
          <Skeleton className="mt-2 h-10 w-1/2" />
          <Skeleton className="h-3.5 w-5/6" />
          <Skeleton className="h-3.5 w-2/3" />
          <Skeleton className="mt-2 h-16 w-full" />
        </div>
      </div>
    </div>
  );
}

/**
 * The answer with each flagged sentence marked, sliced at the model's own offsets. Offsets are UTF-16
 * code units into this exact string. A range that runs past the end is cut at the end, and one that
 * overlaps the mark before it is left unmarked rather than drawn twice.
 */
export function MarkedAnswer({ answer, sentences }: { answer: string; sentences: FlaggedAnswer["flaggedSentences"] }) {
  const parts: React.ReactNode[] = [];
  let at = 0;
  for (const s of [...sentences].sort((a, b) => a.start - b.start)) {
    const start = Math.max(0, s.start);
    const stop = Math.min(answer.length, s.end);
    if (start < at || stop <= start) continue;
    if (start > at) parts.push(answer.slice(at, start));
    parts.push(
      <mark
        key={start}
        title={`Score ${s.score.toFixed(2)}`}
        className="text-fg"
        style={{
          background: "var(--color-error-subtle)",
          borderBottom: "1px solid var(--color-error)",
          borderRadius: 3,
          padding: "1px 2px",
        }}
      >
        {answer.slice(start, stop)}
      </mark>,
    );
    at = stop;
  }
  if (at < answer.length) parts.push(answer.slice(at));
  return (
    <p className="m-0 text-body text-fg-secondary whitespace-pre-wrap wrap-anywhere" style={{ lineHeight: 1.7 }}>
      {parts}
    </p>
  );
}

/** One flagged answer: the question, the answer with its marks, and what it was checked against. */
function Answer({ row, basePath }: { row: FlaggedAnswer; basePath: string }) {
  const answerLabel = useId();
  const marked = row.flaggedSentences.length;
  const documents = row.documents ?? [];
  return (
    <div className="flex flex-col min-w-0 min-h-0">
      <div className="flex items-center gap-2.5 border-b border-border py-3 px-5 text-small">
        <span className="font-mono text-muted truncate" title={row.traceId}>
          {row.traceId.slice(0, 10)}
        </span>
        {row.cleared && <span className="text-muted">· Cleared</span>}
        <span className="ml-auto flex shrink-0 items-center gap-3">
          <Link
            to={`${basePath}/traces/${encodeURIComponent(row.traceId)}#${encodeURIComponent(row.spanId)}`}
            className="text-link hover:text-link-hover transition-colors"
          >
            View trace
          </Link>
          {row.sessionId && (
            <Link
              to={`${basePath}/sessions/${encodeURIComponent(row.sessionId)}`}
              className="text-link hover:text-link-hover transition-colors"
            >
              View session
            </Link>
          )}
        </span>
      </div>
      <div className="flex-1 overflow-y-auto">
        <div className="flex flex-col gap-4 py-4.5 px-5">
          <p className="m-0 text-small text-muted">
            {row.cleared ? "Cleared" : "Flagged"} with a score of {row.score != null ? row.score.toFixed(2) : "–"}
            {row.flaggedAt ? ` on ${dateTime(row.flaggedAt)}` : ""}.
            {marked > 0 ? ` ${marked} ${marked === 1 ? "sentence" : "sentences"} marked.` : ""}
          </p>
          {!row.stored || row.answer == null ? (
            <p className="m-0 text-body text-subtle" style={{ maxWidth: 520 }}>
              This trace is no longer stored, so its answer can't be shown.
            </p>
          ) : (
            <>
              {row.question && (
                <div className="flex flex-col gap-1.5">
                  <div className={SECTION_LABEL}>Question</div>
                  <p
                    className="m-0 self-start rounded-card bg-raised py-2.5 px-3.5 text-body text-fg whitespace-pre-wrap wrap-anywhere"
                    style={{ maxWidth: 520 }}
                  >
                    {row.question}
                  </p>
                </div>
              )}
              <section className="flex flex-col gap-1.5" aria-labelledby={answerLabel}>
                <div id={answerLabel} className={SECTION_LABEL}>
                  Answer
                </div>
                <MarkedAnswer answer={row.answer} sentences={row.flaggedSentences} />
              </section>
              {documents.length > 0 &&
                (row.premiseHadEvidence ? (
                  <div className="flex flex-col gap-1.5">
                    <div className={SECTION_LABEL}>Retrieved documents · {documents.length}</div>
                    <div className="flex flex-col gap-2">
                      {documents.map((d, i) => (
                        <div key={i} className="rounded-control border border-border overflow-hidden">
                          <div className="flex items-center gap-2 bg-raised py-1.75 px-3 font-mono text-small text-fg-secondary">
                            <FileText size={13} aria-hidden="true" className="text-muted shrink-0" />
                            <span className="truncate" title={d.title ?? undefined}>
                              {d.title ?? `Document ${i + 1}`}
                            </span>
                          </div>
                          <p className="m-0 py-2.5 px-3 text-small text-fg-secondary whitespace-pre-wrap wrap-anywhere">{d.text}</p>
                        </div>
                      ))}
                    </div>
                  </div>
                ) : (
                  // Nothing was retrieved, so the model checked the answer against the prompt itself.
                  <div className="flex flex-col gap-1.5">
                    <div className={SECTION_LABEL}>Prompt</div>
                    <p className="m-0 rounded-control border border-border py-2.5 px-3 text-small text-fg-secondary whitespace-pre-wrap wrap-anywhere">
                      {documents.map((d) => d.text).join("\n\n")}
                    </p>
                  </div>
                ))}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
