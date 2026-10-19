// SPDX-License-Identifier: Apache-2.0
// Prompt values: compile a template, list its variables, and link a span to the version.

export type ChatMessage = { role: string; content: string; [key: string]: unknown };
export type ChatPlaceholder = { type: "placeholder"; name: string };
export type ChatItem = ChatMessage | ChatPlaceholder;

/** Any span with OpenTelemetry's setAttribute; the SDK never imports OpenTelemetry itself. */
export interface SpanLike {
  setAttribute(key: string, value: string): unknown;
}

export interface PublicPromptJson {
  name: string;
  version: number;
  type: "text" | "chat";
  prompt: string | ChatItem[];
  config: Record<string, unknown>;
  labels: string[];
  tags: string[];
  commitMessage: string | null;
  isFallback: boolean;
}

export interface PromptInit<P> {
  name: string;
  version: number;
  prompt: P;
  config?: Record<string, unknown>;
  labels?: string[];
  tags?: string[];
  commitMessage?: string | null;
  isFallback?: boolean;
}

// The one variable syntax. The backend, the frontend, and the Python SDK hold the same literal,
// and a repo gate compares the copies.
export const VARIABLE_PATTERN = /\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}/g;

function own(record: Record<string, unknown>, key: string): unknown {
  return Object.hasOwn(record, key) ? record[key] : undefined;
}

function render(template: string, variables: Record<string, unknown>): string {
  return template.replace(VARIABLE_PATTERN, (match: string, name: string) => {
    const value = own(variables, name);
    if (value === undefined) return match;
    return value === null ? "" : String(value);
  });
}

function scan(template: string, seen: string[]): void {
  for (const match of template.matchAll(VARIABLE_PATTERN)) {
    if (!seen.includes(match[1])) seen.push(match[1]);
  }
}

function isPlaceholder(item: ChatItem): item is ChatPlaceholder {
  return item.type === "placeholder";
}

function message(item: unknown, variables: Record<string, unknown>): ChatMessage {
  if (typeof item !== "object" || item === null || !("role" in item) || !("content" in item)) {
    throw new TypeError("each placeholder item must be an object with role and content");
  }
  const entry = item as ChatMessage;
  return { ...entry, content: render(entry.content, variables) };
}

abstract class BasePrompt<P extends string | ChatItem[]> {
  abstract readonly type: "text" | "chat";
  readonly name: string;
  readonly version: number;
  readonly prompt: P;
  readonly config: Record<string, unknown>;
  readonly labels: string[];
  readonly tags: string[];
  readonly commitMessage: string | null;
  readonly isFallback: boolean;

  constructor(init: PromptInit<P>) {
    this.name = init.name;
    this.version = init.version;
    this.prompt = init.prompt;
    this.config = init.config ?? {};
    this.labels = init.labels ?? [];
    this.tags = init.tags ?? [];
    this.commitMessage = init.commitMessage ?? null;
    this.isFallback = init.isFallback ?? false;
  }

  protected abstract templates(): string[];

  /** Variable names in first-seen order, each once. */
  variables(): string[] {
    const seen: string[] = [];
    for (const template of this.templates()) scan(template, seen);
    return seen;
  }

  /** The span attributes that link a call to this version; empty for a fallback. */
  spanAttributes(): Record<string, string> {
    if (this.isFallback) return {};
    return { "tessary.prompt.name": this.name, "tessary.prompt.version": String(this.version) };
  }

  /** Set the link attributes on any object with setAttribute; return the span. */
  link<T extends SpanLike>(span: T): T {
    if (typeof (span as Partial<SpanLike>).setAttribute === "function") {
      for (const [key, value] of Object.entries(this.spanAttributes())) span.setAttribute(key, value);
    }
    return span;
  }

  toJSON(): PublicPromptJson {
    return {
      name: this.name,
      version: this.version,
      type: this.type,
      prompt: this.prompt,
      config: this.config,
      labels: this.labels,
      tags: this.tags,
      commitMessage: this.commitMessage,
      isFallback: this.isFallback,
    };
  }
}

export class TextPrompt extends BasePrompt<string> {
  readonly type = "text" as const;

  protected templates(): string[] {
    return [this.prompt];
  }

  compile(variables: Record<string, unknown> = {}): string {
    return render(this.prompt, variables);
  }
}

export class ChatPrompt extends BasePrompt<ChatItem[]> {
  readonly type = "chat" as const;

  protected templates(): string[] {
    return this.prompt.filter((item) => !isPlaceholder(item)).map((item) => (item as ChatMessage).content);
  }

  /**
   * Compile every message. A placeholder is replaced by `placeholders[name]`, or by an array-valued
   * variable of that name; an unfilled placeholder stays in place.
   */
  compile(variables: Record<string, unknown> = {}, placeholders: Record<string, ChatMessage[]> = {}): ChatItem[] {
    const out: ChatItem[] = [];
    for (const item of this.prompt) {
      if (!isPlaceholder(item)) {
        out.push(message(item, variables));
        continue;
      }
      const fill = own(placeholders, item.name) ?? own(variables, item.name);
      if (Array.isArray(fill)) {
        for (const entry of fill) out.push(message(entry, variables));
      } else {
        out.push({ ...item });
      }
    }
    return out;
  }
}

/** Build a prompt from the `data` of a PublicPrompt envelope. */
export function fromData(data: Record<string, unknown>): TextPrompt | ChatPrompt {
  // A null optional from the wire falls to the constructor's default, as an absent one does.
  const init = {
    name: data.name as string,
    version: data.version as number,
    config: data.config as Record<string, unknown> | undefined,
    labels: data.labels as string[] | undefined,
    tags: data.tags as string[] | undefined,
    commitMessage: data.commitMessage as string | null | undefined,
  };
  if (data.type === "chat") return new ChatPrompt({ ...init, prompt: data.prompt as ChatItem[] });
  return new TextPrompt({ ...init, prompt: data.prompt as string });
}

/** A prompt built from the caller's own text: version 0, never cached, never linked. */
export function fallbackPrompt(name: string, value: string | ChatItem[]): TextPrompt | ChatPrompt {
  if (typeof value === "string") return new TextPrompt({ name, version: 0, prompt: value, isFallback: true });
  return new ChatPrompt({ name, version: 0, prompt: value, isFallback: true });
}
