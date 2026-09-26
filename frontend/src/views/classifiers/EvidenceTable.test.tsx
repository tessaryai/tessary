// SPDX-License-Identifier: Apache-2.0
/*
 * A finding's evidence rows. The bugs worth catching: the role filter reading another role's rows or
 * miscounting them, a column shown over rows that never carry it, an unredacted secret or a violation
 * not called out, and a row linking to the wrong trace or session.
 */
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { EvidenceSpan } from "../../api/types";
import { pending, renderRoute } from "../../test/render";
import { EvidenceTable } from "./EvidenceTable";

const api = vi.hoisted(() => ({ base: "/api/orgs/acme/projects/default", getBehaviorFindingEvidence: vi.fn() }));

vi.mock("../../tenant/TenantContext", () => ({ useTenant: () => ({ api }) }));

const span = (over: Partial<EvidenceSpan>) =>
  ({
    role: "witness",
    name: "search_docs",
    status: "ok",
    level: null,
    errorType: null,
    secretKey: null,
    storedAs: null,
    violation: null,
    startedAt: null,
    latencyMs: null,
    totalTokens: null,
    totalCost: null,
    models: null,
    notRolledUp: false,
    partialCost: false,
    staleTotals: false,
    traceId: null,
    sessionId: null,
    spanId: null,
    ...over,
  }) as EvidenceSpan;
const page = (
  rows: EvidenceSpan[],
  nextCursor: string | null = null,
  recordedCounts: Record<string, number> = { witness: 3, member: 40, baseline: 0 },
) => ({
  rows,
  nextCursor,
  counts: recordedCounts,
  recordedCounts,
});

beforeEach(() => {
  api.getBehaviorFindingEvidence.mockImplementation(
    async (_id: string, params: { role?: string; cursor?: string }) => {
      if (params.role === "member") return page([span({ role: "member", name: "member-row" })]);
      return params.cursor === "c2"
        ? page([span({ name: "third", traceId: "trace-3333333333" })])
        : page(
            [
              span({
                name: null as never,
                status: "error",
                errorType: "Timeout",
                latencyMs: 1234,
                totalTokens: 5600,
                totalCost: 0.01234,
                models: ["gpt-5", "haiku"],
                notRolledUp: true,
                staleTotals: true,
                traceId: "trace-1111111111",
              }),
              span({ role: "custom", level: "ERROR", sessionId: "sess-2222222222", startedAt: "2026-09-25T10:00:00Z" }),
            ],
            "c2",
          );
    },
  );
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const renderTable = () => renderRoute(<EvidenceTable findingId="fnd-1" basePath="/orgs/acme/projects/default" />);
const headers = () => screen.getAllByRole("columnheader").map((h) => h.textContent);
const cells = (i: number) => within(screen.getAllByRole("row")[i + 1]).getAllByRole("cell").map((c) => c.textContent);

describe("EvidenceTable", () => {
  it("prints each row by column, dashing what a row does not carry, and hides columns no row has", async () => {
    renderTable();
    await screen.findByText("Timeout", { exact: false });

    expect(headers()).not.toContain("Key");
    expect(headers()).not.toContain("Violation");
    expect(cells(0)).toEqual([
      "Witness",
      "—",
      "error · Timeout",
      "—",
      "1,234",
      "5,600",
      "0.0123",
      "gpt-5, haiku",
      "not rolled up yet, stale totals",
      "trace-11…",
    ]);
    expect(screen.getByText("error · Timeout").className).toContain("text-error");
    expect(screen.getByRole("link", { name: "trace-11…" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/traces/trace-1111111111",
    );
    expect(cells(1)[0]).toBe("custom");
    expect(screen.getByText("ok").className).toContain("text-error");
    expect(screen.getByRole("link", { name: "conversation sess-222…" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/sessions/sess-2222222222",
    );
  });

  it("filters to one role, counting it alone, and back to all", async () => {
    renderTable();
    await screen.findByText("Timeout", { exact: false });
    expect(screen.getByText("2 of 43")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Baseline/ })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Member (40)" }));

    expect(await screen.findByText("member-row")).toBeTruthy();
    expect(api.getBehaviorFindingEvidence).toHaveBeenLastCalledWith("fnd-1", { role: "member", limit: 10, cursor: undefined });
    expect(screen.getByText("1 of 40")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Member (40)" }).getAttribute("title")).toMatch(/flagged population/);

    fireEvent.click(screen.getByRole("button", { name: "All (43)" }));
    expect(await screen.findByText("2 of 43")).toBeTruthy();
  });

  it("reads the next page on request, and stops offering once there is none", async () => {
    renderTable();
    await screen.findByText("Timeout", { exact: false });

    fireEvent.click(screen.getByRole("button", { name: "Load more" }));

    expect(await screen.findByText("third")).toBeTruthy();
    expect(api.getBehaviorFindingEvidence).toHaveBeenLastCalledWith("fnd-1", { role: undefined, limit: 10, cursor: "c2" });
    await waitFor(() => expect(screen.queryByRole("button", { name: /Load more|Loading/ })).toBeNull());
  });

  it("calls out a leaked key stored unredacted, and each schema violation", async () => {
    api.getBehaviorFindingEvidence.mockResolvedValue(
      page(
        [
          span({ secretKey: "sk-a…9f2c", storedAs: "raw", violation: "items[0].sku is required", partialCost: true }),
          span({ secretKey: null, storedAs: "redacted" }),
          span({ secretKey: "ghp_…", storedAs: "mystery" }),
          span({ storedAs: null, status: null as never }),
        ],
        null,
        { witness: 4 },
      ),
    );
    renderTable();

    const raw = await screen.findByText("Unredacted");
    expect(headers()).toEqual(expect.arrayContaining(["Key", "Stored as", "Violation"]));
    expect(raw.className).toContain("text-error");
    expect(screen.getByText("Redacted").className).toContain("text-muted");
    expect(screen.getByText("mystery")).toBeTruthy();
    expect(screen.getByText("items[0].sku is required").className).toContain("text-error");
    expect(screen.getByText("partial cost")).toBeTruthy();
    expect(cells(3)[3]).toBe("—");
  });

  it("says a finding with no rows cites nothing, and shows the loading and failed reads", async () => {
    api.getBehaviorFindingEvidence.mockResolvedValueOnce(page([], null, {}));
    renderTable();
    expect(await screen.findByText(/This finding cites no rows/)).toBeTruthy();
    cleanup();

    api.getBehaviorFindingEvidence.mockReturnValueOnce(pending());
    renderTable();
    expect(screen.queryByRole("table")).toBeNull();
    cleanup();

    api.getBehaviorFindingEvidence.mockRejectedValueOnce(new Error("evidence unavailable"));
    renderTable();
    expect(await screen.findByText("evidence unavailable")).toBeTruthy();
  });
});
