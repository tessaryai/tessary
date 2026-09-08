// SPDX-License-Identifier: Apache-2.0
import type { ButtonHTMLAttributes, ReactNode } from "react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Check, Copy } from "lucide-react";
import { Button, type ButtonSize, type ButtonVariant } from "./Button";

/**
 * The one copy-to-clipboard path in the app.
 *
 * Every copy affordance has to answer "did that work?" on the control the pointer is already on —
 * a toast in the corner is a supplement, never the confirmation, and several of these buttons sit
 * inside modals that cover it. So the hook owns the confirmed/reset cycle and every button built on
 * it flips to "Copied" for {@link RESET_MS}.
 *
 * It also owns the fallback. `navigator.clipboard` is undefined on any non-secure origin, which
 * includes a self-hosted Tessary reached over plain http:// — the exact deployment whose whole first
 * run is copying an endpoint and a token out of these screens. Without the `execCommand` path those
 * buttons throw into a floating promise and do nothing at all.
 */

const RESET_MS = 1600;

function legacyCopy(text: string): boolean {
  try {
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.setAttribute("readonly", "");
    // Off-screen but still selectable: `display:none` and `visibility:hidden` are not.
    ta.style.position = "fixed";
    ta.style.top = "0";
    ta.style.left = "-9999px";
    document.body.appendChild(ta);
    const active = document.activeElement as HTMLElement | null;
    ta.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(ta);
    active?.focus?.();
    return ok;
  } catch {
    return false;
  }
}

/** Writes to the clipboard, falling back to `execCommand` off a secure origin. Never throws. */
export async function writeClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* denied, or no clipboard permission — the legacy path may still work */
  }
  return legacyCopy(text);
}

/**
 * `copied` is true for {@link RESET_MS} after a successful write and false after a failed one, so a
 * caller can render "Copied" without owning a timer. `copy` resolves to whether it worked.
 */
export function useCopy(resetMs: number = RESET_MS) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (timer.current !== null) window.clearTimeout(timer.current);
    },
    [],
  );

  const copy = useCallback(
    async (text: string) => {
      const ok = await writeClipboard(text);
      if (!ok) return false;
      setCopied(true);
      if (timer.current !== null) window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => {
        timer.current = null;
        setCopied(false);
      }, resetMs);
      return true;
    },
    [resetMs],
  );

  return { copied, copy };
}

type CopyButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, "value" | "onCopy"> & {
  /** What to put on the clipboard. A thunk for a value that is not ready at render time. */
  value: string | (() => string | null | undefined);
  label?: ReactNode;
  copiedLabel?: ReactNode;
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Set false for the compact fields where the label alone carries it. */
  icon?: boolean;
  /** Fired after a confirmed write — the place for a toast, telemetry or a wizard step. */
  onCopied?: () => void;
  /** Fired when nothing reached the clipboard. Callers that can explain the failure should say so. */
  onCopyFailed?: () => void;
};

export function CopyButton({
  value,
  label = "Copy",
  copiedLabel = "Copied",
  variant = "secondary",
  size = "sm",
  icon = true,
  onCopied,
  onCopyFailed,
  onClick,
  ...rest
}: CopyButtonProps) {
  const { copied, copy } = useCopy();
  return (
    <Button
      type="button"
      variant={variant}
      size={size}
      leadingIcon={
        icon ? (
          copied ? (
            <Check size={13} strokeWidth={2} aria-hidden="true" />
          ) : (
            <Copy size={13} strokeWidth={1.8} aria-hidden="true" />
          )
        ) : undefined
      }
      onClick={(e) => {
        onClick?.(e);
        const text = typeof value === "function" ? value() : value;
        if (text == null || text === "") return;
        void copy(text).then((ok) => (ok ? onCopied?.() : onCopyFailed?.()));
      }}
      {...rest}
    >
      {copied ? copiedLabel : label}
    </Button>
  );
}
