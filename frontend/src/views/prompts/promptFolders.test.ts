// SPDX-License-Identifier: Apache-2.0
/*
 * A prompt name with a / is a path; the server groups names into folders (PromptRepositoryTest). The bugs worth
 * catching here are a crumb or parent that points at the wrong path, and `latest` (derived, never set) leaking into
 * a label editor.
 */
import { describe, expect, it } from "vitest";
import {
  folderCrumbs, folderOf, formatVersionRef, labelsWithoutLatest, leafName, parentFolder,
} from "./promptFolders";

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
