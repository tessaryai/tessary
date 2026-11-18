// SPDX-License-Identifier: Apache-2.0
import { ArrowDown, ArrowUp, X } from "lucide-react";
import type { PromptBody, PromptChatBody, PromptType } from "../../api/types";
import { Badge, Button, Field, IconButton, Input, Select, Textarea } from "../../ui";
import { isPlaceholder, PLACEHOLDER_NAME } from "./promptCompile";
import { Note } from "./promptBits";

/** `body` is about the whole body, `rows[i]` about item i. */
export type BodyErrors = { body: string | null; rows: (string | null)[] };
export const NO_BODY_ERRORS: BodyErrors = { body: null, rows: [] };

const ROLES = ["system", "user", "assistant"];

export function validateBody(body: PromptBody): BodyErrors {
  if (typeof body === "string") return { body: body.trim() ? null : "Enter the prompt text.", rows: [] };
  const rows = body.map((e) =>
    isPlaceholder(e)
      ? PLACEHOLDER_NAME.test(e.name)
        ? null
        : "A placeholder name starts with a letter or _ and has only letters, digits and _."
      : e.content.trim()
        ? null
        : "Enter the message content.",
  );
  return { body: body.some((e) => !isPlaceholder(e)) ? null : "Add at least one message.", rows };
}

export function hasBodyErrors(errors: BodyErrors): boolean {
  return errors.body !== null || errors.rows.some((r) => r !== null);
}

/** Shared by the new-prompt and new-version modals and the playground. */
export function PromptBodyEditor({
  type,
  value,
  onChange,
  errors,
}: {
  type: PromptType;
  value: PromptBody;
  onChange: (next: PromptBody) => void;
  errors: BodyErrors;
}) {
  if (type === "text") {
    return (
      <div className="flex flex-col gap-1.5">
        <Field label="Prompt text" hint="Write a variable as {{name}}.">
          {(p) => <Textarea {...p} rows={10} value={value as string} onChange={(e) => onChange(e.target.value)} />}
        </Field>
        {errors.body && <Note tone="error">{errors.body}</Note>}
      </div>
    );
  }
  const items = value as PromptChatBody;
  const set = (i: number, entry: PromptChatBody[number]) => onChange(items.map((e, j) => (j === i ? entry : e)));
  const move = (i: number, to: number) => {
    const next = [...items];
    [next[i], next[to]] = [next[to], next[i]];
    onChange(next);
  };
  return (
    <div className="flex flex-col gap-2">
      {items.map((entry, i) => (
        <div key={i} className="flex flex-col gap-1.5">
          <div className="flex gap-2 rounded-card border border-border bg-raised p-2.5">
            {isPlaceholder(entry) ? (
              <div className="flex flex-1 items-center gap-2">
                <Badge>placeholder</Badge>
                <Input
                  aria-label={`Placeholder name ${i + 1}`}
                  value={entry.name}
                  onChange={(e) => set(i, { type: "placeholder", name: e.target.value })}
                />
              </div>
            ) : (
              <div className="flex flex-1 items-start gap-2">
                <Select
                  aria-label={`Role of message ${i + 1}`}
                  className="w-[120px] shrink-0"
                  value={entry.role}
                  onChange={(e) => set(i, { role: e.target.value, content: entry.content })}
                >
                  {[...new Set([...ROLES, entry.role])].map((r) => (
                    <option key={r} value={r}>
                      {r}
                    </option>
                  ))}
                </Select>
                <Textarea
                  aria-label={`Content of message ${i + 1}`}
                  rows={3}
                  value={entry.content}
                  onChange={(e) => set(i, { role: entry.role, content: e.target.value })}
                />
              </div>
            )}
            <div className="flex flex-col gap-1">
              <IconButton size="sm" label={`Move item ${i + 1} up`} disabled={i === 0} onClick={() => move(i, i - 1)}>
                <ArrowUp size={13} strokeWidth={1.75} aria-hidden="true" />
              </IconButton>
              <IconButton
                size="sm"
                label={`Move item ${i + 1} down`}
                disabled={i === items.length - 1}
                onClick={() => move(i, i + 1)}
              >
                <ArrowDown size={13} strokeWidth={1.75} aria-hidden="true" />
              </IconButton>
              <IconButton size="sm" label={`Remove item ${i + 1}`} onClick={() => onChange(items.filter((_, j) => j !== i))}>
                <X size={13} strokeWidth={1.75} aria-hidden="true" />
              </IconButton>
            </div>
          </div>
          {errors.rows[i] && <Note tone="error">{errors.rows[i]}</Note>}
        </div>
      ))}
      <div className="flex items-center gap-2">
        <Button size="sm" onClick={() => onChange([...items, { role: "user", content: "" }])}>
          Add message
        </Button>
        <Button size="sm" onClick={() => onChange([...items, { type: "placeholder", name: "" }])}>
          Add placeholder
        </Button>
      </div>
      {errors.body && <Note tone="error">{errors.body}</Note>}
      <p className="text-small text-subtle">
        Write a variable as {"{{name}}"}. A placeholder takes a list of messages when the prompt is compiled.
      </p>
    </div>
  );
}
