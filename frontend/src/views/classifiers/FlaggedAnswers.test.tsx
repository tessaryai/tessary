// SPDX-License-Identifier: Apache-2.0
/*
 * FlaggedAnswers: each flagged sentence is marked at the model's own offsets and nowhere else, the list
 * row shows the strongest sentence and how many more, an aged-out or cleared answer says so, and the list
 * reads its next page and one cause's answers from the server.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
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

/** The drawn answer: its text as one paragraph, and the marks in it. */
function drawnAnswer() {
  const section = screen.getByRole("region", { name: "Answer" });
  return {
    text: within(section).getByRole("paragraph").textContent,
    marks: within(section).queryAllByRole("mark"),
  };
}

describe("FlaggedAnswers", () => {
  it("marks exactly the offset slices, one mark per flagged sentence", () => {
    renderList([answer()], 58);

    const { text, marks } = drawnAnswer();
    expect(marks.map((m) => m.textContent)).toEqual([
      "Refunds are available for up to 60 days after purchase.",
      "After 60 days, you can still get half of your payment back.",
    ]);
    expect(marks.map((m) => m.title)).toEqual(["Score 0.99", "Score 0.98"]);
    // The unmarked text around the marks is kept as it was, never re-split.
    expect(text).toBe(ANSWER);
    // Local time, so the expected stamp is built the same way.
    const on = dateTime("2026-09-23T14:41:00Z");
    expect(screen.getByText(`Flagged with a score of 0.99 on ${on}. 2 sentences marked.`)).toBeTruthy();
  });

  it("marks what the offsets say, even mid-word", () => {
    renderList([answer({ answer: "abcdef", flaggedSentences: [{ start: 1, end: 3, score: 0.98 }] })]);
    const { text, marks } = drawnAnswer();
    expect(marks.map((m) => m.textContent)).toEqual(["bc"]);
    expect(text).toBe("abcdef");
  });

  it("draws overlapping ranges once, as the first mark", () => {
    renderList([
      answer({
        answer: "abcdef",
        flaggedSentences: [
          { start: 2, end: 6, score: 0.98 },
          { start: 0, end: 4, score: 0.99 },
        ],
      }),
    ]);
    const { text, marks } = drawnAnswer();
    expect(marks.map((m) => m.textContent)).toEqual(["abcd"]);
    expect(text).toBe("abcdef");
  });

  it("cuts a range that runs past the end at the end, and draws none for one that starts past it", () => {
    renderList([answer({ answer: "abcdef", flaggedSentences: [{ start: 3, end: 99, score: 0.98 }] })]);
    let drawn = drawnAnswer();
    expect(drawn.marks.map((m) => m.textContent)).toEqual(["def"]);
    expect(drawn.text).toBe("abcdef");
    cleanup();

    renderList([
      answer({
        answer: "abcdef",
        flaggedSentences: [
          { start: 0, end: 2, score: 0.98 },
          { start: 10, end: 20, score: 0.99 },
        ],
      }),
    ]);
    drawn = drawnAnswer();
    expect(drawn.marks.map((m) => m.textContent)).toEqual(["ab"]);
    expect(drawn.text).toBe("abcdef");
  });

  it("shows the strongest sentence on the row and how many more were flagged", () => {
    // The strongest sentence is listed second, so the row has to pick it by score.
    renderList(
      [
        answer({
          flaggedSentences: [
            { ...span("After 60 days, you can still get half of your payment back."), score: 0.98 },
            { ...span("Refunds are available for up to 60 days after purchase."), score: 0.99 },
          ],
        }),
      ],
      58,
      "50",
    );

    const row = screen.getByRole("button", { pressed: true });
    expect(row.textContent).toContain("Refunds are available for up to 60 days after purchase.");
    expect(row.textContent).not.toContain("After 60 days");
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

  it("draws the prompt, not retrieved documents, when the answer was checked against the prompt", () => {
    renderList([answer({ premiseHadEvidence: false })]);

    expect(screen.getByText("Prompt")).toBeTruthy();
    expect(
      screen.getByText(
        "Refunds are available within 30 days of purchase. Refunds go back to the original payment method.",
      ),
    ).toBeTruthy();
    expect(screen.queryByText(/Retrieved documents/)).toBeNull();
    expect(screen.queryByText("refund-policy.md")).toBeNull();
  });

  it("says so when the trace aged out, and names its row by trace id", () => {
    // The shape the server sends once the payload ages out: the flag keeps its sentences, the text is gone.
    renderList([answer({ stored: false, answer: null, question: null, documents: null })]);

    const row = screen.getByRole("button", { pressed: true });
    expect(within(row).getByText("4f1c9a07e2b84d0f")).toBeTruthy();
    expect(row.textContent).not.toContain("more");
    expect(screen.getByText("This trace is no longer stored, so its answer can't be shown.")).toBeTruthy();
    expect(screen.getByText(/2 sentences marked\./)).toBeTruthy();
    expect(screen.queryByRole("region", { name: "Answer" })).toBeNull();
  });

  it("hides the answer of a trace marked not stored, whatever text the row carries", () => {
    renderList([answer({ stored: false })]);

    expect(screen.getByText("This trace is no longer stored, so its answer can't be shown.")).toBeTruthy();
    expect(screen.queryByRole("region", { name: "Answer" })).toBeNull();
    expect(screen.queryAllByRole("mark")).toHaveLength(0);
    expect(screen.queryByText("Can I get a refund after 30 days?")).toBeNull();
  });

  it("marks a cleared answer on its row and in its detail", () => {
    renderList([answer({ cleared: true })]);

    const row = screen.getByRole("button", { pressed: true });
    expect(within(row).getByText("Cleared")).toBeTruthy();
    expect(screen.getByText("· Cleared")).toBeTruthy();
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
