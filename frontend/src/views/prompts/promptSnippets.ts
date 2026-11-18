// SPDX-License-Identifier: Apache-2.0
import type { PromptType } from "../../api/types";

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

const KEY = "tsy_q_YOUR_KEY";
const IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/;
/** JSON string syntax is valid in both Python and TypeScript for the text a name can hold. */
const str = (s: string) => JSON.stringify(s);
const spanName = (name: string) => name.replaceAll("/", ".");

function python(i: SnippetInput): string {
  const selector = "label" in i.selector ? `label=${str(i.selector.label)}` : `version=${i.selector.version}`;
  const vars = i.variables.map((v) => [v, '"..."'] as const);
  const holes = i.placeholders.map((p) => [p, IDENTIFIER.test(p) ? p : "[]"] as const);
  const all = [...vars, ...holes];
  const args = all.every(([k]) => IDENTIFIER.test(k))
    ? all.map(([k, v]) => `${k}=${v}`).join(", ")
    : `**{${all.map(([k, v]) => `${str(k)}: ${v}`).join(", ")}}`;
  const out = i.type === "chat" ? "messages" : "text";
  const call = i.type === "chat" ? "client.chat(messages=messages, **prompt.config)" : "client.complete(prompt=text, **prompt.config)";
  return [
    "# pip install ./packages/sdk-python",
    "from tessary_prompts import TessaryPrompts",
    "",
    `prompts = TessaryPrompts(${str(i.host)}, ${str(KEY)})`,
    "",
    "# Cached for 60 s, then refreshed in the background.",
    "# Pass fallback= to keep running when Tessary cannot answer.",
    `prompt = prompts.get_prompt(${str(i.name)}, ${selector})`,
    `${out} = prompt.compile(${args})`,
    "",
    `with tracer.start_as_current_span(${str(spanName(i.name))}) as span:`,
    `    prompt.link(span)  # links this call to version ${i.version}`,
    `    reply = ${call}`,
  ].join("\n");
}

function typescript(i: SnippetInput): string {
  const selector = "label" in i.selector ? `{ label: ${str(i.selector.label)} }` : `{ version: ${i.selector.version} }`;
  const key = (k: string) => (IDENTIFIER.test(k) ? k : str(k));
  const object = (entries: string[]) => (entries.length ? `{ ${entries.join(", ")} }` : "{}");
  const vars = object(i.variables.map((v) => `${key(v)}: "..."`));
  const holes = i.placeholders.map((p) => (IDENTIFIER.test(p) ? p : `${str(p)}: []`));
  const out = i.type === "chat" ? "messages" : "text";
  const args = i.type === "chat" && holes.length ? `${vars}, ${object(holes)}` : vars;
  const call = i.type === "chat" ? "client.chat({ messages, ...prompt.config })" : "client.complete({ prompt: text, ...prompt.config })";
  return [
    "// pnpm add <path-to>/tessaryai-prompts-0.1.0.tgz",
    'import { TessaryPrompts } from "@tessaryai/prompts";',
    "",
    `const prompts = new TessaryPrompts({ host: ${str(i.host)}, apiKey: ${str(KEY)} });`,
    "",
    "// Cached for 60 s, then refreshed in the background.",
    "// Pass fallback to keep running when Tessary cannot answer.",
    `const prompt = await prompts.get(${str(i.name)}, ${selector});`,
    `if (prompt.type !== ${str(i.type)}) throw new Error(${str(`${i.name} is not a ${i.type} prompt`)});`,
    `const ${out} = prompt.compile(${args});`,
    "",
    `await tracer.startActiveSpan(${str(spanName(i.name))}, async (span) => {`,
    `  prompt.link(span); // links this call to version ${i.version}`,
    `  const reply = await ${call};`,
    "  span.end();",
    "});",
  ].join("\n");
}

function http(i: SnippetInput): string {
  const query = new URLSearchParams({ name: i.name });
  if ("label" in i.selector) query.set("label", i.selector.label);
  else query.set("version", String(i.selector.version));
  return [`curl -H "Authorization: Bearer ${KEY}" \\`, `  "${i.host}/v1/prompts/fetch?${query}"`].join("\n");
}

/** Code that fetches this prompt by the chosen selector, compiles it, and links the model call to the version. */
export function fetchSnippet(language: SnippetLanguage, input: SnippetInput): string {
  return language === "python" ? python(input) : language === "typescript" ? typescript(input) : http(input);
}
