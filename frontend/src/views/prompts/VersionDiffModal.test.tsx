// SPDX-License-Identifier: Apache-2.0
/*
 * Comparing two versions. The bugs worth catching are removed and added text on the wrong side, a comparison of a
 * version with itself, identical versions drawn as a change, and an oldest selected version with no partner.
 */
import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PromptVersionView } from "../../api/types";
import { api } from "../../test/api";
import { promptDetail, promptVersion } from "../../test/fixtures";
import { pending, renderRoute } from "../../test/render";
import { defaultPair, VersionDiffModal } from "./VersionDiffModal";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const PROMPT = promptDetail({
  type: "text",
  versions: [3, 2, 1].map((v) => ({ ...promptDetail().versions[1], id: `pv-${v}`, version: v })),
});

function serve(versions: Record<number, Partial<PromptVersionView>>) {
  api.getPromptVersion.mockImplementation((_id: string, v: number) =>
    Promise.resolve(promptVersion({ type: "text", version: v, body: "same", config: {}, ...versions[v] })),
  );
}

const renderDiff = (left = 1, right = 2) =>
  renderRoute(<VersionDiffModal prompt={PROMPT} left={left} right={right} onClose={vi.fn()} />);

describe("the diff", () => {
  // Bug: removed text drawn on the right or added text on the left.
  it("marks removed text on the left and added text on the right, for body and config", async () => {
    serve({ 1: { body: "be kind now", config: { temperature: 0.2 } }, 2: { body: "be brief now", config: { temperature: 0.5 } } });
    renderDiff();

    expect((await screen.findByText("kind")).tagName).toBe("DEL");
    expect(screen.getByText("brief").tagName).toBe("INS");
    expect(screen.getByText("0.2").tagName).toBe("DEL");
    expect(screen.getByText("0.5").tagName).toBe("INS");
    expect(screen.getByRole("heading", { name: "Body" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Config" })).toBeTruthy();
  });

  // Bug: two identical versions drawn as a diff with nothing marked.
  it("says when the versions do not differ", async () => {
    serve({ 1: { body: "same" }, 2: { body: "same" } });
    renderDiff();

    expect(await screen.findByText("No differences between version 1 and version 2.")).toBeTruthy();
  });

  // Bug: a version compared with itself reads as "no differences".
  it("asks for two different versions", async () => {
    serve({});
    renderDiff();

    fireEvent.change(screen.getByRole("combobox", { name: "Right version" }), { target: { value: "1" } });

    expect(screen.getByText("Pick two different versions.")).toBeTruthy();
  });

  // Bug: a changed picker still shows the old pair.
  it("reads the version a picker selects", async () => {
    serve({});
    renderDiff();

    fireEvent.change(screen.getByRole("combobox", { name: "Left version" }), { target: { value: "3" } });

    expect(await screen.findByText("No differences between version 3 and version 2.")).toBeTruthy();
    expect(api.getPromptVersion).toHaveBeenCalledWith("p-1", 3);
  });

  it("shows loading while a version is read", () => {
    api.getPromptVersion.mockReturnValue(pending());
    renderDiff();
    expect(screen.getByText("Loading versions…")).toBeTruthy();
  });

  it("shows the error when a version read fails", async () => {
    api.getPromptVersion.mockRejectedValue(new Error("PROMPTS.VERSION_NOT_FOUND: gone"));
    renderDiff();

    expect((await screen.findByRole("alert")).textContent).toContain("PROMPTS.VERSION_NOT_FOUND: gone");
  });
});

describe("defaultPair", () => {
  // Bug: the oldest version selected leaves the left side undefined.
  it("pairs the selected version with the next older one, or the next newer one when it is the oldest", () => {
    expect(defaultPair(PROMPT.versions, 3)).toEqual({ left: 2, right: 3 });
    expect(defaultPair(PROMPT.versions, 1)).toEqual({ left: 2, right: 1 });
  });
});
