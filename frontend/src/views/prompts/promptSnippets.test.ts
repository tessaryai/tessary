// SPDX-License-Identifier: Apache-2.0
/*
 * The Use prompt snippets. The bugs worth catching are a snippet that fetches another version than the one on
 * screen (the reader runs code for a version they did not look at), a variable name written where the language
 * cannot take it (a syntax error on paste), a name with a / or a space left raw in a URL, and a name with a quote
 * that breaks the string it sits in.
 */
import { describe, expect, it } from "vitest";
import { fetchSnippet, type SnippetInput } from "./promptSnippets";

const CHAT: SnippetInput = {
  host: "https://tessary.example.com",
  name: "support/greeting",
  type: "chat",
  version: 3,
  selector: { label: "staging" },
  variables: ["customer", "plan"],
  placeholders: ["history"],
};

describe("python", () => {
  it("fetches by the chosen label, fills every variable and placeholder, and links the version", () => {
    expect(fetchSnippet("python", CHAT)).toBe(
      [
        "# pip install ./packages/sdk-python",
        "from tessary_prompts import TessaryPrompts",
        "",
        'prompts = TessaryPrompts("https://tessary.example.com", "tsy_q_YOUR_KEY")',
        "",
        "# Cached for 60 s, then refreshed in the background.",
        "# Pass fallback= to keep running when Tessary cannot answer.",
        'prompt = prompts.get_prompt("support/greeting", label="staging")',
        'messages = prompt.compile(customer="...", plan="...", history=history)',
        "",
        'with tracer.start_as_current_span("support.greeting") as span:',
        "    prompt.link(span)  # links this call to version 3",
        "    reply = client.chat(messages=messages, **prompt.config)",
      ].join("\n"),
    );
  });

  it("fetches by version number when no label is chosen", () => {
    expect(fetchSnippet("python", { ...CHAT, selector: { version: 3 } })).toContain(
      'prompt = prompts.get_prompt("support/greeting", version=3)',
    );
  });

  // A dotted or spaced name is a variable under the shared rule, but not a Python keyword argument.
  it("passes names that are not identifiers as a dict, and a text prompt compiles to text", () => {
    const text = fetchSnippet("python", { ...CHAT, type: "text", variables: ["user.name", "plan"], placeholders: [] });
    expect(text).toContain('text = prompt.compile(**{"user.name": "...", "plan": "..."})');
    expect(text).toContain("reply = client.complete(prompt=text, **prompt.config)");
  });

  it("escapes a quote in the prompt name", () => {
    expect(fetchSnippet("python", { ...CHAT, name: 'say "hi"' })).toContain('prompts.get_prompt("say \\"hi\\"", label="staging")');
  });
});

describe("typescript", () => {
  it("fetches by the chosen label, narrows the type, fills variables and placeholders, and links the version", () => {
    expect(fetchSnippet("typescript", CHAT)).toBe(
      [
        "// pnpm add <path-to>/tessaryai-prompts-0.1.0.tgz",
        'import { TessaryPrompts } from "@tessaryai/prompts";',
        "",
        'const prompts = new TessaryPrompts({ host: "https://tessary.example.com", apiKey: "tsy_q_YOUR_KEY" });',
        "",
        "// Cached for 60 s, then refreshed in the background.",
        "// Pass fallback to keep running when Tessary cannot answer.",
        'const prompt = await prompts.get("support/greeting", { label: "staging" });',
        'if (prompt.type !== "chat") throw new Error("support/greeting is not a chat prompt");',
        'const messages = prompt.compile({ customer: "...", plan: "..." }, { history });',
        "",
        'await tracer.startActiveSpan("support.greeting", async (span) => {',
        "  prompt.link(span); // links this call to version 3",
        "  const reply = await client.chat({ messages, ...prompt.config });",
        "  span.end();",
        "});",
      ].join("\n"),
    );
  });

  it("quotes a key that is not an identifier and fetches by version", () => {
    const text = fetchSnippet("typescript", { ...CHAT, type: "text", selector: { version: 3 }, variables: ["user.name"], placeholders: [] });
    expect(text).toContain('const prompt = await prompts.get("support/greeting", { version: 3 });');
    expect(text).toContain('const text = prompt.compile({ "user.name": "..." });');
  });
});

describe("http", () => {
  it("encodes the name and sends the chosen selector", () => {
    expect(fetchSnippet("http", { ...CHAT, name: "support/first reply" })).toBe(
      [
        'curl -H "Authorization: Bearer tsy_q_YOUR_KEY" \\',
        '  "https://tessary.example.com/v1/prompts/fetch?name=support%2Ffirst+reply&label=staging"',
      ].join("\n"),
    );
    expect(fetchSnippet("http", { ...CHAT, selector: { version: 3 } })).toContain("fetch?name=support%2Fgreeting&version=3");
  });
});
