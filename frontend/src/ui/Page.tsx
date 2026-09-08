// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { cn } from "./cn";

export type Crumb = { label: ReactNode; to?: string };

export function Breadcrumb({ trail }: { trail: Crumb[] }) {
  if (trail.length === 0) return null;
  return (
    <nav aria-label="Breadcrumb" className="flex items-center gap-1.5 text-small text-muted">
      {trail.map((c, i) => {
        const last = i === trail.length - 1;
        return (
          <span key={i} className="flex items-center gap-1.5">
            {c.to && !last ? (
              <Link to={c.to} className="hover:text-fg transition-colors">
                {c.label}
              </Link>
            ) : (
              <span className={cn(last ? "text-fg" : undefined)}>{c.label}</span>
            )}
            {!last && (
              <ChevronRight size={10} strokeWidth={1.75} aria-hidden="true" className="text-subtle" />
            )}
          </span>
        );
      })}
    </nav>
  );
}

/**
 * The page header — exactly two sanctioned forms, no per-page variants
 * (DESIGN-DIRECTION §6 / sheets/shell.md):
 *
 * **Form A — index** (Triage, Traces, Classifiers, Vitals, Settings):
 * `kicker` = the nav-group name in mono caps, `title` =
 * the noun in sentence case at 28px sans, one dim `subtitle` line, `actions`
 * right-aligned (max one primary + one secondary, size "sm").
 *
 * **Form B — detail** (case, trace): `breadcrumb` above (parent surface
 * → current identifier, IDs truncate the tail never the head); `title` is the
 * identifier — pass `titleMono` for the 22px mono treatment (trace
 * pages); case pages keep the 28px sans sentence title (omit `titleMono`).
 *
 * `eyebrow` is the legacy alias for `kicker` kept for older surfaces.
 */
export function PageHeader({
  kicker,
  eyebrow,
  title,
  titleMono,
  subtitle,
  breadcrumb,
  actions,
  className,
}: {
  kicker?: ReactNode;
  eyebrow?: ReactNode;
  title: ReactNode;
  titleMono?: boolean;
  subtitle?: ReactNode;
  breadcrumb?: Crumb[];
  actions?: ReactNode;
  className?: string;
}) {
  const kick = kicker ?? eyebrow;
  return (
    <header className={cn("flex flex-col gap-3 mb-6", className)}>
      {breadcrumb && breadcrumb.length > 0 && <Breadcrumb trail={breadcrumb} />}
      <div className="flex items-start justify-between gap-6">
        <div className="min-w-0">
          {kick && (
            <div className="font-mono text-label uppercase text-muted mb-1.5">{kick}</div>
          )}
          {titleMono ? (
            <h1 className="font-mono text-h1 text-fg">{title}</h1>
          ) : (
            <h1 className="text-h1 text-fg" style={{ textWrap: "pretty" }}>
              {title}
            </h1>
          )}
          {subtitle && (
            <div className="text-body text-muted mt-1.5" style={{ maxWidth: 620 }}>
              {subtitle}
            </div>
          )}
        </div>
        {actions && <div className="flex items-center gap-2 shrink-0">{actions}</div>}
      </div>
    </header>
  );
}

export function PageBody({
  children,
  size = "wide",
  className,
}: {
  children: ReactNode;
  size?: "narrow" | "wide" | "full";
  className?: string;
}) {
  const max = size === "narrow" ? "max-w-3xl" : size === "wide" ? "max-w-7xl" : "";
  return <div className={cn("px-8 py-8 mx-auto w-full", max, className)}>{children}</div>;
}

export function Section({
  title,
  subtitle,
  actions,
  children,
  className,
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("mb-10", className)}>
      {(title || actions) && (
        <div className="flex items-center justify-between gap-4 mb-3">
          <div>
            {title && <h2 className="text-h2 text-fg">{title}</h2>}
            {subtitle && <p className="text-small text-muted mt-1">{subtitle}</p>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}
