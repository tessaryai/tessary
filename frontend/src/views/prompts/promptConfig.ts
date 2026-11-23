// SPDX-License-Identifier: Apache-2.0
import type { PromptConfig } from "../../api/types";

const CONFIG_ERROR = "Config must be a JSON object.";

/** Blank text is an empty config; anything but a JSON object is refused. */
export function parseConfig(text: string): { ok: true; value: PromptConfig } | { ok: false; error: string } {
  if (!text.trim()) return { ok: true, value: {} };
  try {
    const value: unknown = JSON.parse(text);
    if (value !== null && typeof value === "object" && !Array.isArray(value)) return { ok: true, value: value as PromptConfig };
  } catch {
    // Falls through to the one message: the parser's own wording names a column, not a fix.
  }
  return { ok: false, error: CONFIG_ERROR };
}
