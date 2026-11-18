// SPDX-License-Identifier: Apache-2.0
import type { PromptConfig } from "../../api/types";
import { Field, Textarea } from "../../ui";
import { Note } from "./promptBits";

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

export function ConfigEditor({
  value,
  onChange,
  error,
}: {
  value: string;
  onChange: (text: string) => void;
  error: string | null;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Field label="Config" hint="JSON passed to your code with the prompt, for example model or temperature.">
        {(p) => (
          <Textarea {...p} aria-label="Config JSON" rows={6} value={value} onChange={(e) => onChange(e.target.value)} />
        )}
      </Field>
      {error && <Note tone="error">{error}</Note>}
    </div>
  );
}
