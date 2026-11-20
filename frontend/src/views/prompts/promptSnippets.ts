// SPDX-License-Identifier: Apache-2.0
import type { PromptType } from "../../api/types";
import { joinAnd } from "./LabelEditor";

export type SnippetLanguage = "python" | "typescript" | "http";

/** `selector` is what the snippet fetches by: one of the version's labels, or its number. */
export type SnippetInput = {
  host: string;
  name: string;
  type: PromptType;
  version: number;
  selector: { label: string } | { version: number };
  variables: string[];
  placeholders: string[];
};

/** The ingest key: an application that sends traces fetches its prompts with the same key. */
const KEY = "tsy_w_YOUR_KEY";
const IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/;
/** Python's keywords, which pass IDENTIFIER but cannot be a keyword argument or a variable. */
const PY_KEYWORDS = new Set(
  ("False None True and as assert async await break class continue def del elif else except finally for from global if " +
    "import in is lambda nonlocal not or pass raise return try while with yield").split(" "),
);
const pyName = (k: string) => IDENTIFIER.test(k) && !PY_KEYWORDS.has(k);
/** JSON string syntax is valid in both Python and TypeScript for the text a name can hold. */
const str = (s: string) => JSON.stringify(s);

function fetchUrl(i: SnippetInput): string {
  const query = new URLSearchParams({ name: i.name });
  if ("label" in i.selector) query.set("label", i.selector.label);
  else query.set("version", String(i.selector.version));
  return `${i.host}/v1/prompts/fetch?${query}`;
}

function python(i: SnippetInput): string {
  const selector = "label" in i.selector ? `label=${str(i.selector.label)}` : `version=${i.selector.version}`;
  const vars = i.variables.map((v) => [v, '"..."'] as const);
  const holes = i.placeholders.map((p) => [p, pyName(p) ? p : "[]"] as const);
  const all = [...vars, ...holes];
  const args = all.every(([k]) => pyName(k))
    ? all.map(([k, v]) => `${k}=${v}`).join(", ")
    : `**{${all.map(([k, v]) => `${str(k)}: ${v}`).join(", ")}}`;
  const out = i.type === "chat" ? "messages" : "text";
  const call = i.type === "chat" ? "client.chat(messages=messages, **prompt.config)" : "client.complete(prompt=text, **prompt.config)";
  return [
    "# pip install tessary",
    "import tessary",
    "",
    `tessary.init(${str(i.host)}, ${str(KEY)})`,
    "",
    "# Cached for 60 s, then refreshed in the background.",
    "# Pass fallback= to keep running when Tessary cannot answer.",
    `prompt = tessary.prompts.get(${str(i.name)}, ${selector})`,
    `${out} = prompt.compile(${args})`,
    "",
    `with prompt.linked():  # spans started here link to version ${i.version}`,
    `    reply = ${call}`,
  ].join("\n");
}

/**
 * No TypeScript SDK yet, so the snippet is what any OpenTelemetry setup can do: fetch the version over HTTP and set
 * the two documented `tessary.prompt.*` attributes on the model-call span, which usage counts only when it is a chat
 * span.
 */
function typescript(i: SnippetInput): string {
  const holes = i.placeholders.map((p) => `the ${p} placeholder`);
  const fill = joinAnd([...i.variables.map((v) => `{{${v}}}`), ...holes]);
  const what = i.type === "chat" ? "the messages" : "the text";
  const call = i.type === "chat" ? "client.chat({ messages, ...prompt.config })" : "client.complete({ prompt: text, ...prompt.config })";
  return [
    "// No TypeScript SDK yet: fetch the version, then link the call with two span attributes.",
    "const res = await fetch(",
    `  ${str(fetchUrl(i))},`,
    `  { headers: { Authorization: "Bearer " + ${str(KEY)} } },`,
    ");",
    "const { data: prompt } = await res.json();",
    `// prompt.body holds ${what}${fill ? `; fill ${fill}` : ""}.`,
    "",
    "// The pair goes on the span of the model call; usage counts only chat spans.",
    'await tracer.startActiveSpan("chat", async (span) => {',
    '  span.setAttribute("gen_ai.operation.name", "chat");',
    '  span.setAttribute("gen_ai.request.model", prompt.config.model ?? "your-model");',
    '  span.setAttribute("tessary.prompt.name", prompt.name);',
    `  span.setAttribute("tessary.prompt.version", String(prompt.version)); // version ${i.version}`,
    "  try {",
    `    const reply = await ${call};`,
    "  } finally {",
    "    span.end();",
    "  }",
    "});",
  ].join("\n");
}

function http(i: SnippetInput): string {
  return [`curl -H "Authorization: Bearer ${KEY}" \\`, `  "${fetchUrl(i)}"`].join("\n");
}

/** Code that fetches this prompt by the chosen selector, compiles it, and links the model call to the version. */
export function fetchSnippet(language: SnippetLanguage, input: SnippetInput): string {
  return language === "python" ? python(input) : language === "typescript" ? typescript(input) : http(input);
}
