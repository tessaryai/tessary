// SPDX-License-Identifier: Apache-2.0
import { CodeXml } from "lucide-react";
import { CopyButton } from "../../ui";

/** The guide's URL renders bright: it is the only part of the prompt a reader can go and check. */
export function PromptBlock({
  label,
  prompt,
  highlight,
  onCopy,
}: {
  label: string;
  prompt: string;
  highlight?: string;
  onCopy: () => void;
}) {
  const parts = highlight ? prompt.split(highlight) : [prompt];
  return (
    <div>
      <div className="flex items-center gap-1.5 text-label uppercase text-muted mb-2.5">
        <CodeXml size={13} strokeWidth={1.8} aria-hidden="true" />
        {label}
      </div>
      <div className="rounded-card border border-border-strong bg-surface p-4">
        <code className="block font-mono text-small text-fg-secondary leading-[1.75] whitespace-pre-wrap wrap-anywhere">
          {parts.map((part, i) => (
            <span key={i}>
              {part}
              {i < parts.length - 1 && <span className="text-fg">{highlight}</span>}
            </span>
          ))}
        </code>
      </div>
      <CopyButton
        value={prompt}
        label="Copy prompt"
        variant="primary"
        size="md"
        className="mt-3 font-medium"
        onCopied={onCopy}
      />
    </div>
  );
}
