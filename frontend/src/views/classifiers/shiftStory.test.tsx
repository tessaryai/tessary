// SPDX-License-Identifier: Apache-2.0
/*
 * A distribution-shift finding, told the way the page tells it: the measure in the unit it is really
 * in, the two observations worth pinning (the median that fired, and the tail or a suspiciously flat
 * reference window), direction as the only colour, and the decomposition that names the row that
 * moved the money rather than the one with the biggest percentage. Expected strings are worked by hand
 * from the rules in the source comments.
 */
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import type { components } from "../../api/generated/schema";
import { PairBlock, PatternBlock, changeOf, compact, prettyKey } from "./findingCharts";
import { ShiftBehind, ShiftChart, ShiftPins, formatMeasure, measureNoun, pinsFor, referenceWords } from "./shiftStory";

type Shift = components["schemas"]["ShiftDetail"];
type Pair = components["schemas"]["Pair"];

afterEach(cleanup);

const q = (p50: [number | null, number | null], p95?: [number | null, number | null]): Pair[] => [
  { key: "p50", then: p50[0], now: p50[1] },
  ...(p95 ? [{ key: "p95", then: p95[0], now: p95[1] }] : []),
];

function shift(over: Partial<Shift>): Shift {
  return {
    bucketKey: "cost|checkout",
    bucketKind: "call_site",
    nCur: 400,
    nRef: 1_200,
    sinceVersionId: null,
    windowClosedAt: null,
    windowKind: null,
    windowOpenedAt: null,
    w1Log: 0.4,
    control: null,
    direction: "up",
    explains: [],
    floor: 0,
    measure: "cost",
    quantiles: q([1, 2.5], [10, 40]),
    ratio: 2.5,
    reference: "previous",
    tokens: [],
    workload: [],
    ...over,
  };
}

describe("formatMeasure", () => {
  it.each([
    ["cost", 0.59, "$0.59"],
    ["cost", 18.11, "$18.11"],
    ["cost", 150, "$150"],
    ["cost", 0.0123, "$0.012"],
    ["duration", 4_502_356, "75min"],
    ["duration", 90_000, "1.5min"],
    ["duration", 12_345, "12s"],
    ["duration", 1_500, "1.5s"],
    ["duration", 1_000, "1s"],
    ["tool_duration", 999.6, "1000ms"],
    ["duration", null, "–"],
  ])("%s %s -> %s", (measure, v, text) => {
    expect(formatMeasure(measure as string, v as number | null)).toBe(text);
  });

  it("names the reference window and the measure in words, never the wire value", () => {
    expect(referenceWords("previous")).toBe("its recent normal");
    expect(referenceWords("pinned")).toBe("the pinned baseline");
    expect(measureNoun("cost")).toBe("Cost per turn");
    expect(measureNoun("tool_duration")).toBe("Time per call");
    expect(measureNoun("duration")).toBe("Time per turn");
    expect(measureNoun("queue_wait_time")).toBe("queue wait time");
  });
});

describe("pinsFor", () => {
  const labels = (s: Shift) => pinsFor(s).map((p) => p.label);

  it("pins the median that fired and says whether the tail moved with it", () => {
    expect(pinsFor(shift({}))).toEqual([
      { label: "A typical turn is 2.5× more", detail: "$1.00 to $2.50 at the median. This is the number that fired the finding.", at: "after-p50" },
      { label: "The tail moved with the middle", detail: "$10.00 to $40.00 at p95, against +150% at the median.", at: "after-p95" },
    ]);
  });

  it("calls out a tail that moved twice as far as the middle, or less than it", () => {
    expect(labels(shift({ measure: "duration", quantiles: q([100, 120], [1_000, 2_000]) }))).toEqual([
      "A typical call is 20% slower",
      "The tail moved 2.0× more. That's the finding",
    ]);
    expect(labels(shift({ measure: "duration", quantiles: q([100, 200], [1_000, 1_100]) }))).toEqual([
      "A typical call is 2.0× more",
      "The expensive tail moved less than the middle",
    ]);
  });

  it("reads a reference window whose median and tail nearly coincide as something hitting a limit", () => {
    const pins = pinsFor(shift({ measure: "tool_duration", quantiles: q([30_000, 2_000], [31_000, 2_500]) }));
    expect(pins[0]).toEqual({
      label: "Before, every call took almost exactly the same time",
      detail: "Median 30s and p95 31s, a 3% spread across the whole window. Work doesn't distribute like that on its own. Something was hitting a limit.",
      at: "before-p50",
    });
    expect(pins[1].label).toBe("A typical call is 15× faster");
  });

  it("words a fall as cheaper, faster, or less", () => {
    expect(labels(shift({ quantiles: q([1, 0.4]) }))).toEqual(["A typical turn is 2.5× cheaper"]);
    expect(labels(shift({ quantiles: q([1, 0.8]) }))).toEqual(["A typical turn is 20% less"]);
    expect(labels(shift({ measure: "duration", quantiles: q([100, 80]) }))).toEqual(["A typical call is 20% faster"]);
  });

  it("pins only the median without a full tail, and nothing without a median", () => {
    expect(labels(shift({ quantiles: q([1, 1.3], [10, null]) }))).toEqual(["A typical turn is 30% more"]);
    expect(pinsFor(shift({ quantiles: q([0, 2]) }))).toEqual([]);
    expect(pinsFor(shift({ quantiles: [] }))).toEqual([]);
  });
});

describe("ShiftChart", () => {
  it("states both windows in its label and colours the after window by direction only", () => {
    render(<ShiftChart shift={shift({ direction: "down", quantiles: q([1, 0.4], [3, 1]) })} />);
    const chart = screen.getByRole("img", { name: "Cost per turn: median $1.00 to $0.40, 95th percentile $3.00 to $1.00" });
    expect(within(chart).getByText("$0.40").getAttribute("fill")).toBe("var(--color-success)");
    cleanup();

    render(<ShiftChart shift={shift({})} />);
    expect(within(screen.getByRole("img")).getByText("$2.50").getAttribute("fill")).toBe("var(--color-error)");
    expect(within(screen.getByRole("img")).getAllByText("1").length).toBeGreaterThan(0);
  });

  it("labels a near-flat reference window once, as a range", () => {
    render(<ShiftChart shift={shift({ measure: "duration", quantiles: q([30_000, 2_000], [31_000, 2_500]) })} />);
    expect(within(screen.getByRole("img")).getByText("30s – 31s")).toBeTruthy();
  });

  it("draws without a tail, and not at all without a median", () => {
    render(<ShiftChart shift={shift({ quantiles: q([1, 2]) })} />);
    expect(screen.getByRole("img", { name: "Cost per turn: median $1.00 to $2.00, 95th percentile – to –" })).toBeTruthy();
    cleanup();
    const { container } = render(<ShiftChart shift={shift({ quantiles: q([null, 2]) })} />);
    expect(container.innerHTML).toBe("");
  });
});

describe("ShiftPins", () => {
  it("numbers each pin in chart order, and renders nothing with none", () => {
    render(<ShiftPins shift={shift({})} />);
    expect(screen.getByText("A typical turn is 2.5× more")).toBeTruthy();
    expect(screen.getByText("The tail moved with the middle")).toBeTruthy();
    expect(screen.getByText("2")).toBeTruthy();
    cleanup();
    const { container } = render(<ShiftPins shift={shift({ quantiles: [] })} />);
    expect(container.innerHTML).toBe("");
  });
});

describe("ShiftBehind", () => {
  const tokens: Pair[] = [
    { key: "tok_input_p50", then: 30, now: 91 },
    { key: "tok_cache_read_p50", then: 1_210_000, now: 2_390_000 },
    { key: "tok_output_p50", then: null, now: 12 },
  ];
  const workload: Pair[] = [{ key: "user_message_chars_p50", then: 200, now: 210 }];

  it("emphasises the row that moved the most in absolute terms, in the direction's colour", () => {
    render(<ShiftBehind shift={shift({ tokens, workload })} />);
    expect(screen.getByText("tokens, and what users asked for")).toBeTruthy();
    const cacheRow = screen.getByText("cache read p50").parentElement!;
    expect(within(cacheRow).getByText("+98%").className).toContain("text-error");
    expect(within(screen.getByText("input p50").parentElement!).getByText("+203%").className).toContain("text-subtle");
    expect(screen.getByText(/If these stay flat while the measure moves/)).toBeTruthy();
  });

  it("names what it has, and renders nothing when a tool call has neither", () => {
    render(<ShiftBehind shift={shift({ tokens, direction: "down" })} />);
    expect(screen.getByText("tokens")).toBeTruthy();
    expect(within(screen.getByText("cache read p50").parentElement!).getByText("+98%").className).toContain("text-success");
    cleanup();

    render(<ShiftBehind shift={shift({ workload })} />);
    expect(screen.getByText("what users asked for")).toBeTruthy();
    cleanup();

    const { container } = render(<ShiftBehind shift={shift({ tokens: [{ key: "x", then: null, now: null }] })} />);
    expect(container.innerHTML).toBe("");
  });
});

describe("paired readings", () => {
  it("says a reading appeared or stopped rather than dividing by zero", () => {
    expect(changeOf({ key: "k", then: 0, now: 5 })).toEqual({ kind: "appeared" });
    expect(changeOf({ key: "k", then: 4, now: null })).toEqual({ kind: "stopped" });
    expect(changeOf({ key: "k", then: 0, now: 0 })).toEqual({ kind: "unmeasured" });
    expect(changeOf({ key: "k", then: -4, now: -2 })).toEqual({ kind: "pct", value: 50 });

    render(<PairBlock title="Tokens" pairs={[{ key: "tok_cache_write", then: 0, now: 800 }, { key: "a", then: 3, now: 0 }, { key: "b", then: 0, now: 0 }, { key: "c", then: 10, now: 5 }]} />);
    expect(within(screen.getByText("cache write").parentElement!).getByText("new")).toBeTruthy();
    expect(within(screen.getByText("a").parentElement!).getByText("gone")).toBeTruthy();
    expect(within(screen.getByText("b").parentElement!).getAllByText("–")).toHaveLength(1);
    expect(within(screen.getByText("c").parentElement!).getByText("-50%")).toBeTruthy();
    cleanup();

    const { container } = render(<PairBlock title="Empty" pairs={[{ key: "x", then: null, now: null }]} />);
    expect(container.innerHTML).toBe("");
  });

  it("prints numbers at a precision a reader can hold", () => {
    expect(compact(null)).toBe("–");
    expect(compact(0)).toBe("0");
    expect(compact(0.000123)).toBe("0.00012");
    expect(compact(1.2345)).toBe("1.23");
    expect(compact(12_345.6)).toBe("12,346");
    expect(prettyKey("tok_input_p50")).toBe("input p50");
  });
});

describe("PatternBlock", () => {
  const pattern = (signature: string) => ({ signature, cur: 3, ref: 0, source: "status" });

  it("lists the window's failure signatures with the measured direction, and no invented before", () => {
    render(<PatternBlock patterns={[pattern("TimeoutError: upstream"), pattern("")]} truncated elevated />);
    expect(screen.getByText("Elevated tool errors.").className).toContain("text-error");
    expect(screen.getByText("TimeoutError: upstream")).toBeTruthy();
    expect(screen.getByText("(no signature)")).toBeTruthy();
    expect(screen.getByText(/Truncated: the ranked head/)).toBeTruthy();
    expect(screen.queryByText("0")).toBeNull();
    cleanup();

    render(<PatternBlock patterns={[pattern("x")]} truncated={false} elevated={false} />);
    expect(screen.getByText("Lower tool errors.")).toBeTruthy();
    expect(screen.queryByText(/Truncated/)).toBeNull();
    cleanup();

    const { container } = render(<PatternBlock patterns={[]} truncated={false} elevated />);
    expect(container.innerHTML).toBe("");
  });
});
