// SPDX-License-Identifier: Apache-2.0
// @tessaryai/prompts/otel: automatic span linking through OpenTelemetry context. Needs only the
// optional peer @opentelemetry/api; the main entry never imports this module.
//
// withPrompt(prompt, fn) puts the prompt under a private context key, not baggage, so nothing leaves
// the process. PromptLinkSpanProcessor, added once to your tracer provider, copies it onto every span
// started inside fn, including the model-call spans an auto-instrumented client starts.
import { context, createContextKey, trace, type Context, type Span } from "@opentelemetry/api";

import type { ChatPrompt, SpanLike, TextPrompt } from "./prompts.ts";

const LINK_KEY = createContextKey("tessary.prompt.link");

/** Link the active span to the prompt version and return it; `undefined` when no span is active. */
export function linkActiveSpan(prompt: TextPrompt | ChatPrompt): Span | undefined {
  const span = trace.getSpan(context.active());
  if (span !== undefined) prompt.link(span);
  return span;
}

/**
 * Run `fn` so that every span started inside it, also after an `await`, is linked to the prompt
 * version by `PromptLinkSpanProcessor`. A fallback links nothing, even inside another prompt's block.
 * Needs a registered context manager, as the Node SDK sets up.
 */
export function withPrompt<T>(prompt: TextPrompt | ChatPrompt, fn: () => T): T {
  return context.with(context.active().setValue(LINK_KEY, prompt.spanAttributes()), fn);
}

/**
 * Sets `tessary.prompt.name` and `tessary.prompt.version` on each span started inside `withPrompt`.
 * Duck-typed to the SDK's span processor, so this package needs only the OpenTelemetry API.
 */
export class PromptLinkSpanProcessor {
  // SpanLike and Context only, so the SDK's own Span type of any version is accepted.
  onStart(span: SpanLike, parentContext: Context): void {
    const attributes = parentContext.getValue(LINK_KEY) as Record<string, string> | undefined;
    for (const [key, value] of Object.entries(attributes ?? {})) span.setAttribute(key, value);
  }

  onEnd(): void {}

  async forceFlush(): Promise<void> {}

  async shutdown(): Promise<void> {}
}
