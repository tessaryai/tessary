// SPDX-License-Identifier: Apache-2.0
// The pieces the MCP token and API key pages share: a list with nothing in it yet, the dialog that shows a
// just-issued secret once, and a row's Revoke button.
import type { ReactNode } from "react";
import { AlertTriangle, KeyRound } from "lucide-react";
import { Button, CopyButton, IconButton, useToast } from "../../ui";

function KeyGlyph({ size = 20 }: { size?: number }) {
  return <KeyRound size={size} strokeWidth={1.5} aria-hidden="true" />;
}

export function NoSecrets({ title, children, action, onAction }: {
  title: string;
  children: ReactNode;
  action: string;
  onAction: () => void;
}) {
  return (
    <div className="flex flex-col items-center text-center py-14">
      <span className="flex size-[42px] items-center justify-center rounded-card bg-surface border border-border-strong text-muted">
        <KeyGlyph />
      </span>
      <div className="text-h3 text-fg mt-4">{title}</div>
      <p className="text-small text-muted mt-2 max-w-sm">{children}</p>
      <Button variant="primary" className="mt-5" onClick={onAction}>
        {action}
      </Button>
    </div>
  );
}

export function IssuedTitle({ children }: { children: ReactNode }) {
  return (
    <span className="flex items-center gap-2.5">
      <span className="flex size-6 items-center justify-center rounded-pill bg-accent-subtle border border-accent-edge text-accent">
        <KeyGlyph size={13} />
      </span>
      <span>{children}</span>
    </span>
  );
}

export function IssuedFooter({ noun, onDone }: { noun: string; onDone: () => void }) {
  return (
    <div className="flex w-full items-center gap-3">
      <Button variant="primary" onClick={onDone}>
        Done
      </Button>
      <span className="ml-auto text-label text-subtle">Closing without copying loses the {noun}.</span>
    </div>
  );
}

/** `lost` says what to do instead, since only a hash is kept. */
export function IssuedSecret({ plaintext, noun, lost }: { plaintext: string; noun: string; lost: string }) {
  const toast = useToast();
  return (
    <div>
      <div className="text-label uppercase text-muted">Copy it now. It won't be shown again.</div>
      <div className="mt-2.5 flex items-center gap-2.5 rounded-control border border-accent-edge bg-bg px-3.5 py-3">
        <span className="flex-1 min-w-0 font-mono text-small text-fg truncate">{plaintext}</span>
        <CopyButton
          value={plaintext}
          size="sm"
          variant="primary"
          className="flex-none"
          onCopyFailed={() => toast.error("Could not copy", `Copy the ${noun} manually.`)}
        />
      </div>
      <div className="mt-3.5 flex items-start gap-2.5 rounded-control border border-warning bg-warning-subtle px-3.5 py-2.5">
        <AlertTriangle size={14} strokeWidth={1.5} aria-hidden="true" className="mt-px flex-none text-warning" />
        <span className="text-label text-fg leading-snug">
          Tessary stores only a hash. If you lose this, {lost}. There's no recovery.
        </span>
      </div>
    </div>
  );
}

export function RevokeButton({ name, onRevoke }: { name: string; onRevoke: () => void }) {
  return (
    <IconButton
      label={`Revoke ${name}`}
      size="sm"
      onClick={onRevoke}
      className="text-error hover:text-error hover:bg-[color:var(--color-error-subtle)] px-2 w-auto">
      <span className="text-label font-medium">Revoke</span>
    </IconButton>
  );
}
