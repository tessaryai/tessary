// SPDX-License-Identifier: Apache-2.0
/*
 * Calls per version from the spans that name the prompt. The bugs worth catching are a window control that does not
 * reach the read, usage joined to the wrong version, a version without calls missing from the table, unpriced calls
 * hidden inside a cost, versions spans name that the prompt lacks left unsaid, and an empty window that names the
 * wrong period.
 */
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { api } from "../../test/api";
import { promptDetail, versionUsage } from "../../test/fixtures";
import { pending, renderRoute } from "../../test/render";
import { UsagePanel } from "./UsagePanel";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const VERSIONS = promptDetail().versions;
const renderPanel = () => renderRoute(<UsagePanel promptId="p-1" versions={VERSIONS} />);
const row = (version: string) => screen.getByRole("cell", { name: version }).closest("tr")!;
const cells = (version: string) => within(row(version)).getAllByRole("cell").map((c) => c.textContent);

describe("the table", () => {
  // Bug: usage drawn on the wrong version, a version without calls missing, or rows out of order.
  it("draws one row per version, newest first, with a zero row for a version without calls", async () => {
    api.getPromptUsage.mockResolvedValue({
      days: 7,
      versions: [
        versionUsage({
          version: "2", calls: 1204, error_calls: 3, p50_ms: 820, p95_ms: 2400, input_tokens: 12_000, output_tokens: 3_400,
          cost_usd: 0.4321, first_seen: "2026-09-20T10:00:00Z", last_seen: "2026-09-21T10:00:00Z",
        }),
      ],
      unknown_versions: [],
    });
    renderPanel();

    await screen.findByRole("cell", { name: "v2" });
    expect(screen.getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell")[0].textContent)).toEqual(["v2", "v1"]);
    expect(cells("v2").slice(0, 8)).toEqual(["v2", "1,204", "3", "820 ms", "2.4 s", "12.0k", "3.40k", "0.4321"]);
    expect(cells("v1")).toEqual(["v1", "0", "—", "—", "—", "—", "—", "—", "—", "—"]);
    expect(screen.queryByText(/Spans also name versions/)).toBeNull();
  });

  // Bug: in a narrow card the table squeezed its columns, so "900 ms" broke onto two lines. A wide table scrolls inside
  // the Table primitive's own scroll box instead, which only works when no number or date wraps.
  it("keeps every number and date on one line", async () => {
    api.getPromptUsage.mockResolvedValue({
      days: 7, versions: [versionUsage({ version: "2", calls: 2, p50_ms: 900, p95_ms: 900 })], unknown_versions: [],
    });
    renderPanel();

    await screen.findByRole("cell", { name: "v2" });
    for (const version of ["v2", "v1"]) {
      const wraps = within(row(version)).getAllByRole("cell").slice(1).map((c) => c.classList.contains("whitespace-nowrap"));
      expect(wraps).toEqual(Array(9).fill(true));
    }
  });

  // Bug: a cost that leaves out unpriced calls reads as the full cost.
  it.each([
    [2, "2 calls unpriced"],
    [1, "1 call unpriced"],
  ])("says when %s calls are unpriced", async (unpriced, title) => {
    api.getPromptUsage.mockResolvedValue({
      days: 7, versions: [versionUsage({ calls: 5, cost_usd: 0.01, unpriced_calls: unpriced })], unknown_versions: [],
    });
    renderPanel();

    expect((await screen.findByText("0.0100")).getAttribute("title")).toBe(title);
  });

  // Bug: calls from spans that name a version this prompt does not have vanish without a word.
  it("names the versions spans sent that the prompt does not have", async () => {
    api.getPromptUsage.mockResolvedValue({
      days: 7, versions: [versionUsage({ version: "9", calls: 2 }), versionUsage({ version: "prod", calls: 1 })],
      unknown_versions: ["9", "prod"],
    });
    renderPanel();

    expect(await screen.findByText("Spans also name versions this prompt does not have: 9, prod.")).toBeTruthy();
  });
});

describe("calls the version list does not hold", () => {
  // Bug: spans that name no version, or a version the prompt lacks, count on the list page's Calls column but were
  // drawn nowhere here, so the two screens disagreed.
  it("draws a row for each version the prompt lacks and one for spans with no version", async () => {
    api.getPromptUsage.mockResolvedValue({
      days: 7,
      versions: [
        versionUsage({ version: "2", calls: 4 }),
        versionUsage({ version: "9", calls: 2, error_calls: 1 }),
        versionUsage({ version: "prod", calls: 1 }),
        versionUsage({ version: null, calls: 5 }),
      ],
      unknown_versions: ["9", "prod"],
    });
    renderPanel();

    await screen.findByRole("cell", { name: "No version" });
    expect(screen.getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell")[0].textContent)).toEqual(
      ["v2", "v1", "v9", "(prod)", "No version"],
    );
    expect(cells("v9").slice(1, 3)).toEqual(["2", "1"]);
    expect(cells("(prod)")[1]).toBe("1");
    expect(cells("No version")[1]).toBe("5");
  });
});

describe("the window", () => {
  // Bug: the window control changes the label but not the read.
  it("reads the chosen window", async () => {
    api.getPromptUsage.mockResolvedValue({ days: 7, versions: [], unknown_versions: [] });
    renderPanel();
    await waitFor(() => expect(api.getPromptUsage).toHaveBeenLastCalledWith("p-1", 7));

    fireEvent.click(screen.getByRole("button", { name: "30 days" }));
    await waitFor(() => expect(api.getPromptUsage).toHaveBeenLastCalledWith("p-1", 30));
    fireEvent.click(screen.getByRole("button", { name: "1 day" }));
    await waitFor(() => expect(api.getPromptUsage).toHaveBeenLastCalledWith("p-1", 1));
  });

  // Bug: an empty window names a period other than the one chosen.
  it("names the chosen window when it holds no calls", async () => {
    api.getPromptUsage.mockResolvedValue({ days: 7, versions: [], unknown_versions: [] });
    renderPanel();

    expect(await screen.findByText("No calls in the last 7 days")).toBeTruthy();
    expect(
      screen.getByText(
        "A span counts here when it carries tessary.prompt.name. Playground runs are not recorded.",
      ),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "1 day" }));
    expect(await screen.findByText("No calls in the last day")).toBeTruthy();
  });
});

it("shows a skeleton while usage loads", () => {
  api.getPromptUsage.mockReturnValue(pending());
  renderPanel();

  expect(screen.getByRole("status", { name: "Loading table" })).toBeTruthy();
});

it("shows the error when usage fails to load", async () => {
  api.getPromptUsage.mockRejectedValue(new Error("COMMON.INVALID_PARAMETER: days"));
  renderPanel();

  expect((await screen.findByRole("alert")).textContent).toContain("COMMON.INVALID_PARAMETER: days");
});
