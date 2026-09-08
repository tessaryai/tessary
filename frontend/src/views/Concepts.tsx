// SPDX-License-Identifier: Apache-2.0
import { PageBody, PageHeader } from "../ui";
import { useTenant } from "../tenant/TenantContext";
import { CONCEPTS, type ConceptId } from "../concepts";

const ORDER: ConceptId[] = [
  "pipeline",
  "call_site",
  "chain",
  "failure_mode",
  "invariant",
  "pack",
  "taxonomy",
  "trace_source",
  "severity",
];

export function Concepts() {
  const { orgSlug, projectSlug } = useTenant();
  return (
    <PageBody size="narrow">
      <PageHeader
        breadcrumb={[
          { label: projectSlug, to: `/orgs/${orgSlug}/projects/${projectSlug}/overview` },
          { label: "Concepts" },
        ]}
        eyebrow="Reference"
        title="Concepts"
        subtitle="Every noun the platform uses, defined once. Same vocabulary as the evals plugin that generated your pipeline."
      />
      <div className="flex flex-col">
        {ORDER.map((id) => {
          const c = CONCEPTS[id];
          return (
            <section
              key={id}
              id={id}
              className="py-6 border-b border-border last:border-b-0 scroll-mt-8">
              <h2 className="text-h2 text-fg mb-2">{c.label}</h2>
              <p className="text-body text-fg mb-3">{c.gloss}</p>
              {c.long && <p className="text-body text-muted">{c.long}</p>}
            </section>
          );
        })}
      </div>
    </PageBody>
  );
}
