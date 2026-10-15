// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, Code2 } from "lucide-react";
import { ApiError } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";
import { Button, CopyButton, cn, useToast } from "../../ui";

/** `blob/main`, not raw: an agent fetches either, and a human can open and skim this one. */
export const INSTRUMENT_DOC_URL = "https://github.com/tessaryai/tessary/blob/main/instrument.md";

/**
 * One sentence pointing at the doc, which updates without a frontend release. The endpoint and token are
 * never inlined: they are the CopyFields below, and a secret must not ride in a pasted string.
 */
export const OTLP_PROMPT = `Update the instrumentation using ${INSTRUMENT_DOC_URL} to send traces to Tessary.`;

/**
 * The fragment is GitHub's slug for instrument.md's "## The call-site tag"; renaming the heading
 * degrades it to the top of the file.
 */
export const TAG_REPAIR_PROMPT = `Tessary is receiving my spans but none carry a call site. Tag them by following ${INSTRUMENT_DOC_URL}#the-call-site-tag. Do not change the exporter, it already works.`;

export function OtlpConnect({ onBack, backLabel }: { onBack: () => void; backLabel: string }) {
  const toast = useToast();
  const { token, issue, issuing } = useIngestToken();
  const endpoint = window.location.origin + "/v1/traces";
  return (
    <div>
      <BackLink onClick={onBack} label={backLabel} />
      <h3 className="mb-1.5 text-h2 text-fg">Forward your OpenTelemetry traces</h3>
      <p className="mb-4 text-small text-muted leading-relaxed">
        Add Tessary as a second OpenTelemetry exporter. Your existing one keeps working, and traces go to
        both. Paste the prompt into your coding agent — it reads our instrumentation guide and asks you for
        the endpoint and header below — or wire it up by hand.
      </p>
      <CodingAgentLabel />
      <PromptCard prompt={OTLP_PROMPT} onCopy={() => toast.success("Prompt copied")} />
      <div className="mt-3">
        <CopyField label="Endpoint" value={endpoint} onCopy={() => toast.success("Copied")} />
      </div>
      <div className="mt-3">
        {token ? (
          <CopyField label="Header" value={`Authorization: Bearer ${token}`} onCopy={() => toast.success("Copied")} />
        ) : (
          <div>
            <FieldLabel>Header</FieldLabel>
            <Button variant="secondary" size="sm" loading={issuing} onClick={issue}>
              Create a connection token
            </Button>
          </div>
        )}
      </div>
      <ExporterSnippets endpoint={endpoint} token={token} />
      <ListeningBanner />
    </div>
  );
}

/** Never blank: a snippet copied before the token existed would 401 with no clue why. */
const TOKEN_PLACEHOLDER = "<create a connection token above>";

type Snippet = { id: string; label: string; render: (endpoint: string, token: string) => string };

/**
 * The env-var form leads: every OTel SDK reads it and it cannot drift. The TRACES_-suffixed variables,
 * because the unsuffixed pair also redirects metrics and logs, which we drop.
 */
const SNIPPETS: Snippet[] = [
  {
    id: "env",
    label: "Environment",
    render: (endpoint, token) => `# Any OpenTelemetry SDK reads these. No code change needed.
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=${endpoint}
OTEL_EXPORTER_OTLP_TRACES_HEADERS=Authorization=Bearer ${token}
OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=http/protobuf`,
  },
  {
    id: "python",
    label: "Python",
    render: (endpoint, token) => `from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

# Added alongside your existing processor. Spans go to both.
provider: TracerProvider = trace.get_tracer_provider()
provider.add_span_processor(
    BatchSpanProcessor(
        OTLPSpanExporter(
            endpoint="${endpoint}",
            headers={"Authorization": "Bearer ${token}"},
        )
    )
)`,
  },
  {
    id: "node",
    label: "TypeScript",
    render: (endpoint, token) => `import { BatchSpanProcessor } from "@opentelemetry/sdk-trace-base";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-http";

// Added alongside your existing processor. Spans go to both.
provider.addSpanProcessor(
  new BatchSpanProcessor(
    new OTLPTraceExporter({
      url: "${endpoint}",
      headers: { Authorization: "Bearer ${token}" },
    }),
  ),
);`,
  },
  {
    id: "go",
    label: "Go",
    render: (endpoint, token) => `exporter, err := otlptracehttp.New(ctx,
    otlptracehttp.WithEndpointURL("${endpoint}"),
    otlptracehttp.WithHeaders(map[string]string{
        "Authorization": "Bearer ${token}",
    }),
)
if err != nil {
    return err
}
// Added alongside your existing processor. Spans go to both.
provider.RegisterSpanProcessor(sdktrace.NewBatchSpanProcessor(exporter))`,
  },
  {
    id: "collector",
    label: "Collector",
    render: (endpoint, token) => `# Fan out from a collector you already run. Nothing in your app changes.
exporters:
  otlphttp/tessary:
    traces_endpoint: ${endpoint}
    headers:
      Authorization: "Bearer ${token}"

service:
  pipelines:
    traces:
      exporters: [your_existing_exporter, otlphttp/tessary]`,
  },
];

function ExporterSnippets({ endpoint, token }: { endpoint: string; token: string | null }) {
  const toast = useToast();
  const [active, setActive] = useState(SNIPPETS[0].id);
  const snippet = SNIPPETS.find((s) => s.id === active) ?? SNIPPETS[0];
  const body = snippet.render(endpoint, token ?? TOKEN_PLACEHOLDER);

  return (
    <div className="mt-4">
      <FieldLabel>Or wire it up in code</FieldLabel>
      <div className="flex flex-wrap gap-1 mb-2" role="tablist" aria-label="Exporter language">
        {SNIPPETS.map((s) => (
          <button
            key={s.id}
            type="button"
            role="tab"
            aria-selected={s.id === active}
            onClick={() => setActive(s.id)}
            className={cn(
              "rounded-control px-2.5 py-1 text-small transition-colors",
              s.id === active
                ? "bg-selected text-fg"
                : "text-muted hover:text-fg hover:bg-hover",
            )}
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            {s.label}
          </button>
        ))}
      </div>
      <div className="rounded-card border border-border-strong bg-surface">
        <pre className="p-3.5 overflow-x-auto font-mono text-small text-fg leading-relaxed">
          <code>{body}</code>
        </pre>
        <div className="flex justify-end border-t border-border px-2 py-1.5">
          <CopyButton
            value={body}
            className="font-medium"
            onCopied={() => toast.success("Copied")}
            onCopyFailed={() => toast.error("Could not copy", "Select the snippet and copy it manually.")}
          />
        </div>
      </div>
      {token == null && (
        <p className="mt-1.5 text-small text-subtle">
          Create a connection token above and this fills itself in.
        </p>
      )}
    </div>
  );
}

/**
 * Write-scoped: ingest is all an exporter needs. Issued only from a visible control, never on mount,
 * which left live keys nobody asked for.
 */
export function useIngestToken() {
  const api = useProjectApi();
  const toast = useToast();
  const qc = useQueryClient();
  const [token, setToken] = useState<string | null>(null);
  const m = useMutation({
    mutationFn: () => api.createApiKey({ name: "OTLP ingest", scope: "write" }),
    onSuccess: (res) => {
      setToken(res.plaintext);
      // Materialize the "sdk" source (idempotent server-side) so the ingested telemetry has somewhere to show.
      api
        .createSource({ provider: "sdk", name: "Tessary SDK", baseUrl: "tessary://sdk", credentials: {} })
        .then(() => qc.invalidateQueries({ queryKey: ["sources"] }))
        .catch(() => {
          /* best-effort: a failure here must not block showing the token */
        });
    },
    onError: (err) => toast.error("Could not create token", err instanceof ApiError ? err.message : String(err)),
  });
  return {
    token,
    issue: () => m.mutate(),
    issueAsync: async () => (await m.mutateAsync()).plaintext,
    issuing: m.isPending,
  };
}

function CodingAgentLabel() {
  return (
    <span className="inline-flex items-center gap-1.5 text-label uppercase text-muted mb-2.5">
      <Code2 size={13} strokeWidth={1.7} aria-hidden="true" />
      Coding agent
    </span>
  );
}

function PromptCard({ prompt, onCopy }: { prompt: string; onCopy: () => void }) {
  return (
    <div className="flex flex-col gap-2.5 rounded-card border border-border-strong bg-surface p-3.5">
      <code className="font-mono text-small text-fg leading-relaxed">{prompt}</code>
      <div className="flex justify-end">
        <CopyButton value={prompt} label="Copy prompt" variant="primary" onCopied={onCopy} />
      </div>
    </div>
  );
}

function FieldLabel({ children }: { children: React.ReactNode }) {
  return <div className="text-label uppercase text-muted mb-1.5">{children}</div>;
}

/** `copyValue` is for ConnectGate, which elides the bearer token on screen but copies the whole thing. */
export function CopyField({
  label,
  value,
  copyValue,
  onCopy,
}: {
  label: string;
  value: string;
  copyValue?: string | null;
  onCopy: () => void;
}) {
  return (
    <div>
      <FieldLabel>{label}</FieldLabel>
      <div className="flex items-center gap-2 rounded-control border border-border-strong bg-surface px-2.5 py-2">
        <code className="flex-1 font-mono text-small text-fg overflow-hidden text-ellipsis whitespace-nowrap">{value}</code>
        <CopyButton
          value={() => (copyValue === undefined ? value : copyValue)}
          className="font-medium flex-none"
          onCopied={onCopy}
        />
      </div>
    </div>
  );
}

export function ListeningBanner({ label = "Listening for your first trace." }: { label?: string }) {
  return (
    <div className="mt-3.5 flex items-center gap-2.5 rounded-card px-3 py-2.5" style={{ background: "var(--color-accent-subtle)" }}>
      <span className="relative size-2.5 flex-none">
        <span className="absolute inset-0 rounded-pill bg-accent" style={{ animation: "tess-ag-ping 2s var(--ease-enter) infinite" }} />
        <span className="relative block size-2.5 rounded-pill bg-accent" />
      </span>
      <span className="text-small text-fg">
        {label} <span className="text-muted">This screen updates itself.</span>
      </span>
    </div>
  );
}

function BackLink({ onClick, label }: { onClick: () => void; label: string }) {
  return (
    <button onClick={onClick} className="inline-flex items-center gap-1.5 text-small text-muted hover:text-fg mb-2.5">
      <ChevronLeft size={13} strokeWidth={1.7} aria-hidden="true" />
      {label}
    </button>
  );
}
