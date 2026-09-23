// SPDX-License-Identifier: Apache-2.0
import { CodeXml } from "lucide-react";
import { CopyButton } from "../../ui";

/**
 * The paste-into-your-agent prompt anatomy: eyebrow + code surface (the linked guide's URL bright) + Copy
 * button. Shared by the connect gate and the Groundedness setup and restart modals.
 *
 * The bright token used to be `tessary.call_site.id`, back when the connect prompt spelled the whole ask
 * out inline. Every prompt is now one sentence pointing at a guide, so the URL is the part a reader's eye
 * should land on: it is the only thing in the sentence they can go and check.
 */
export function PromptBlock({
  label,
  prompt,
  highlight,
  onCopy,
}: {
  label: string;
  prompt: string;
  /** The part of the prompt to render bright, normally the guide's URL. */
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
        <code className="font-mono text-small text-fg-secondary leading-[1.75] whitespace-pre-wrap">
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
