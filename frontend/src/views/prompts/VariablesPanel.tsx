// SPDX-License-Identifier: Apache-2.0
import type { PromptBody } from "../../api/types";
import { Field, Input, Textarea } from "../../ui";
import { extractPlaceholders, extractVariables } from "./promptCompile";

/** Placeholder values stay raw text: the caller parses them as a JSON array of messages when it compiles. */
export type VariableValues = { vars: Record<string, string>; placeholders: Record<string, string> };

export function VariablesPanel({
  body,
  values,
  onChange,
}: {
  body: PromptBody;
  values: VariableValues;
  onChange: (next: VariableValues) => void;
}) {
  const variables = extractVariables(body);
  const placeholders = extractPlaceholders(body);
  if (variables.length + placeholders.length === 0) {
    return <p className="text-small text-muted">No variables. Write {"{{name}}"} in the body to add one.</p>;
  }
  return (
    <div className="flex flex-col gap-3">
      {variables.map((name) => (
        <Field key={name} label={name}>
          {(p) => (
            <Input
              {...p}
              aria-label={name}
              value={values.vars[name] ?? ""}
              onChange={(e) => onChange({ ...values, vars: { ...values.vars, [name]: e.target.value } })}
            />
          )}
        </Field>
      ))}
      {placeholders.map((name) => (
        <Field key={name} label={`Messages for ${name}`} hint="A JSON array of {role, content} messages.">
          {(p) => (
            <Textarea
              {...p}
              aria-label={`Messages for ${name}`}
              rows={4}
              value={values.placeholders[name] ?? ""}
              onChange={(e) =>
                onChange({ ...values, placeholders: { ...values.placeholders, [name]: e.target.value } })
              }
            />
          )}
        </Field>
      ))}
    </div>
  );
}
