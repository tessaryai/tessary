// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useProjectApi, useTenant } from "../tenant/TenantContext";
import { ApiError, type IngestionSource } from "../api/types";
import { SourceGlyph } from "./components/SourcesPreview";
import {
  Badge,
  Button,
  ConceptPopover,
  EmptyState,
  Modal,
  PageBody,
  PageHeader,
  Spinner,
} from "../ui";
import { OtlpConnect } from "./components/SourceConnect";

/** Telemetry binds to call sites by the `tessary.call_site.id` the SDK stamps, not a server-side mapping. */
export function Sources() {
  const api = useProjectApi();
  const nav = useNavigate();
  const { orgSlug, projectSlug } = useTenant();
  const sources = useQuery({ queryKey: ["sources", api.base], queryFn: api.listSources });
  const [showNew, setShowNew] = useState(false);

  return (
    <PageBody>
      <PageHeader
        eyebrow="Data & ingestion"
        title={<ConceptPopover concept="trace_source">Sources</ConceptPopover>}
        subtitle="Forward your OpenTelemetry traces to Tessary so the classifiers can watch them."
        actions={
          <div className="flex items-center gap-2">
            <Button
              variant="secondary"
              onClick={() => nav(`/orgs/${orgSlug}/projects/${projectSlug}/settings/import`)}
            >
              Import bundle
            </Button>
            {sources.data && sources.data.length > 0 && (
              <Button variant="primary" onClick={() => setShowNew(true)}>
                Connect source
              </Button>
            )}
          </div>
        }
      />

      {sources.isLoading && (
        <div className="flex items-center gap-2 text-small text-muted">
          <Spinner size="sm" /> Loading sources…
        </div>
      )}
      {sources.isError && (
        <p className="text-small text-error">
          <code className="font-mono">{(sources.error as ApiError).code}</code>:{" "}
          {(sources.error as ApiError).message}
        </p>
      )}

      {sources.data && sources.data.length === 0 && (
        <EmptyState
          title="No sources yet"
          body="Connect one to start receiving traces. A pull source is optional: if an OpenTelemetry exporter already points at Tessary, your traces are arriving without one. This page lists the sources Tessary pulls from."
          action={
            <Button variant="primary" onClick={() => setShowNew(true)}>
              Connect source
            </Button>
          }
        />
      )}

      {sources.data && sources.data.length > 0 && (
        <div className="flex flex-col gap-2">
          {sources.data.map((s) => (
            <SourceRow key={s.id} source={s} />
          ))}
          <button
            type="button"
            onClick={() => setShowNew(true)}
            className="flex items-center justify-center gap-2 rounded-card border border-dashed border-border-strong bg-transparent px-4 py-3.5 text-small text-muted transition-colors hover:text-fg"
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            <PlusIcon />
            Connect source
          </button>
        </div>
      )}

      <NewSourceModal open={showNew} onClose={() => setShowNew(false)} />
    </PageBody>
  );
}

function SourceRow({ source }: { source: IngestionSource }) {
  return (
    <div className="flex w-full items-center gap-3.5 rounded-card bg-surface px-4 py-3.5 text-left">
      <SourceGlyph provider={source.provider} />
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2 text-small text-fg">
          {source.name}
          <Badge tone="accent">{source.provider}</Badge>
        </span>
        <span className="mt-1 block truncate font-mono text-label text-muted">{source.baseUrl}</span>
      </span>
      <span className="flex items-center gap-1.5 text-label text-success">
        <span className="size-1.5 rounded-pill bg-success" aria-hidden="true" />
        Connected
      </span>
    </div>
  );
}

function NewSourceModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Connect a source"
      subtitle="Send traces to Tessary so the classifiers can watch them."
      size="md"
      footer={
        <Button variant="secondary" onClick={onClose}>
          Done
        </Button>
      }
    >
      <OtlpConnect onBack={onClose} backLabel="Cancel" />
    </Modal>
  );
}

function PlusIcon() {
  return <Plus size={13} strokeWidth={1.75} aria-hidden="true" />;
}
