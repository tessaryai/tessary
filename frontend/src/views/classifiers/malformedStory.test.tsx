// SPDX-License-Identifier: Apache-2.0
/*
 * "How outputs broke": the schema tree beside one failing output for the selected field. The bugs worth
 * catching: the failing outputs read for a field other than the one selected, stepping that skips or
 * repeats an output or stops at a page boundary, a position carried over from one field to the next,
 * and a missing document drawn as an empty one.
 */
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { MalformedOutputDetail, MalformedOutputRow } from "../../api/types";
import { renderRoute } from "../../test/render";
import { HowOutputsBroke } from "./malformedStory";

const api = vi.hoisted(() => ({ base: "/api/orgs/acme/projects/default", getMalformedOutputs: vi.fn() }));

vi.mock("../../tenant/TenantContext", () => ({ useTenant: () => ({ api }) }));

const DETAIL = {
  fields: [
    { depth: 0, failing: 0, name: "items", path: "items", required: true, type: "array" },
    { depth: 1, failing: 14, name: "sku", path: "items[].sku", required: true, type: "string" },
  ],
  notJson: 3,
  other: 2,
  rate: {},
} as unknown as MalformedOutputDetail;

const output = (n: number, over: Partial<MalformedOutputRow> = {}): MalformedOutputRow => ({
  document: `{\n  "n": ${n}\n}`,
  highlightLines: [2],
  message: `output ${n} is invalid`,
  name: "extract_order",
  spanId: `span-${n}`,
  startedAt: "2026-09-25T10:00:00Z",
  traceId: `trace-${n}-abcdef`,
  ...over,
});

beforeEach(() => {
  api.getMalformedOutputs.mockImplementation(async (_id: string, field: string, params?: { cursor?: string }) => {
    if (field !== "items[].sku") return { rows: [output(90, { name: null })], total: 1, nextCursor: null };
    return params?.cursor === "p2"
      ? { rows: [output(3)], total: 3, nextCursor: null }
      : { rows: [output(1), output(2)], total: 3, nextCursor: "p2" };
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const renderBlock = (detail = DETAIL) =>
  renderRoute(<HowOutputsBroke findingId="fnd-1" detail={detail} linkToTrace={(t, s) => `/traces/${t}#${s}`} />);
const next = () => fireEvent.click(screen.getByRole("button", { name: "Next output" }));
const prev = () => fireEvent.click(screen.getByRole("button", { name: "Previous output" }));
const position = () => screen.getByText(/ of \d+$/).textContent;

describe("HowOutputsBroke", () => {
  it("opens on the first failing field, with its offending line marked and its trace linked", async () => {
    renderBlock();

    expect(await screen.findByText("output 1 is invalid")).toBeTruthy();
    expect(api.getMalformedOutputs).toHaveBeenCalledWith("fnd-1", "items[].sku", { limit: 20, cursor: undefined });
    expect(screen.getByRole("button", { name: /^sku/ }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByText('"n": 1', { exact: false }).className).toBe("text-error");
    expect(screen.getByRole("link", { name: "trace-1-…" }).getAttribute("href")).toBe("/traces/trace-1-abcdef#span-1");
    expect(position()).toBe("1 of 3");
  });

  it("steps through every failing output, across the page boundary and back", async () => {
    renderBlock();
    await screen.findByText("output 1 is invalid");
    expect((screen.getByRole("button", { name: "Previous output" }) as HTMLButtonElement).disabled).toBe(true);

    next();
    expect(await screen.findByText("output 2 is invalid")).toBeTruthy();
    next();
    expect(await screen.findByText("output 3 is invalid")).toBeTruthy();
    expect(api.getMalformedOutputs).toHaveBeenLastCalledWith("fnd-1", "items[].sku", { limit: 20, cursor: "p2" });
    expect(position()).toBe("3 of 3");
    expect((screen.getByRole("button", { name: "Next output" }) as HTMLButtonElement).disabled).toBe(true);

    prev();
    expect(position()).toBe("2 of 3");
  });

  it("reads the outputs of the bucket selected, from the start, and names what it shows", async () => {
    renderBlock();
    await screen.findByText("output 1 is invalid");
    next();
    await screen.findByText("output 2 is invalid");

    fireEvent.click(screen.getByRole("button", { name: /Not JSON at all/ }));
    expect(await screen.findByText("output 90 is invalid")).toBeTruthy();
    expect(screen.getByText("an output that was not JSON at all")).toBeTruthy();
    expect(api.getMalformedOutputs).toHaveBeenLastCalledWith("fnd-1", "not_json", expect.anything());
    expect(position()).toBe("1 of 1");

    fireEvent.click(screen.getByRole("button", { name: /Other violations/ }));
    expect(await screen.findByText("an output with an untracked violation")).toBeTruthy();
    await waitFor(() => expect(api.getMalformedOutputs).toHaveBeenLastCalledWith("fnd-1", "other", expect.anything()));

    fireEvent.click(screen.getByText("items").closest("button")!);
    expect(await screen.findByText("items", { selector: ".font-mono.text-fg-secondary" })).toBeTruthy();
  });

  it("opens on the not-JSON bucket when no field fails, and says when a document was not recorded", async () => {
    api.getMalformedOutputs.mockResolvedValue({
      rows: [output(5, { document: null, message: null })],
      total: 1,
      nextCursor: null,
    });
    renderBlock({ ...DETAIL, fields: [DETAIL.fields[0]] });

    expect(await screen.findByText("This output was not recorded.")).toBeTruthy();
    expect(api.getMalformedOutputs).toHaveBeenCalledWith("fnd-1", "not_json", expect.anything());
  });

  it.each([
    [{ notJson: 0, other: 2 }, "other"],
    [{ notJson: 0, other: 0 }, "items"],
  ])("falls back through the buckets to the first field when nothing else failed: %o", async (counts, field) => {
    renderBlock({ ...DETAIL, fields: [DETAIL.fields[0]], ...counts });

    await waitFor(() => expect(api.getMalformedOutputs).toHaveBeenCalledWith("fnd-1", field, expect.anything()));
  });

  it("reads not-JSON when the schema declares no field at all", async () => {
    renderBlock({ ...DETAIL, fields: [], notJson: 0, other: 0 });

    await waitFor(() => expect(api.getMalformedOutputs).toHaveBeenCalledWith("fnd-1", "not_json", expect.anything()));
    expect(screen.queryByRole("button", { name: /Not JSON at all/ })).toBeNull();
  });

  it("says the outputs aged out, and names a failed read", async () => {
    api.getMalformedOutputs.mockResolvedValueOnce({ rows: [], total: 0, nextCursor: null });
    renderBlock();
    expect(await screen.findByText(/have aged out of retention/)).toBeTruthy();
    expect(screen.getByText("0 of 0")).toBeTruthy();
    cleanup();

    api.getMalformedOutputs.mockRejectedValueOnce(new Error("outputs unavailable"));
    renderBlock();
    expect(await screen.findByText("outputs unavailable")).toBeTruthy();
  });
});
