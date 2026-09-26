// SPDX-License-Identifier: Apache-2.0
/*
 * The secret-leak story: the timeline and its three pins. The bugs worth catching: a leak that is still
 * happening read as history (or the reverse), the wrong leak named as the latest, a dot drawn on a lane
 * that is not its key, and the unredacted count wrong or said when there is nothing to say.
 */
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { components } from "../../api/generated/schema";
import { LeakPins, LeakTimeline } from "./secretStory";

type Detail = components["schemas"]["SecretLeakDetail"];
type Leak = components["schemas"]["SecretLeakLeakView"];

const NOW = new Date("2026-09-25T12:00:00Z");
const ago = (ms: number) => new Date(NOW.getTime() - ms).toISOString();
const MIN = 60_000;
const HOUR = 60 * MIN;

const leak = (over: Partial<Leak>): Leak => ({
  at: ago(2 * HOUR),
  masked: "sk-a…9f2c",
  spanId: "span-1",
  stored: "redacted",
  traceId: "trace-0123456789",
  ...over,
});
const detail = (over: Partial<Detail> = {}): Detail => ({
  basis: "Any high-confidence match opens a case.",
  confidence: "high",
  firstAt: ago(3 * 24 * HOUR),
  lastAt: ago(14 * MIN),
  keys: [
    { lastAt: ago(14 * MIN), leaks: 2, masked: "sk-a…9f2c", storedRaw: true, traces: 2 },
    { lastAt: ago(3 * 24 * HOUR), leaks: 1, masked: "ghp_…1a2b", storedRaw: false, traces: 1 },
  ],
  leakCount: 3,
  leaks: [
    leak({ at: ago(14 * MIN), stored: "raw", traceId: "trace-latest-01", spanId: "span-9" }),
    leak({ at: ago(3 * 24 * HOUR), masked: "ghp_…1a2b" }),
    leak({ at: ago(2 * 24 * HOUR) }),
  ],
  rule: "openai_api_key",
  threshold: 1,
  traceCount: 3,
  windowEnd: ago(0),
  windowSeconds: 86400,
  windowStart: ago(24 * HOUR),
  ...over,
});
const link = (traceId: string, spanId?: string | null) => `/traces/${traceId}${spanId ? `#${spanId}` : ""}`;
const pins = (d: Detail) => render(<LeakPins secretLeak={d} linkToTrace={link} />);

beforeEach(() => {
  vi.useFakeTimers({ now: NOW, toFake: ["Date"] });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("LeakPins", () => {
  it("names the latest leak, links its span, and says it keeps the finding open", () => {
    pins(detail());

    expect(screen.getByText("Still leaking. The latest was 14 minutes ago")).toBeTruthy();
    expect(screen.getByRole("link", { name: "trace-la…" }).getAttribute("href")).toBe("/traces/trace-latest-01#span-9");
    expect(screen.getByText(/This is the leak that keeps the finding open/)).toBeTruthy();
    expect(screen.getByText("1 of the 3 are stored unredacted")).toBeTruthy();
    expect(screen.getByText(/That copy sits in trace storage until retention removes it/)).toBeTruthy();
  });

  it.each([
    [30_000, "moments ago"],
    [-5 * MIN, "moments ago"],
    [MIN, "1 minute ago"],
    [HOUR, "1 hour ago"],
    [3 * HOUR, "3 hours ago"],
    [23 * HOUR + 50 * MIN, "1 day ago"],
  ])("spells out a leak %i ms old as %s", (age, words) => {
    pins(detail({ lastAt: ago(age), leaks: [leak({ at: ago(age) })] }));

    expect(screen.getByText(`Still leaking. The latest was ${words}`)).toBeTruthy();
  });

  it("reads a leak older than a day as history, and says nothing of storage when every copy is redacted", () => {
    pins(detail({ lastAt: ago(2 * 24 * HOUR), leaks: [leak({ at: ago(2 * 24 * HOUR), traceId: null as never })] }));

    expect(screen.getByText(/^Last leaked /)).toBeTruthy();
    expect(screen.getByText(/older leaks may have aged out/)).toBeTruthy();
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.queryByText(/stored unredacted/)).toBeNull();
  });

  it("says when neither the leaks nor the start are on record", () => {
    pins(detail({ lastAt: null, firstAt: null, leaks: [] }));

    expect(screen.getByText("No leak on record")).toBeTruthy();
    expect(screen.getByText("The spans behind this finding have aged out of retention.")).toBeTruthy();
    expect(screen.getByText("The start of this leak is not on record")).toBeTruthy();
  });

  it("counts every unredacted copy, in the singular when there is one leak", () => {
    pins(detail({ leakCount: 2, leaks: [leak({ stored: "raw" }), leak({ stored: "raw", at: ago(3 * HOUR) })] }));
    expect(screen.getByText("2 of the 2 are stored unredacted")).toBeTruthy();
    expect(screen.getByText(/Those copies sit in trace storage until retention removes them/)).toBeTruthy();
    cleanup();

    pins(detail({ leakCount: 1, leaks: [leak({ stored: "raw" })] }));
    expect(screen.getByText("1 of the 1 is stored unredacted")).toBeTruthy();
  });
});

describe("LeakTimeline", () => {
  const dots = (container: HTMLElement) => Array.from(container.querySelectorAll("circle[cx]"));

  it("draws a dot per recorded leak on its own key's lane, coloured by how it was stored", () => {
    const { container } = render(
      <LeakTimeline
        secretLeak={detail({
          leaks: [
            leak({ at: ago(14 * MIN), stored: "raw" }),
            leak({ at: ago(3 * HOUR), masked: "ghp_…1a2b", stored: "unknown" }),
            leak({ at: ago(5 * HOUR), stored: "redacted" }),
            leak({ at: null as never }),
            leak({ masked: "not-a-lane" }),
          ],
        })}
      />,
    );

    const drawn = dots(container).filter((c) => c.getAttribute("r") === "5");
    expect(drawn.map((c) => c.getAttribute("fill"))).toEqual([
      "var(--color-error)",
      "var(--color-subtle)",
      "var(--color-fg-secondary)",
    ]);
    expect(drawn[0].getAttribute("cy")).not.toBe(drawn[1].getAttribute("cy"));
    expect(screen.getByText("sk-a…9f2c")).toBeTruthy();
    expect(screen.getByText("ghp_…1a2b")).toBeTruthy();
  });
});
