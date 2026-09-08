// SPDX-License-Identifier: Apache-2.0
import type { InputHTMLAttributes, SelectHTMLAttributes, TextareaHTMLAttributes } from "react";
import { forwardRef } from "react";
import { cn } from "./cn";

const BASE =
  "w-full rounded-control bg-surface border border-border text-fg placeholder:text-subtle " +
  "focus:bg-raised focus:border-border-strong outline-none transition-colors";

const SIZED = "h-9 px-2.5 text-small";

type InputProps = InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean };

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { invalid, className, ...rest },
  ref,
) {
  return (
    <input
      ref={ref}
      className={cn(BASE, SIZED, invalid && "border-[color:var(--color-error)]", className)}
      style={{ transitionDuration: "var(--duration-micro)" }}
      {...rest}
    />
  );
});

type TextareaProps = TextareaHTMLAttributes<HTMLTextAreaElement> & { invalid?: boolean };

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { invalid, className, rows = 4, ...rest },
  ref,
) {
  return (
    <textarea
      ref={ref}
      rows={rows}
      className={cn(BASE, "px-2.5 py-2 text-small leading-relaxed font-mono", invalid && "border-[color:var(--color-error)]", className)}
      style={{ transitionDuration: "var(--duration-micro)" }}
      {...rest}
    />
  );
});

type SelectProps = SelectHTMLAttributes<HTMLSelectElement> & { invalid?: boolean };

export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { invalid, className, children, ...rest },
  ref,
) {
  return (
    <select
      ref={ref}
      className={cn(
        BASE,
        SIZED,
        "appearance-none pr-7 bg-no-repeat",
        invalid && "border-[color:var(--color-error)]",
        className,
      )}
      style={{
        transitionDuration: "var(--duration-micro)",
        // %236E6E6E is --primitive-grey-600, the ramp's decorative-icon step. It has to be a
        // literal: a data: URI is its own document and cannot read a custom property from ours.
        // Keep it in sync with tokens.css by hand — it is the one color in src/ that can't.
        backgroundImage:
          "url(\"data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 12 12'><path fill='%236E6E6E' d='M2.5 4.5L6 8l3.5-3.5z'/></svg>\")",
        backgroundPosition: "right 0.5rem center",
        backgroundSize: "12px 12px" }}
      {...rest}
    >
      {children}
    </select>
  );
});
