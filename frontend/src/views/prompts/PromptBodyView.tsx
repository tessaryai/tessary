// SPDX-License-Identifier: Apache-2.0
import type { PromptBody, PromptChatBody, PromptType } from "../../api/types";
import { Badge } from "../../ui";
import { isPlaceholder, tokenizeVariables } from "./promptCompile";

function Tokenized({ text }: { text: string }) {
  return (
    <pre className="m-0 whitespace-pre-wrap font-mono text-code text-fg-secondary">
      {tokenizeVariables(text).map((t, i) =>
        t.kind === "variable" ? (
          <span key={i} data-variable={t.name} className="rounded-micro bg-accent-subtle px-0.5 text-fg">
            {t.text}
          </span>
        ) : (
          t.text
        ),
      )}
    </pre>
  );
}

const BLOCK = "rounded-card border border-border bg-bg p-3";

export function PromptBodyView({ type, body }: { type: PromptType; body: PromptBody }) {
  if (type === "text") {
    return body === "" ? (
      <p className="text-small text-muted">This version has an empty body.</p>
    ) : (
      <div className={BLOCK}>
        <Tokenized text={body as string} />
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-2">
      {(body as PromptChatBody).map((entry, i) =>
        isPlaceholder(entry) ? (
          <div key={i} className={BLOCK}>
            <Badge>placeholder: {entry.name}</Badge>
          </div>
        ) : (
          <div key={i} className={`${BLOCK} flex flex-col gap-1.5`}>
            <div className="text-label uppercase text-subtle">{entry.role}</div>
            <Tokenized text={entry.content} />
          </div>
        ),
      )}
    </div>
  );
}
