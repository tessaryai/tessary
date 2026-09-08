// SPDX-License-Identifier: Apache-2.0
import type { ButtonHTMLAttributes, ReactNode } from "react";
import { forwardRef } from "react";
import { Spinner } from "./Spinner";
import { cn } from "./cn";

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type ButtonSize = "sm" | "md";

const SIZE: Record<ButtonSize, string> = {
  sm: "h-7 px-2.5 text-small gap-1.5",
  md: "h-9 px-3.5 text-small gap-2",
};

const VARIANT: Record<ButtonVariant, string> = {
  primary:
    "bg-accent text-[color:var(--color-accent-text-on)] hover:bg-accent-hover active:bg-accent-pressed font-medium",
  secondary:
    "bg-raised text-fg border border-border-strong hover:bg-overlay",
  ghost:
    "bg-transparent text-muted hover:bg-hover hover:text-fg",
  danger:
    "bg-transparent text-error border border-border-strong hover:bg-[color:var(--color-error-subtle)]",
};

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  leadingIcon?: ReactNode;
  trailingIcon?: ReactNode;
};

export const Button = forwardRef<HTMLButtonElement, Props>(function Button(
  { variant = "secondary", size = "md", loading, leadingIcon, trailingIcon, children, className, disabled, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      disabled={disabled || loading}
      className={cn(
        "inline-flex items-center justify-center rounded-control whitespace-nowrap select-none",
        "transition-colors disabled:opacity-50 disabled:cursor-not-allowed",
        SIZE[size],
        VARIANT[variant],
        className,
      )}
      style={{ transitionDuration: "var(--duration-micro)" }}
      {...rest}
    >
      {loading ? <Spinner size="sm" /> : leadingIcon}
      {children}
      {!loading && trailingIcon}
    </button>
  );
});

type IconBtnProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
  label: string;
  children: ReactNode;
};

export const IconButton = forwardRef<HTMLButtonElement, IconBtnProps>(function IconButton(
  { variant = "ghost", size = "md", label, children, className, ...rest },
  ref,
) {
  const sq = size === "sm" ? "size-7" : "size-9";
  return (
    <button
      ref={ref}
      aria-label={label}
      title={label}
      className={cn(
        "inline-flex items-center justify-center rounded-control transition-colors",
        sq,
        VARIANT[variant],
        className,
      )}
      style={{ transitionDuration: "var(--duration-micro)" }}
      {...rest}
    >
      {children}
    </button>
  );
});
