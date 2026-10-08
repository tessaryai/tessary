// SPDX-License-Identifier: Apache-2.0
/*
 * Classifiers: the charts of what each classifier detects, per call site and per tool.
 *
 * Until the charts land, the page lists every classifier with a link to its configure page, which is where the old
 * catalog's switch, status, call sites, tuning, detections and reset live now. The open findings moved to Triage.
 */
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { useTenant } from "../../tenant/TenantContext";
import { ErrorNote, LoadingRow, PageHeader, Section } from "../../ui";
import { CONTAINER } from "./shared";
import { FrustrationBanner } from "./FrustrationBanner";
import { FRUSTRATION_DETECTOR } from "./FrustrationEnableModal";

export function ClassifiersPage() {
  const { api, orgSlug, projectSlug } = useTenant();
  const classifiersQ = useQuery({ queryKey: ["classifiers", api.base], queryFn: api.listClassifiers });
  const classifiers = classifiersQ.data ?? [];
  const frustration = classifiers.find((c) => c.detector === FRUSTRATION_DETECTOR);
  const classifiersPath = `/orgs/${orgSlug}/projects/${projectSlug}/classifiers`;

  return (
    <div style={CONTAINER}>
      <PageHeader kicker="Monitor" title="Classifiers" />

      {frustration && <FrustrationBanner classifier={frustration} />}
      {classifiersQ.isLoading && <LoadingRow />}
      {classifiersQ.isError && <ErrorNote error={classifiersQ.error} />}

      {classifiers.length > 0 && (
        <Section title="Configure classifiers">
          <div className="rounded-card border border-border overflow-hidden" style={{ maxWidth: 560 }}>
            <div className="flex flex-col gap-px bg-border">
              {classifiers.map((c) => (
                <Link
                  key={c.id}
                  to={`${classifiersPath}/${encodeURIComponent(c.id)}`}
                  className="flex items-center gap-3 bg-surface hover:bg-hover transition-colors py-3 px-4"
                  style={{ transitionDuration: "var(--duration-micro)" }}
                >
                  <span className="flex-1 min-w-0 truncate text-body text-fg">{c.name}</span>
                  <span className="text-small text-muted">{c.enabled ? "On" : "Off"}</span>
                  <ChevronRight size={12} strokeWidth={1.75} aria-hidden="true" className="text-subtle" />
                </Link>
              ))}
            </div>
          </div>
        </Section>
      )}
    </div>
  );
}
