// SPDX-License-Identifier: Apache-2.0
import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "./cn";

type CardProps = HTMLAttributes<HTMLDivElement> & {
  children: ReactNode;
};

export function Card({ className, children, ...rest }: CardProps) {
  return (
    <div className={cn("rounded-card bg-surface", className)} {...rest}>
      {children}
    </div>
  );
}
