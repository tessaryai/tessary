// SPDX-License-Identifier: Apache-2.0
/*
 * The version diff marks removed words on the left and added words on the right. The bugs worth catching are a
 * change missed or invented, a whitespace-only edit hidden, a key-order change in config shown as a change, and a
 * pair of huge bodies building a quadratic table.
 */
import { describe, expect, it } from "vitest";
import { DIFF_TOKEN_CAP, canonicalJson, diffBody, diffConfig, tokenize, wordDiff } from "./promptDiff";

describe("wordDiff", () => {
  // Bug: identical text split into many ops, or two empty bodies drawn as one empty change.
  it("gives one same op for identical text and nothing for two empties", () => {
    expect(wordDiff("be kind", "be kind")).toEqual([{ kind: "same", text: "be kind" }]);
    expect(wordDiff("", "")).toEqual([]);
  });

  // Bug: an insertion, deletion or replacement in the middle shifts every later word into the change.
  it.each([
    ["inserts", "be kind", "be very kind", [
      { kind: "same", text: "be " }, { kind: "added", text: "very " }, { kind: "same", text: "kind" },
    ]],
    ["deletes", "be very kind", "be kind", [
      { kind: "same", text: "be " }, { kind: "removed", text: "very " }, { kind: "same", text: "kind" },
    ]],
    ["replaces", "be kind now", "be brief now", [
      { kind: "same", text: "be " }, { kind: "removed", text: "kind" }, { kind: "added", text: "brief" },
      { kind: "same", text: " now" },
    ]],
  ])("%s in the middle", (_, a, b, ops) => {
    expect(wordDiff(a, b)).toEqual(ops);
  });

  // Bug: whitespace dropped by the tokenizer, so a reflowed prompt reads as unchanged.
  it("counts a whitespace-only change as a change", () => {
    expect(wordDiff("a b", "a  b")).toEqual([
      { kind: "same", text: "a" }, { kind: "removed", text: " " }, { kind: "added", text: "  " },
      { kind: "same", text: "b" },
    ]);
  });

  // Bug: adjacent ops of one kind left unmerged, so a removed sentence renders word by word.
  it("merges adjacent ops of one kind, including a removed or added tail", () => {
    expect(wordDiff("one two three", "one")).toEqual([
      { kind: "same", text: "one" }, { kind: "removed", text: " two three" },
    ]);
    expect(wordDiff("one", "one two three")).toEqual([
      { kind: "same", text: "one" }, { kind: "added", text: " two three" },
    ]);
  });

  // Bug: a table of (cap + 1) squared cells built for two huge bodies.
  it.each([
    ["left", "a ".repeat(DIFF_TOKEN_CAP), "b"],
    ["right", "b", "a ".repeat(DIFF_TOKEN_CAP)],
  ])("falls back to whole-side ops when the %s side exceeds the cap", (_, a, b) => {
    expect(tokenize(a + b).length).toBeGreaterThan(DIFF_TOKEN_CAP);
    expect(wordDiff(a, b)).toEqual([{ kind: "removed", text: a }, { kind: "added", text: b }]);
  });
});

// Bug: whitespace runs dropped or empty tokens kept, so joined tokens differ from the input.
it("tokenizes into words and whitespace runs", () => {
  expect(tokenize(" a  b\n")).toEqual([" ", "a", "  ", "b", "\n"]);
});

describe("canonicalJson", () => {
  // Bug: a config whose keys only changed order diffs as a change.
  it("sorts keys at every depth, so a key-order change is the same", () => {
    const a = { b: 1, a: { d: [{ y: 1, x: null }], c: "s" } };
    const b = { a: { c: "s", d: [{ x: null, y: 1 }] }, b: 1 };

    expect(canonicalJson(a)).toBe(JSON.stringify({ a: { c: "s", d: [{ x: null, y: 1 }] }, b: 1 }, null, 2));
    expect(diffConfig(a, b)).toEqual([{ kind: "same", text: canonicalJson(a) }]);
  });
});

describe("diffBody", () => {
  // Bug: a text body compared as JSON, so quotes and escapes show as changes.
  it("compares a text body word by word", () => {
    expect(diffBody("text", "say hi", "say bye")).toEqual([
      { kind: "same", text: "say " }, { kind: "removed", text: "hi" }, { kind: "added", text: "bye" },
    ]);
  });

  // Bug: a chat body compared by reference or by unsorted JSON.
  it("compares a chat body as sorted JSON", () => {
    const a = [{ role: "user", content: "hi" }];
    const b = [{ content: "hi", role: "user" }];

    expect(diffBody("chat", a, b)).toEqual([{ kind: "same", text: canonicalJson(a) }]);
  });
});
