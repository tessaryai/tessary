// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { Link } from "react-router-dom";
import type { LabelRef, PromptDetail, PromptVersionView } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { CopyButton, SegmentedControl } from "../../ui";
import { orderLabels } from "./LabelEditor";
import { extractPlaceholders, extractVariables } from "./promptCompile";
import { labelsWithoutLatest } from "./promptFolders";
import { fetchSnippet, type SnippetLanguage } from "./promptSnippets";

export const SDK_DOCS = "https://docs.tessary.ai/prompts/sdk";

const LANGUAGES: { value: SnippetLanguage; label: string }[] = [
  { value: "python", label: "Python" },
  { value: "typescript", label: "TypeScript" },
  { value: "http", label: "HTTP" },
];
const LANGUAGE_NAME: Record<SnippetLanguage, string> = { python: "Python", typescript: "TypeScript", http: "HTTP" };

/** Where a fetch with no selector lands: the version that carries production, or nowhere. */
function productionNote(labels: LabelRef[], version: number): string {
  const production = labels.find((l) => l.label === "production");
  if (!production) return "No version carries production, so a fetch with no label and no version gets a not-found error.";
  if (production.version === version) return "This version carries production, so a fetch with no label and no version gets it.";
  return `production is on version ${production.version}. A fetch with no label and no version gets the version that carries production.`;
}

/** Code that fetches this version, by one of its deployment labels or by its number, in three languages. */
export function UsePromptPanel({ detail, view }: { detail: PromptDetail; view: PromptVersionView }) {
  const { orgSlug, projectSlug } = useTenant();
  const labels = orderLabels(labelsWithoutLatest(view.labels)).filter((l) => view.labels.includes(l));
  const options = [
    ...labels.map((l) => ({ value: `label:${l}`, label: `Label: ${l}` })),
    { value: "version", label: `Version ${view.version}` },
  ];
  const [language, setLanguage] = useState<SnippetLanguage>("python");
  const [by, setBy] = useState(options[0].value);
  const chosen = options.some((o) => o.value === by) ? by : options[0].value;
  const code = fetchSnippet(language, {
    host: window.location.origin,
    name: detail.name,
    type: detail.type,
    version: view.version,
    selector: chosen.startsWith("label:") ? { label: chosen.slice("label:".length) } : { version: view.version },
    variables: extractVariables(view.body),
    placeholders: extractPlaceholders(view.body),
  });

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <SegmentedControl ariaLabel="Language" value={language} onChange={setLanguage} options={LANGUAGES} />
        <div className="flex items-center gap-2">
          <span className="text-small text-muted">Fetch by</span>
          <SegmentedControl ariaLabel="Fetch by" value={chosen} onChange={setBy} options={options} />
        </div>
      </div>
      <section
        aria-label={`${LANGUAGE_NAME[language]} snippet`}
        className="relative rounded-card border border-border bg-bg"
      >
        <div className="absolute right-2 top-2">
          <CopyButton value={code} />
        </div>
        <pre className="m-0 overflow-x-auto p-3 pr-24 font-mono text-code text-fg-secondary">{code}</pre>
      </section>
      <p className="text-small text-muted">{productionNote(detail.labels, view.version)}</p>
      <p className="text-small text-muted">
        A fetch needs a query key (tsy_q_).{" "}
        <Link to={`/orgs/${orgSlug}/projects/${projectSlug}/settings/api-keys`} className="text-link hover:text-link-hover">
          Create an API key
        </Link>
        {" · "}
        <a href={SDK_DOCS} target="_blank" rel="noreferrer" className="text-link hover:text-link-hover">
          Read the SDK guide
        </a>
      </p>
    </div>
  );
}
