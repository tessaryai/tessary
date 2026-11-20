// SPDX-License-Identifier: Apache-2.0
/*
 * The variable syntax is shared with the server and both SDKs: {{name}}, where the name is any non-blank text with no
 * brace in it, trimmed. The bugs worth catching are a variable missed in a chat message, one listed twice, a name read
 * here that the server or the SDKs read differently, and compile dropping a value, an unknown variable, or a
 * placeholder.
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

  // Bug: a dotted, hyphenated or spaced name is not listed, so the Variables panel offers no field for it.
  it("lists dotted, hyphenated, spaced and digit-first names", () => {
    expect(extractVariables("{{ spaced }} {{a.b}} {{1st}} {{first-name}} {{ first name }}")).toEqual([
      "spaced",
      "a.b",
      "1st",
      "first-name",
      "first name",
    ]);
  });

  // Bug: an empty name, or a name holding a brace, listed here and not by the other three copies.
  it("skips blank inner text and inner text that holds a brace", () => {
    expect(extractVariables("{{}} {{   }} {{a{b}} {{{name}}}")).toEqual(["name"]);
  });

  // Bug: the scan backtracks quadratically, and pasting a long body with an unclosed {{ freezes the page.
  it("returns quickly on an unclosed opening over a long body", () => {
    const started = performance.now();
    expect(extractVariables("{{" + " ".repeat(200_000))).toEqual([]);
    expect(performance.now() - started).toBeLessThan(500);
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
      { role: "user", content: "Hi kind" },
      { role: "assistant", content: "Hello" },
      { type: "placeholder", name: "examples" },
    ]);
  });

  // Bug: a dotted name stays uncompiled when supplied, or becomes empty when not.
  it("fills a dotted name when supplied and keeps it literal when not", () => {
    expect(compile("Hi {{user.name}}", { "user.name": "Ada" })).toBe("Hi Ada");
    expect(compile("Hi {{user.name}}", {})).toBe("Hi {{user.name}}");
  });

  // Bug: the brace handling eats a literal brace around a variable.
  it("keeps a literal brace around a variable", () => {
    expect(compile("{{{name}}}", { name: "v" })).toBe("{v}");
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

describe("compile and placeholder messages", () => {
  // Bug: messages supplied for a placeholder kept their {{variables}}, which the SDK fills, so a playground run
  // sent the model text that production never would.
  it("fills variables inside the messages a placeholder takes", () => {
    expect(
      compile([{ type: "placeholder", name: "history" }], { company: "Acme" }, { history: [{ role: "user", content: "Hi from {{company}}" }] }),
    ).toEqual([{ role: "user", content: "Hi from Acme" }]);
  });
});
