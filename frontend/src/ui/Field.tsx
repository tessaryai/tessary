// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { useId } from "react";
import { cn } from "./cn";

export function Field({
  label,
  hint,
  error,
  required,
  children,
  className,
}: {
  label: ReactNode;
  hint?: ReactNode;
  error?: ReactNode;
  required?: boolean;
  children: (props: { id: string; "aria-describedby"?: string; "aria-invalid"?: boolean }) => ReactNode;
  className?: string;
}) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errId = `${id}-err`;
  const describedBy = [hint && hintId, error && errId].filter(Boolean).join(" ") || undefined;
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <label htmlFor={id} className="text-label uppercase text-muted">
        {label}
        {required && <span className="text-error ml-0.5">*</span>}
      </label>
      {children({ id, "aria-describedby": describedBy, "aria-invalid": Boolean(error) })}
      {hint && !error && (
        <span id={hintId} className="text-small text-subtle">
          {hint}
        </span>
      )}
      {error && (
        <span id={errId} className="text-small text-error">
          {error}
        </span>
      )}
    </div>
  );
}
