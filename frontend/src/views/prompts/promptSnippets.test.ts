// SPDX-License-Identifier: Apache-2.0
/*
 * The Use prompt snippets. They use the Tessary SDK (Python) or plain HTTP and two span attributes (TypeScript, which
 * has no SDK yet), with the ingest key, which may read prompts. The bugs worth catching are a snippet that fetches another version than the one on
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
  it("fetches by the chosen label with the ingest key, fills every variable and placeholder, and links the version", () => {
    expect(fetchSnippet("python", CHAT)).toBe(
      [
        "# pip install tessary",
        "import tessary",
        "",
        'tessary.init("https://tessary.example.com", "tsy_w_YOUR_KEY")',
        "",
        "# Cached for 60 s, then refreshed in the background.",
        "# Pass fallback= to keep running when Tessary cannot answer.",
        'prompt = tessary.prompts.get("support/greeting", label="staging")',
        'messages = prompt.compile(customer="...", plan="...", history=history)',
        "",
        "with prompt.linked():  # spans started here link to version 3",
        "    reply = client.chat(messages=messages, **prompt.config)",
      ].join("\n"),
    );
  });

  it("fetches by version number when no label is chosen", () => {
    expect(fetchSnippet("python", { ...CHAT, selector: { version: 3 } })).toContain(
      'prompt = tessary.prompts.get("support/greeting", version=3)',
    );
  });

  // A dotted or spaced name is a variable under the shared rule, but not a Python keyword argument.
  it("passes names that are not identifiers as a dict, and a text prompt compiles to text", () => {
    const text = fetchSnippet("python", { ...CHAT, type: "text", variables: ["user.name", "plan"], placeholders: [] });
    expect(text).toContain('text = prompt.compile(**{"user.name": "...", "plan": "..."})');
    expect(text).toContain("reply = client.complete(prompt=text, **prompt.config)");
  });

  // Bug: a variable named after a Python keyword passed the identifier test, so the snippet was a SyntaxError.
  it("passes a Python keyword as a dict key and never as a bare name", () => {
    const text = fetchSnippet("python", { ...CHAT, variables: ["from"], placeholders: ["class"] });
    expect(text).toContain('messages = prompt.compile(**{"from": "...", "class": []})');
  });

  it("escapes a quote in the prompt name", () => {
    expect(fetchSnippet("python", { ...CHAT, name: 'say "hi"' })).toContain('tessary.prompts.get("say \\"hi\\"", label="staging")');
  });
});

describe("typescript", () => {
  it("fetches the chosen version over HTTP and links the call with the two documented span attributes", () => {
    expect(fetchSnippet("typescript", CHAT)).toBe(
      [
        "// No TypeScript SDK yet: fetch the version, then link the call with two span attributes.",
        "const res = await fetch(",
        '  "https://tessary.example.com/v1/prompts/fetch?name=support%2Fgreeting&label=staging",',
        '  { headers: { Authorization: "Bearer " + "tsy_w_YOUR_KEY" } },',
        ");",
        "const { data: prompt } = await res.json();",
        "// prompt.body holds the messages; fill {{customer}}, {{plan}} and the history placeholder.",
        "",
        "// The pair goes on the span of the model call; usage counts only chat spans.",
        'await tracer.startActiveSpan("chat", async (span) => {',
        '  span.setAttribute("gen_ai.operation.name", "chat");',
        '  span.setAttribute("gen_ai.request.model", prompt.config.model ?? "your-model");',
        '  span.setAttribute("tessary.prompt.name", prompt.name);',
        '  span.setAttribute("tessary.prompt.version", String(prompt.version)); // version 3',
        "  try {",
        "    const reply = await client.chat({ messages, ...prompt.config });",
        "  } finally {",
        "    span.end();",
        "  }",
        "});",
      ].join("\n"),
    );
  });

  it("fetches by version, and a text prompt names only its variables", () => {
    const text = fetchSnippet("typescript", { ...CHAT, type: "text", selector: { version: 3 }, variables: ["user.name"], placeholders: [] });
    expect(text).toContain("fetch?name=support%2Fgreeting&version=3");
    expect(text).toContain("// prompt.body holds the text; fill {{user.name}}.");
    expect(text).toContain("    const reply = await client.complete({ prompt: text, ...prompt.config });");
  });
});

describe("http", () => {
  it("encodes the name and sends the chosen selector", () => {
    expect(fetchSnippet("http", { ...CHAT, name: "support/first reply" })).toBe(
      [
        // Split so the secret scanner does not read the placeholder key as a curl credential.
        'curl -H "Authorization: Bearer ' + "tsy_w_YOUR_KEY" + '" \\',
        '  "https://tessary.example.com/v1/prompts/fetch?name=support%2Ffirst+reply&label=staging"',
      ].join("\n"),
    );
    expect(fetchSnippet("http", { ...CHAT, selector: { version: 3 } })).toContain("fetch?name=support%2Fgreeting&version=3");
  });
});
