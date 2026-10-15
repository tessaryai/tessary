// SPDX-License-Identifier: Apache-2.0
import type { ButtonHTMLAttributes, ReactNode } from "react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Check, Copy } from "lucide-react";
import { Button, type ButtonSize, type ButtonVariant } from "./Button";

/**
 * The one copy path. The button itself confirms ("Copied"), since a toast can sit under a modal.
 * `navigator.clipboard` is undefined off a secure origin, such as a self-hosted install over plain
 * http, so `execCommand` is the fallback.
 */

const RESET_MS = 1600;

function legacyCopy(text: string): boolean {
  try {
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.setAttribute("readonly", "");
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

export async function writeClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
  }
  return legacyCopy(text);
}

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
  value: string | (() => string | null | undefined);
  label?: ReactNode;
  copiedLabel?: ReactNode;
  variant?: ButtonVariant;
  size?: ButtonSize;
  icon?: boolean;
  onCopied?: () => void;
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
