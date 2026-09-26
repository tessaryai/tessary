// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → Import bundle. The bugs worth catching: an import that can be sent without a pipeline/meta.yaml,
 * a Replace (which deletes the current pipeline) that can be sent without typing the project name back,
 * the wrong mode reaching the server, and a result that misreports what changed.
 */
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { EntityDiff, ImportResult } from "../../api/client";
import { currentLocation, renderRoute } from "../../test/render";
import { ImportYaml } from "./Import";

const api = vi.hoisted(() => ({ base: "/api/orgs/acme/projects/support-bot", importEvalsDirectory: vi.fn() }));

vi.mock("../../tenant/TenantContext", () => ({
  useProjectApi: () => api,
  useTenant: () => ({ orgSlug: "acme", projectSlug: "support-bot" }),
}));

/** A file as a directory picker hands it over: its path within the chosen directory on webkitRelativePath. */
const picked = (path: string) => {
  const f = new File(["x"], path.split("/").pop()!);
  Object.defineProperty(f, "webkitRelativePath", { value: path });
  return f;
};
const BUNDLE = [
  ".tessary/pipeline/meta.yaml",
  ".tessary/pipeline/call_sites/answer.yaml",
  ".tessary/pipeline/call_sites/route.yml",
  ".tessary/pipeline/failure_modes/answer.yaml",
  ".tessary/pipeline/quality_dimensions/tone.yaml",
  ".tessary/graders/answer/hallucination.yaml",
  ".tessary/datasets/answer.jsonl",
];

const none: EntityDiff = { added: 0, updated: 0, removed: 0 };
const result = (over: Partial<ImportResult> = {}): ImportResult => ({
  mode: "upsert",
  metaReplaced: false,
  callSites: none,
  chains: none,
  failureModes: none,
  repairs: [],
  ...over,
});

const choose = (paths: string[]) => {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  fireEvent.change(input, { target: { files: paths.map(picked) } });
};
const submit = (name: string) => screen.getByRole("button", { name }) as HTMLButtonElement;

beforeEach(() => {
  api.importEvalsDirectory.mockResolvedValue(result());
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("choosing a bundle", () => {
  it("reads what the directory holds, counting call sites, graders, and quality dims apart", () => {
    renderRoute(<ImportYaml />);
    expect(screen.getByText("Nothing selected yet")).toBeTruthy();
    expect(submit("Import bundle").disabled).toBe(true);

    choose(BUNDLE);

    expect(screen.getByText("meta.yaml", { selector: "code" })).toBeTruthy();
    const counts = screen.getByText("call sites").parentElement!.parentElement!.textContent;
    expect(counts).toBe("2 call sites1 graders1 quality dims7 files");
    expect(screen.getByText("7 files selected")).toBeTruthy();
    expect(submit("Import bundle").disabled).toBe(false);
  });

  it("refuses a directory without pipeline/meta.yaml and says what it found", () => {
    renderRoute(<ImportYaml />);

    choose(["notes/pipeline/call_sites/answer.yaml", "notes/readme.md"]);

    expect(screen.getByText(/doesn't look like a .tessary bundle/)).toBeTruthy();
    expect(screen.getByText("meta.yaml missing · 1 call site · 0 graders · 2 total")).toBeTruthy();
    expect(submit("Import bundle").disabled).toBe(true);
  });

  it("finds meta.yaml at the top of the selection too, and counts every quality dim shard", () => {
    renderRoute(<ImportYaml />);

    choose([
      "pipeline/meta.yml",
      "x/pipeline/quality_dimensions/a.yaml",
      "x/pipeline/quality_dimensions/b.yaml",
    ]);

    const counts = screen.getByText("call sites").parentElement!.parentElement!.textContent;
    expect(counts).toBe("0 call sites0 graders2 quality dims3 files");
    expect(submit("Import bundle").disabled).toBe(false);
  });

  it("lists the first thirty files and counts the rest", () => {
    renderRoute(<ImportYaml />);

    choose([".tessary/pipeline/meta.yaml", ...Array.from({ length: 31 }, (_, i) => `.tessary/datasets/d${i}.jsonl`)]);

    expect(screen.getByText("32 files selected")).toBeTruthy();
    expect(screen.getAllByRole("listitem")).toHaveLength(31);
    expect(screen.getByText("… and 2 more")).toBeTruthy();
    expect(screen.queryByText("quality dims")).toBeNull();
  });
});

describe("importing", () => {
  it("upserts the chosen files, then reports each change and clears the selection", async () => {
    api.importEvalsDirectory.mockResolvedValue(
      result({
        callSites: { added: 1, updated: 0, removed: 0 },
        chains: { added: 0, updated: 1, removed: 0 },
        failureModes: { added: 0, updated: 0, removed: 1 },
      }),
    );
    renderRoute(<ImportYaml />);
    choose(BUNDLE);

    fireEvent.click(submit("Import bundle"));

    expect(await screen.findByText("Pipeline updated")).toBeTruthy();
    const [files, mode] = api.importEvalsDirectory.mock.calls[0];
    expect(files.map((f: File & { webkitRelativePath: string }) => f.webkitRelativePath)).toEqual(BUNDLE);
    expect(mode).toBe("upsert");
    expect(screen.getByText("+ 1 call sites added")).toBeTruthy();
    expect(screen.getByText("~ 1 chains updated")).toBeTruthy();
    expect(screen.getByText("- 1 failure modes removed")).toBeTruthy();
    expect(screen.getByText("Nothing selected yet")).toBeTruthy();
  });

  it("says when the pipeline already matched", async () => {
    renderRoute(<ImportYaml />);
    choose(BUNDLE);

    fireEvent.click(submit("Import bundle"));

    expect(await screen.findByText(/No changes/)).toBeTruthy();
  });

  it("holds Replace until the project name is typed back, then sends replace", async () => {
    api.importEvalsDirectory.mockResolvedValue(result({ mode: "replace" }));
    renderRoute(<ImportYaml />);
    choose(BUNDLE);

    fireEvent.click(screen.getByRole("button", { name: /Replace.*Destructive/ }));
    const confirmBox = screen.getByLabelText("Type the project name to confirm replace");
    expect(submit("Replace pipeline").disabled).toBe(true);
    fireEvent.change(confirmBox, { target: { value: "support" } });
    expect(submit("Replace pipeline").disabled).toBe(true);
    fireEvent.change(confirmBox, { target: { value: " support-bot " } });
    fireEvent.click(submit("Replace pipeline"));

    expect(await screen.findByText("Pipeline replaced")).toBeTruthy();
    expect(api.importEvalsDirectory.mock.calls[0][1]).toBe("replace");
  });

  it("does not let a typed confirmation outlive the import it confirmed", async () => {
    renderRoute(<ImportYaml />);
    choose(BUNDLE);
    fireEvent.click(screen.getByRole("button", { name: /Replace.*Destructive/ }));
    fireEvent.change(screen.getByLabelText("Type the project name to confirm replace"), {
      target: { value: "support-bot" },
    });
    fireEvent.click(submit("Replace pipeline"));
    await screen.findByText("Pipeline updated");

    choose(BUNDLE);
    expect(submit("Replace pipeline").disabled).toBe(true);
  });

  it("says when the import fails, and keeps the selection", async () => {
    api.importEvalsDirectory.mockRejectedValue(new Error("meta.yaml: unknown key"));
    renderRoute(<ImportYaml />);
    choose(BUNDLE);

    fireEvent.click(submit("Import bundle"));

    expect(await screen.findByText("meta.yaml: unknown key")).toBeTruthy();
    await waitFor(() => expect(submit("Import bundle").disabled).toBe(false));
    expect(screen.getByText("7 files selected")).toBeTruthy();
  });

  it("goes back to the project's sources", () => {
    renderRoute(<ImportYaml />, { route: "/orgs/acme/projects/support-bot/settings/import" });

    fireEvent.click(screen.getByRole("button", { name: "Back to Sources" }));

    expect(currentLocation()).toBe("/orgs/acme/projects/support-bot/settings/sources");
  });
});
