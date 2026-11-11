// SPDX-License-Identifier: Apache-2.0
/*
 * The variable syntax is shared with the server and both SDKs: {{name}}, a name that starts with a letter or _ and
 * holds only letters, digits and _, with optional spaces inside the braces. The bugs worth catching are a variable
 * missed in a chat message, one listed twice, a dotted name accepted here that the SDKs never fill, and compile
 * dropping a value, an unknown variable, or a placeholder.
 */
import { describe, expect, it } from "vitest";
import type { PromptChatBody } from "../../api/types";
import { compile, extractPlaceholders, extractVariables, tokenizeVariables } from "./promptCompile";

const CHAT: PromptChatBody = [
  { role: "system", content: "You are {{tone}} support for {{brand}}." },
  { type: "placeholder", name: "history" },
  { role: "user", content: "{{ tone }} again, from {{brand}}" },
];

describe("extractVariables", () => {
  // Bug: only the first message read, a repeat listed twice, or a placeholder name taken for a variable.
  it("reads every chat message in first-appearance order, once each, and skips placeholders", () => {
    expect(extractVariables(CHAT)).toEqual(["tone", "brand"]);
  });

  // Bug: a dotted or digit-first name accepted here, which the server and the SDKs never fill.
  it("matches spaced names and rejects dotted and digit-first names", () => {
    expect(extractVariables("{{ spaced }} {{a.b}} {{1bad}} {{_ok2}}")).toEqual(["spaced", "_ok2"]);
  });
});

// Bug: a text body offered a placeholder, or a placeholder repeated in the body asked for twice.
it("lists each placeholder of a chat body once, none for text", () => {
  expect(extractPlaceholders([...CHAT, { type: "placeholder", name: "history" }])).toEqual(["history"]);
  expect(extractPlaceholders("{{history}}")).toEqual([]);
});

describe("compile", () => {
  // Bug: an empty value left as the literal variable, or an unknown variable erased.
  it("fills a text body: known replaced, an empty value stays empty, an unknown one stays literal", () => {
    expect(compile("Hi {{name}}{{suffix}} from {{team}}", { name: "Ada", suffix: "" })).toBe("Hi Ada from {{team}}");
  });

  // Bug: the placeholder list dropped instead of spliced in place, or an unfilled placeholder silently removed.
  it("splices a filled placeholder in place and keeps an unfilled one", () => {
    const body: PromptChatBody = [
      { role: "system", content: "Be {{tone}}." },
      { type: "placeholder", name: "history" },
      { type: "placeholder", name: "examples" },
    ];
    const history = [
      { role: "user", content: "Hi {{tone}}" },
      { role: "assistant", content: "Hello" },
    ];

    expect(compile(body, { tone: "kind" }, { history })).toEqual([
      { role: "system", content: "Be kind." },
      { role: "user", content: "Hi {{tone}}" },
      { role: "assistant", content: "Hello" },
      { type: "placeholder", name: "examples" },
    ]);
  });

  // Bug: a value that names an Object.prototype key read as a filled variable.
  it("does not fill a variable from an inherited key", () => {
    expect(compile("{{constructor}}", {})).toBe("{{constructor}}");
  });
});

// Bug: text lost or duplicated between variables, so the highlighted body differs from the stored one.
it("tokenizes a body into text and variables that join back into the input", () => {
  const text = "{{a}} and {{ b }}, then {{a}} end";
  const tokens = tokenizeVariables(text);

  expect(tokens.map((t) => t.text).join("")).toBe(text);
  expect(tokens).toEqual([
    { kind: "variable", text: "{{a}}", name: "a" },
    { kind: "text", text: " and " },
    { kind: "variable", text: "{{ b }}", name: "b" },
    { kind: "text", text: ", then " },
    { kind: "variable", text: "{{a}}", name: "a" },
    { kind: "text", text: " end" },
  ]);
  expect(tokenizeVariables("")).toEqual([]);
});
