// SPDX-License-Identifier: Apache-2.0
/*
 * A prompt name with a / is a path: the list groups names into folders. The bugs worth catching are a flat list
 * that hides folders, a row shown twice because the server over-returned, a folder dated by the wrong child, and
 * `latest` (derived, never set) leaking into a label editor or the delete gate.
 */
import { describe, expect, it } from "vitest";
import { promptSummary } from "../../test/fixtures";
import {
  folderCrumbs, folderOf, formatVersionRef, groupByFolder, labelsWithoutLatest, leafName, parentFolder,
} from "./promptFolders";

describe("groupByFolder", () => {
  // Bug: a flat list at the root hides the folder, or lists the nested prompt as if it sat at the root.
  it("nests a deeper name under its first folder at the root and keeps a root prompt as a prompt", () => {
    const x = promptSummary({ id: "p-x", name: "team/a/x", updated_at: "2026-10-01T00:00:00Z" });
    const y = promptSummary({ id: "p-y", name: "y" });

    expect(groupByFolder([y, x], "")).toEqual([
      { kind: "folder", label: "team", folder: "team", count: 1, updatedAt: "2026-10-01T00:00:00Z" },
      { kind: "prompt", label: "y", prompt: y },
    ]);
  });

  // Bug: the server returns a name outside the folder and the row shows under the wrong folder.
  it("drops an item outside the folder", () => {
    expect(groupByFolder([promptSummary({ name: "other/z" })], "team")).toEqual([]);
  });

  // Bug: a folder dated by its first or last child instead of its newest one.
  it("dates a folder by its newest child and counts every child, inside a nested folder", () => {
    const items = [
      promptSummary({ id: "1", name: "team/support/a", updated_at: "2026-10-01T00:00:00Z" }),
      promptSummary({ id: "2", name: "team/support/b", updated_at: "2026-10-03T00:00:00Z" }),
      promptSummary({ id: "3", name: "team/support/c", updated_at: "2026-10-02T00:00:00Z" }),
    ];

    expect(groupByFolder(items, "team")).toEqual([
      { kind: "folder", label: "support", folder: "team/support", count: 3, updatedAt: "2026-10-03T00:00:00Z" },
    ]);
  });

  // Bug: folders and prompts interleaved in server order, so a folder hides among prompts.
  it("lists folders first, then prompts, each alphabetical", () => {
    const items = [
      promptSummary({ id: "1", name: "zeta" }),
      promptSummary({ id: "2", name: "b/one" }),
      promptSummary({ id: "3", name: "alpha" }),
      promptSummary({ id: "4", name: "a/one" }),
    ];

    expect(groupByFolder(items, "").map((e) => e.label)).toEqual(["a", "b", "alpha", "zeta"]);
  });
});

describe("path helpers", () => {
  // Bug: the breadcrumb links each crumb to its own segment instead of the path up to it.
  it("builds one crumb per segment, each with the full path to it", () => {
    expect(folderCrumbs("team/a")).toEqual([
      { label: "team", folder: "team" },
      { label: "a", folder: "team/a" },
    ]);
    expect(folderCrumbs("")).toEqual([]);
  });

  // Bug: going up from a top folder writes ?folder= (an empty folder) instead of clearing it.
  it("finds the parent folder, null at the root", () => {
    expect(parentFolder("team/a")).toBe("team");
    expect(parentFolder("team")).toBeNull();
  });

  // Bug: the leaf or folder taken from the first / instead of the last.
  it("splits a name at its last /", () => {
    expect(leafName("a/b/c")).toBe("c");
    expect(leafName("greeting")).toBe("greeting");
    expect(folderOf("a/b/c")).toBe("a/b");
    expect(folderOf("greeting")).toBe("");
  });
});

// Bug: `latest` offered as a label to set, or counted as a deployment label that blocks deleting a version.
it("strips latest from a label list", () => {
  expect(labelsWithoutLatest(["production", "latest"])).toEqual(["production"]);
});

// Bug: a label a span sent as its version reads as "vprod", or a span with no version reads "(null)".
it("formats a version a span sent", () => {
  expect(formatVersionRef("3")).toBe("v3");
  expect(formatVersionRef("prod")).toBe("(prod)");
  expect(formatVersionRef(null)).toBe("");
});
