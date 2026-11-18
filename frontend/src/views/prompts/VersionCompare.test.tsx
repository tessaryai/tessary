// SPDX-License-Identifier: Apache-2.0
/*
 * Comparing two versions. The bugs worth catching are removed and added text on the wrong side, an inserted chat
 * message that marks every later one as changed, a config change that does not name its key, a comparison of a
 * version with itself, identical versions drawn as a change, a picker that does not reach the read, a promotion
 * offered for a version that already carries production, and an oldest selected version with no partner.
 */
import { fireEvent, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PromptDetail, PromptVersionView } from "../../api/types";
import { api } from "../../test/api";
import { promptDetail, promptVersion, versionUsage } from "../../test/fixtures";
import { pending, renderRoute } from "../../test/render";
import { defaultPair, VersionCompare } from "./VersionCompare";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const TEXT = promptDetail({
  type: "text",
  versions: [3, 2, 1].map((v) => ({ ...promptDetail().versions[1], id: `pv-${v}`, version: v, labels: v === 2 ? ["production"] : [] })),
});

function serve(versions: Record<number, Partial<PromptVersionView>>, type: "text" | "chat" = "text") {
  api.getPromptVersion.mockImplementation((_id: string, v: number) =>
    Promise.resolve(promptVersion({ type, version: v, body: "same", config: {}, labels: [], ...versions[v] })),
  );
  api.getPromptUsage.mockResolvedValue({ days: 7, versions: [versionUsage({ version: "2", calls: 1204, error_calls: 10 })], unknown_versions: [] });
}

function renderCompare(from = 1, to = 2, prompt: PromptDetail = TEXT) {
  const onPick = vi.fn();
  renderRoute(
    <VersionCompare
      detail={prompt}
      versions={prompt.versions}
      from={from}
      to={to}
      onPick={onPick}
      backHref="/prompts/p-1"
      metricsHref="/prompts/p-1?section=metrics"
    />,
  );
  return onPick;
}

describe("the diff", () => {
  // Bug: removed text drawn on the right or added text on the left.
  it("marks removed text on the left and added text on the right, for the prompt and the config", async () => {
    serve({ 1: { body: "be kind now", config: { temperature: 0.2 } }, 2: { body: "be brief now", config: { temperature: 0.5 } } });
    renderCompare();

    expect((await screen.findByText("kind")).tagName).toBe("DEL");
    expect(screen.getByText("brief").tagName).toBe("INS");
    expect(screen.getByText("0.2").tagName).toBe("DEL");
    expect(screen.getByText("0.5").tagName).toBe("INS");
    expect(screen.getByRole("heading", { name: "Prompt" })).toBeTruthy();
    expect(screen.getByText("1 key changed")).toBeTruthy();
  });

  // Bug: a removed or added config key vanishes from one side, so the reader cannot tell it changed.
  it("shows each config key on its side: removed on the left, added on the right", async () => {
    serve({
      1: { config: { model: "m", gone: true } },
      2: { config: { model: "m", max_tokens: 200 } },
    });
    renderCompare();

    expect((await screen.findByText('"gone": true')).tagName).toBe("DEL");
    expect(screen.getByText('"max_tokens": 200').tagName).toBe("INS");
    expect(screen.getAllByText('"model": "m"')).toHaveLength(2);
    expect(screen.getByText("1 key added, 1 key removed")).toBeTruthy();
  });

  // Bug: messages compared by position, so one inserted message marks every later one as changed.
  it("compares a chat prompt message by message and counts the changes", async () => {
    const chat = promptDetail({ versions: TEXT.versions });
    serve(
      {
        1: { body: [{ role: "system", content: "Be kind." }, { type: "placeholder", name: "history" }] },
        2: { body: [{ role: "system", content: "Be kind." }, { role: "user", content: "Hi" }, { type: "placeholder", name: "history" }] },
      },
      "chat",
    );
    renderCompare(1, 2, chat);

    expect(await screen.findByText("1 message added, 2 unchanged. Removed text is marked on the left, added text on the right.")).toBeTruthy();
    expect(screen.getByText("Hi").tagName).toBe("INS");
    expect(screen.getAllByText("system · version 1")).toHaveLength(1);
  });

  // Bug: two identical versions drawn as a diff with nothing marked.
  it("says when the versions do not differ", async () => {
    serve({ 1: { body: "same" }, 2: { body: "same" } });
    renderCompare();

    expect(await screen.findByText("No differences between version 1 and version 2.")).toBeTruthy();
  });

  // Bug: a version compared with itself reads as "no differences".
  it("asks for two different versions", () => {
    serve({});
    renderCompare(2, 2);

    expect(screen.getByText("Pick two different versions.")).toBeTruthy();
  });

  // Bug: a changed picker does not reach the page, so the old pair stays.
  it("hands back the pair a picker selects", () => {
    serve({});
    const onPick = renderCompare();

    fireEvent.change(screen.getByRole("combobox", { name: "From" }), { target: { value: "3" } });
    fireEvent.change(screen.getByRole("combobox", { name: "To" }), { target: { value: "1" } });

    expect(onPick.mock.calls).toEqual([[3, 2], [1, 1]]);
    expect(Array.from((screen.getByRole("combobox", { name: "From" }) as HTMLSelectElement).options).map((o) => o.textContent)).toEqual(
      ["Version 3", "Version 2 · production", "Version 1"],
    );
  });

  it("shows loading while a version is read", () => {
    api.getPromptVersion.mockReturnValue(pending());
    api.getPromptUsage.mockReturnValue(pending());
    renderCompare();
    expect(screen.getByText("Loading versions…")).toBeTruthy();
  });

  it("shows the error when a version read fails", async () => {
    api.getPromptVersion.mockRejectedValue(new Error("PROMPTS.VERSION_NOT_FOUND: gone"));
    api.getPromptUsage.mockReturnValue(pending());
    renderCompare();

    expect((await screen.findByRole("alert")).textContent).toContain("PROMPTS.VERSION_NOT_FOUND: gone");
  });
});

describe("around the diff", () => {
  // Bug: the week's calls drawn for the wrong version, or with no way to the full table.
  it("shows the two versions' calls this week and links to the metrics", async () => {
    serve({});
    renderCompare(1, 2);

    const row = (await screen.findByText("1,204")).closest("tr")!;
    expect(within(row).getByText("v2")).toBeTruthy();
    expect(within(row).getByText("0.8%")).toBeTruthy();
    expect(screen.getByRole("link", { name: "View metrics by version" }).getAttribute("href")).toBe("/prompts/p-1?section=metrics");
    expect(screen.getByRole("link", { name: "Back to versions" }).getAttribute("href")).toBe("/prompts/p-1");
  });

  // Bug: Promote offered for the version that already carries production.
  it("offers to promote the right-hand version only when it does not carry production", async () => {
    serve({ 2: { labels: ["production"] } });
    renderCompare(1, 2);
    await screen.findByText("1,204");
    expect(screen.queryByRole("button", { name: "Promote version 2 to production" })).toBeNull();
  });

  // Bug: Promote from Compare opens a dialog that does not promote, or that cannot be left.
  it("opens the promotion dialog with production ticked, and Cancel closes it", async () => {
    serve({});
    renderCompare(2, 3);

    fireEvent.click(await screen.findByRole("button", { name: "Promote version 3 to production" }));

    const dialog = screen.getByRole("dialog", { name: "Deployment labels for version 3" });
    expect((within(dialog).getByRole("checkbox", { name: "production" }) as HTMLInputElement).checked).toBe(true);
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  // Bug: a failed usage read leaves an empty table that reads as no calls.
  it("shows the error when the week's calls fail to load", async () => {
    serve({});
    api.getPromptUsage.mockRejectedValue(new Error("COMMON.INVALID_PARAMETER: days"));
    renderCompare();

    expect((await screen.findByRole("alert")).textContent).toContain("COMMON.INVALID_PARAMETER: days");
  });
});

describe("defaultPair", () => {
  // Bug: the oldest version selected leaves the left side undefined.
  it("pairs the selected version with the next older one, or the next newer one when it is the oldest", () => {
    expect(defaultPair(TEXT.versions, 3)).toEqual({ left: 2, right: 3 });
    expect(defaultPair(TEXT.versions, 1)).toEqual({ left: 2, right: 1 });
    expect(defaultPair(TEXT.versions.slice(0, 1), 3)).toEqual({ left: 3, right: 3 });
  });
});
