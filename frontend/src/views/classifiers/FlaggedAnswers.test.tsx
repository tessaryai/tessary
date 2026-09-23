// SPDX-License-Identifier: Apache-2.0
/*
 * FlaggedAnswers: each flagged sentence is marked at the model's own offsets and nowhere else, the list
 * row shows the strongest sentence and how many more, an aged-out or cleared answer says so, and the list
 * reads its next page and one cause's answers from the server.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { FlaggedAnswer, FlaggedAnswerPage } from "../../api/types";
import { FlaggedAnswers } from "./FlaggedAnswers";
import { dateTime } from "./groundedness";

const getFlaggedAnswers =
  vi.fn<
    (id: string, params?: { cursor?: string | null; cause?: { rcaReport: string; index: number } }) => Promise<FlaggedAnswerPage>
  >();

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({
      orgSlug: "acme",
      projectSlug: "default",
      api: { base: "/api/orgs/acme/projects/default", getFlaggedAnswers },
    }),
  };
});

afterEach(() => {
  cleanup();
  getFlaggedAnswers.mockReset();
});

const ANSWER =
  "Yes. Refunds are available for up to 60 days after purchase. " +
  "After 60 days, you can still get half of your payment back. Refunds go back to your original payment method.";

function span(text: string): { start: number; end: number } {
  const start = ANSWER.indexOf(text);
  return { start, end: start + text.length };
}

function answer(over: Partial<FlaggedAnswer> = {}): FlaggedAnswer {
  return {
    traceId: "4f1c9a07e2b84d0f",
    spanId: "span-1",
    sessionId: "sess-1",
    flaggedAt: "2026-09-23T14:41:00Z",
    score: 0.99,
    question: "Can I get a refund after 30 days?",
    answer: ANSWER,
    flaggedSentences: [
      { ...span("Refunds are available for up to 60 days after purchase."), score: 0.99 },
      { ...span("After 60 days, you can still get half of your payment back."), score: 0.98 },
    ],
    documents: [
      { title: "refund-policy.md", text: "Refunds are available within 30 days of purchase." },
      { title: null, text: "Refunds go back to the original payment method." },
    ],
    premiseHadEvidence: true,
    stored: true,
    cleared: false,
    ...over,
  };
}

function renderList(
  rows: FlaggedAnswer[],
  traces = rows.length,
  nextCursor: string | null = null,
  filter?: { rcaReport: string; index: number },
) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <FlaggedAnswers
          findingId="f-1"
          first={{ rows, nextCursor }}
          traces={traces}
          filter={filter}
          basePath="/orgs/acme/projects/default"
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("FlaggedAnswers", () => {
  it("marks exactly the offset slices, one mark per flagged sentence", () => {
    renderList([answer()], 58);

    const marks = Array.from(document.querySelectorAll("mark"));
    expect(marks.map((m) => m.textContent)).toEqual([
      "Refunds are available for up to 60 days after purchase.",
      "After 60 days, you can still get half of your payment back.",
    ]);
    expect(marks.map((m) => m.title)).toEqual(["Score 0.99", "Score 0.98"]);
    // The unmarked text around the marks is kept as it was, never re-split.
    expect(marks[0].parentElement?.textContent).toBe(ANSWER);
    // Local time, so the expected stamp is built the same way.
    const on = dateTime("2026-09-23T14:41:00Z");
    expect(screen.getByText(`Flagged with a score of 0.99 on ${on}. 2 sentences marked.`)).toBeTruthy();
  });

  it("marks what the offsets say, even mid-word", () => {
    renderList([answer({ answer: "abcdef", flaggedSentences: [{ start: 1, end: 3, score: 0.98 }] })]);
    const marks = Array.from(document.querySelectorAll("mark"));
    expect(marks.map((m) => m.textContent)).toEqual(["bc"]);
    expect(marks[0].parentElement?.textContent).toBe("abcdef");
  });

  it("shows the strongest sentence on the row and how many more were flagged", () => {
    renderList([answer()], 58, "50");

    const row = screen.getByRole("button", { pressed: true });
    expect(row.textContent).toContain("Refunds are available for up to 60 days after purchase.");
    expect(row.textContent).toContain("and 1 more");
    expect(screen.getByText("1 of 58 traces")).toBeTruthy();
  });

  it("draws the question and the retrieved documents, naming an untitled one by its place", () => {
    renderList([answer()]);

    expect(screen.getByText("Can I get a refund after 30 days?")).toBeTruthy();
    expect(screen.getByText("Retrieved documents · 2")).toBeTruthy();
    expect(screen.getByText("refund-policy.md")).toBeTruthy();
    expect(screen.getByText("Document 2")).toBeTruthy();
  });

  it("says so when the trace is no longer stored", () => {
    renderList([answer({ stored: false, answer: null, question: null, documents: null })]);

    expect(screen.getByText("This trace is no longer stored, so its answer can't be shown.")).toBeTruthy();
    expect(document.querySelectorAll("mark")).toHaveLength(0);
  });

  it("marks a cleared answer", () => {
    renderList([answer({ cleared: true })]);

    expect(screen.getAllByText(/Cleared/).length).toBeGreaterThan(1);
    expect(screen.getByText(/Cleared with a score of 0.99/)).toBeTruthy();
  });

  it("says so when no flagged answers are stored", () => {
    renderList([]);

    expect(screen.getByText("No flagged answers are stored for this finding. Their traces may have aged out."))
      .toBeTruthy();
  });

  it("reads the next page of answers and adds it below the first", async () => {
    getFlaggedAnswers.mockResolvedValue({
      rows: [
        answer({
          traceId: "t-9",
          spanId: "span-9",
          answer: "Nonprofits get 50% off every plan.",
          flaggedSentences: [{ start: 0, end: 34, score: 0.99 }],
        }),
      ],
      total: 2,
      nextCursor: null,
    });
    renderList([answer()], 2, "50");
    expect(screen.getByText("1 of 2 traces")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /Load more answers/ }));

    await screen.findByRole("button", { name: /Nonprofits get 50% off every plan/ });
    expect(getFlaggedAnswers).toHaveBeenCalledWith("f-1", expect.objectContaining({ cursor: "50", limit: 50 }));
    expect(screen.getByText("2 traces")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Load more answers/ })).toBeNull();
  });

  it("reads one cause's answers from the server rather than the first page", async () => {
    getFlaggedAnswers.mockResolvedValue({
      rows: [
        answer({
          traceId: "t-7",
          spanId: "span-7",
          answer: "The API allows 1,000 requests per minute per key.",
          flaggedSentences: [{ start: 0, end: 49, score: 0.99 }],
        }),
      ],
      total: 1,
      nextCursor: null,
    });
    renderList([answer()], 1, "50", { rcaReport: "rca-1", index: 1 });

    await screen.findByRole("button", { name: /The API allows 1,000 requests/ });
    expect(getFlaggedAnswers).toHaveBeenCalledWith(
      "f-1",
      expect.objectContaining({ cursor: null, cause: { rcaReport: "rca-1", index: 1 } }),
    );
    expect(screen.queryByRole("button", { name: /Refunds are available/ })).toBeNull();
  });
});
