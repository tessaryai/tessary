// SPDX-License-Identifier: Apache-2.0
/*
 * The version diff marks removed words on the left and added words on the right. The bugs worth catching are a
 * change missed or invented, a whitespace-only edit hidden, a key-order change in config shown as a change, a long
 * prompt shown as two whole sides, and an inserted chat message marking every later message as changed.
 */
import { describe, expect, it } from "vitest";
import { canonicalJson, diffBody, diffChat, diffConfigKeys, tokenize, wordDiff } from "./promptDiff";

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

  // Bug: above 4,000 tokens the diff gave up and showed both sides whole, so one changed word in a long prompt hid
  // in two walls of text.
  it("two 20,000-word texts with one changed word give one removed and one added word", () => {
    const words = Array.from({ length: 20_000 }, (_, i) => `w${i}`);
    const changed = [...words];
    changed[12_345] = "edited";
    const a = words.join(" ");
    const b = changed.join(" ");

    expect(wordDiff(a, b)).toEqual([
      { kind: "same", text: `${words.slice(0, 12_345).join(" ")} ` },
      { kind: "removed", text: "w12345" },
      { kind: "added", text: "edited" },
      { kind: "same", text: ` ${words.slice(12_346).join(" ")}` },
    ]);
  });

  // Bug: a changed word in one line of a long multi-line prompt marks the whole line, or every later line.
  it("marks only the changed word inside a long multi-line text", () => {
    const lines = Array.from({ length: 5_000 }, (_, i) => `line ${i} says hello`);
    const changed = [...lines];
    changed[4_000] = "line 4000 says goodbye";
    const a = lines.join("\n");
    const b = changed.join("\n");

    expect(wordDiff(a, b)).toEqual([
      { kind: "same", text: `${lines.slice(0, 4_000).join("\n")}\nline 4000 says ` },
      { kind: "removed", text: "hello" },
      { kind: "added", text: "goodbye" },
      { kind: "same", text: `\n${lines.slice(4_001).join("\n")}` },
    ]);
  });

  // Bug: two changes with unchanged words between them merged into one change, so the shared words read as edited.
  it("keeps the unchanged words between two changes", () => {
    expect(wordDiff("a b c d e", "x b c d y")).toEqual([
      { kind: "removed", text: "a" }, { kind: "added", text: "x" }, { kind: "same", text: " b c d " },
      { kind: "removed", text: "e" }, { kind: "added", text: "y" },
    ]);
  });

  // Bug: more than 1,000 changed lines run the line search without a bound.
  it("gives two texts with more than 1,000 changed lines as one removed and one added side", () => {
    const a = Array.from({ length: 1_001 }, (_, i) => `a${i}`).join("\n");
    const b = Array.from({ length: 1_001 }, (_, i) => `b${i}`).join("\n");

    expect(wordDiff(a, b)).toEqual([{ kind: "removed", text: a }, { kind: "added", text: b }]);
  });

  // Bug: two long texts with nothing in common run the edit search without a bound and hang the tab.
  it("gives two long unrelated texts as one removed and one added side", () => {
    const a = Array.from({ length: 20_000 }, (_, i) => `a${i}`).join(" ");
    const b = Array.from({ length: 20_000 }, (_, i) => `b${i}`).join(" ");

    expect(wordDiff(a, b)).toEqual([{ kind: "removed", text: a }, { kind: "added", text: b }]);
  });
});

// Bug: whitespace runs dropped or empty tokens kept, so joined tokens differ from the input.
it("tokenizes into words and whitespace runs", () => {
  expect(tokenize(" a  b\n")).toEqual([" ", "a", "  ", "b", "\n"]);
});

// Bug: a full stop glued to its word, so adding a clause after "name." marks "name" as removed.
it("splits sentence punctuation off a word, but not a decimal point", () => {
  expect(tokenize("first name. Keep 0.3")).toEqual(["first", " ", "name", ".", " ", "Keep", " ", "0.3"]);
  expect(wordDiff("Greet by first name.", "Greet by first name and plan.")).toEqual([
    { kind: "same", text: "Greet by first name" }, { kind: "added", text: " and plan" }, { kind: "same", text: "." },
  ]);
});

describe("canonicalJson", () => {
  // Bug: a config whose keys only changed order diffs as a change.
  it("sorts keys at every depth, so a key-order change is the same", () => {
    const a = { b: 1, a: { d: [{ y: 1, x: null }], c: "s" } };
    const b = { a: { c: "s", d: [{ x: null, y: 1 }] }, b: 1 };

    expect(canonicalJson(a)).toBe(JSON.stringify({ a: { c: "s", d: [{ x: null, y: 1 }] }, b: 1 }, null, 2));
    expect(canonicalJson(b)).toBe(canonicalJson(a));
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

describe("diffChat", () => {
  const sys = (content: string) => ({ role: "system", content });
  const user = (content: string) => ({ role: "user", content });
  const history = { type: "placeholder" as const, name: "history" };

  // Bug: messages paired by position, so one inserted message marks every later message as changed.
  it("pairs messages around an inserted one", () => {
    const rows = diffChat([sys("be kind"), history], [sys("be kind"), user("hi"), history]);

    expect(rows.map((r) => r.kind)).toEqual(["same", "added", "same"]);
    expect(rows[1]).toEqual({ kind: "added", right: user("hi") });
  });

  // Bug: an edited message shown as one removed and one added message, which hides the changed words.
  it("gives an edited message one changed row with a word diff of its content", () => {
    const rows = diffChat([sys("be kind now"), history], [sys("be brief now"), history]);

    expect(rows).toEqual([
      {
        kind: "changed",
        left: sys("be kind now"),
        right: sys("be brief now"),
        ops: [
          { kind: "same", text: "be " }, { kind: "removed", text: "kind" }, { kind: "added", text: "brief" },
          { kind: "same", text: " now" },
        ],
      },
      { kind: "same", left: history, right: history },
    ]);
  });

  // Bug: a removed message, or a placeholder swapped for a message, drawn as an edit of one into the other.
  it("gives a removed message its own row, and never pairs a placeholder with a message", () => {
    expect(diffChat([sys("a"), user("b")], [sys("a")])).toEqual([
      { kind: "same", left: sys("a"), right: sys("a") }, { kind: "removed", left: user("b") },
    ]);
    expect(diffChat([history], [user("hi")])).toEqual([{ kind: "removed", left: history }, { kind: "added", right: user("hi") }]);
  });

  // Bug: a long chat prompt with every message rewritten runs the message search without a bound.
  it("pairs every message when more than 1,000 messages changed", () => {
    const a = Array.from({ length: 501 }, (_, i) => sys(`a${i}`));
    const b = Array.from({ length: 501 }, (_, i) => sys(`b${i}`));

    const rows = diffChat(a, b);

    expect(rows).toHaveLength(501);
    expect(rows.every((r) => r.kind === "changed")).toBe(true);
  });

  // Bug: a role change on unchanged content reads as no change.
  it("counts a role change as a change", () => {
    expect(diffChat([sys("hi")], [user("hi")]).map((r) => r.kind)).toEqual(["changed"]);
  });
});

describe("diffConfigKeys", () => {
  // Bug: config compared as one text block, so a reader cannot tell which key changed.
  it("gives one row per top-level key, in the old order with new keys last", () => {
    const rows = diffConfigKeys({ model: "m", temperature: 0.3 }, { temperature: 0.4, model: "m", max_tokens: 200 });

    expect(rows).toEqual([
      { key: "model", kind: "same", left: '"m"', right: '"m"' },
      {
        key: "temperature",
        kind: "changed",
        left: "0.3",
        right: "0.4",
        ops: [{ kind: "removed", text: "0.3" }, { kind: "added", text: "0.4" }],
      },
      { key: "max_tokens", kind: "added", right: "200" },
    ]);
  });

  // Bug: a removed key disappears instead of showing on the left.
  it("keeps a removed key on the left, and a nested key-order change is the same", () => {
    expect(diffConfigKeys({ a: { y: 1, x: 2 }, gone: true }, { a: { x: 2, y: 1 } })).toEqual([
      { key: "a", kind: "same", left: canonicalJson({ x: 2, y: 1 }), right: canonicalJson({ x: 2, y: 1 }) },
      { key: "gone", kind: "removed", left: "true" },
    ]);
  });
});

describe("diffConfigKeys and inherited names", () => {
  // Bug: `key in config` saw Object.prototype, so a dropped "toString" key threw and an added one never showed.
  it("treats a key named after an Object method as a plain key", () => {
    expect(diffConfigKeys({ toString: 1 }, {})).toEqual([{ key: "toString", kind: "removed", left: "1" }]);
    expect(diffConfigKeys({}, { constructor: "x" })).toEqual([{ key: "constructor", kind: "added", right: '"x"' }]);
  });
});
