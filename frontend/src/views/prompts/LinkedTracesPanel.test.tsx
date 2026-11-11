// SPDX-License-Identifier: Apache-2.0
/*
 * The traces whose spans name this prompt version. The bugs worth catching are the read sent without the version
 * (every version's traces shown), a link to Traces that drops the filter, and a capped list that reads as complete.
 */
import { screen, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { api } from "../../test/api";
import { traceItem } from "../../test/fixtures";
import { pending, renderRoute } from "../../test/render";
import { LinkedTracesPanel } from "./LinkedTracesPanel";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const renderPanel = () => renderRoute(<LinkedTracesPanel name="support/greeting" version={2} />);

it("reads this version's traces and links each row and the Traces page", async () => {
  api.listTraces.mockResolvedValue({
    traces: [
      traceItem({ id: "tr-1", name: "checkout-agent", status: "ok", latency_ms: 2_000, total_cost: 0.0021 }),
      traceItem({ id: "tr-2", name: null, status: "error" }),
    ],
    next_cursor: "c-1",
  });
  renderPanel();

  expect((await screen.findByRole("link", { name: "checkout-agent" })).getAttribute("href")).toBe("/orgs/acme/projects/default/traces/tr-1");
  expect(api.listTraces).toHaveBeenCalledWith({ promptName: "support/greeting", promptVersion: "2", limit: 50, sort: "when" });
  expect(screen.getByRole("link", { name: "tr-2" }).getAttribute("href")).toBe("/orgs/acme/projects/default/traces/tr-2");
  expect(screen.getByText("ok")).toBeTruthy();
  expect(screen.getByText("error")).toBeTruthy();
  expect(screen.getByText("0.0021")).toBeTruthy();
  expect(screen.getByRole("link", { name: "Open in Traces" }).getAttribute("href")).toBe(
    "/orgs/acme/projects/default/traces?prompt=support%2Fgreeting&prompt_version=2",
  );
  expect(screen.getByText("Showing the first 50 traces. Open in Traces to see every one.")).toBeTruthy();
});

// Bug: in a narrow card the latency, cost and time broke onto two lines instead of the table scrolling sideways.
it("keeps the time, latency and cost on one line", async () => {
  api.listTraces.mockResolvedValue({
    traces: [traceItem({ id: "tr-1", name: "checkout-agent", latency_ms: 900, total_cost: 0.0021 })],
    next_cursor: null,
  });
  renderPanel();

  const row = (await screen.findByRole("link", { name: "checkout-agent" })).closest("tr")!;
  const [when, , , latency, cost] = within(row).getAllByRole("cell");
  expect([when, latency, cost].map((c) => c.classList.contains("whitespace-nowrap"))).toEqual([true, true, true]);
});

it("does not claim a cap when every trace is shown", async () => {
  api.listTraces.mockResolvedValue({ traces: [traceItem({})], next_cursor: null });
  renderPanel();

  await screen.findByRole("link", { name: "checkout-agent" });
  expect(screen.queryByText(/Showing the first 50 traces/)).toBeNull();
});

it("says when no trace names this version", async () => {
  api.listTraces.mockResolvedValue({ traces: [], next_cursor: null });
  renderPanel();

  expect(await screen.findByText("No traces for version 2 yet")).toBeTruthy();
  expect(screen.getByText("A trace appears here when one of its spans carries this prompt's name and version.")).toBeTruthy();
});

it("shows a skeleton while traces load", () => {
  api.listTraces.mockReturnValue(pending());
  renderPanel();

  expect(screen.getByRole("status", { name: "Loading table" })).toBeTruthy();
});

it("shows the error when traces fail to load", async () => {
  api.listTraces.mockRejectedValue(new Error("COMMON.INVALID_PARAMETER: promptVersion"));
  renderPanel();

  expect((await screen.findByRole("alert")).textContent).toContain("COMMON.INVALID_PARAMETER: promptVersion");
});
