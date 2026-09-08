// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, Code2 } from "lucide-react";
import { ApiError } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";
import { Button, CopyButton, cn, useToast } from "../../ui";

/**
 * Shared "connect a source" UI — the OTLP connect story (forward your traces to
 * Tessary as a second exporter), a config screen with copyable secrets.
 *
 * Single-sourced so the Setup surface and the in-app "Connect a source" modal
 * (Settings → Sources) show the exact same flow. Decoupled from any wizard
 * context: telemetry is an optional `emitAction` callback and toasts are taken
 * from the live context.
 *
 * This is now the ONLY thing a partner has to do (launch G1), so it also has to
 * be doable without asking us (G2): the agent prompt, the raw endpoint and
 * header, AND working exporter configuration in the language they are actually
 * in. The long-form version lives in docs/self-hosting/setup.mdx.
 */

/**
 * `instrument.md` in the repository root, on the public mirror: the agent-facing WORKFLOW for
 * instrumenting a repository — inspect it and its own AGENTS.md-style instructions, confirm the
 * discovered call sites with the user before editing, extend whatever observability already exists
 * rather than bolting on a second stack, then say exactly where the credentials go. It is the
 * SUBSTANCE the two prompts below used to try to carry inline.
 *
 * A pinned `blob/main` URL rather than a raw one: an agent fetches either, and this is the one a
 * human who reads the prompt before pasting it can open and skim.
 */
export const INSTRUMENT_DOC_URL = "https://github.com/tessaryai/tessary/blob/main/instrument.md";

/**
 * The connect prompt (#1227). It is one sentence pointing at {@link INSTRUMENT_DOC_URL}, because a
 * prompt is a bad place to keep a spec: the previous paragraph-long version had to restate the
 * exporter rules AND the `tessary.call_site.id` ask in the copy field itself, could not say
 * anything about WHERE a given repository keeps credentials, and drifted from the docs the moment
 * either changed. The file it names carries all of that, and updates without a frontend release.
 *
 * Deliberately does NOT inline the endpoint or token. They are the two CopyFields directly below
 * this prompt on every surface that renders it, and `instrument.md` §5 tells the agent to ask for
 * them — so the pasted line stays short enough to read, and no secret rides along in a clipboard
 * string that gets pasted into issue trackers and chat logs.
 *
 * Exported so {@link ConnectGate} (the first-run gate) renders the exact same text as this file's
 * own {@link PromptCard}, never a paraphrase.
 */
export const OTLP_PROMPT = `Update the instrumentation using ${INSTRUMENT_DOC_URL} to send traces to Tessary.`;

/**
 * The repair prompt for wait state 2 (spans arriving, none carry a call site) — a narrower ask than
 * {@link OTLP_PROMPT}: the exporter already works by the time this one is shown, so it deep-links
 * the tagging section alone and explicitly says not to touch the exporter.
 *
 * The fragment is GitHub's own slug for `instrument.md`'s "## The call-site tag" heading;
 * renaming that heading breaks the anchor (it degrades to the top of the same file, so the agent
 * still gets correct instructions, just not scoped ones).
 */
export const TAG_REPAIR_PROMPT = `Tessary is receiving my spans but none carry a call site. Tag them by following ${INSTRUMENT_DOC_URL}#the-call-site-tag. Do not change the exporter, it already works.`;

export function OtlpConnect({
  onBack,
  backLabel,
  emitAction,
  arrived = false,
}: {
  onBack?: () => void;
  backLabel?: string;
  emitAction?: (action: string) => void;
  /** Whether a trace has already landed. The pulsing banner is a claim about the project's state,
   *  so it must not sit above a ladder that says traces arrived days ago. */
  arrived?: boolean;
}) {
  const toast = useToast();
  const { token, issue, issuing } = useIngestToken(emitAction);
  const endpoint = (typeof window !== "undefined" ? window.location.origin : "https://your-host") + "/v1/traces";
  return (
    <div>
      {onBack && backLabel !== "" && <BackLink onClick={onBack} label={backLabel} />}
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
      {!arrived && <ListeningBanner />}
    </div>
  );
}

/* ---------------------------------------------------- per-language snippets */

/**
 * The token, or an obvious placeholder. Never a blank: a snippet copied before the token was minted
 * would fail with a 401 and no clue why, so the placeholder has to be loud enough to be caught.
 */
const TOKEN_PLACEHOLDER = "<create a connection token above>";

type Snippet = { id: string; label: string; render: (endpoint: string, token: string) => string };

/**
 * Working exporter configuration per runtime (launch G2 — "in the partner's language").
 *
 * The environment-variable form is FIRST and is the one to reach for: every OTel SDK reads it, it needs
 * no code change, and it is the only form that cannot drift from the SDK's own API. The language
 * snippets exist for codebases that build their exporter programmatically and would otherwise have to
 * translate the env vars themselves.
 *
 * `OTEL_EXPORTER_OTLP_TRACES_*` rather than the unsuffixed variables on purpose: the unsuffixed pair
 * also redirects metrics and logs, which we do not accept, and a partner who set them would be sending
 * us data we drop while wondering why their metrics backend went quiet.
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
 * Issues a project-scoped ingest token on demand. The token is a WRITE-scoped managed API key —
 * ingest (POST /v1/traces) is the only surface an exporter needs, so callers must never hand the
 * broadest (admin) family to something that only pushes spans.
 *
 * <p>{@link OtlpConnect} only ever calls the returned `issue` from the visible "Create a connection
 * token" button — a revisitable settings surface must not mint a fresh key on every render.
 * `ConnectGate` (the first-run gate, #1227) is the one caller that auto-issues on mount instead,
 * because its design has no button for it: the header field is always populated, never a
 * call-to-action. Exported for that one caller; every other consumer of this file keeps using
 * {@link OtlpConnect} whole.
 */
export function useIngestToken(emitAction?: (action: string) => void) {
  const api = useProjectApi();
  const toast = useToast();
  const qc = useQueryClient();
  const [token, setToken] = useState<string | null>(null);
  const m = useMutation({
    mutationFn: () => api.createApiKey({ name: "OTLP ingest", scope: "write" }),
    onSuccess: (res) => {
      setToken(res.plaintext);
      emitAction?.("issue_token");
      // Minting an ingest token means data is about to flow into the substrate via the OTLP ingest path.
      // Materialize the substrate-backed "sdk" source (idempotent server-side via ensureSdkSource) so the
      // ingested telemetry shows up as a selectable dataset source — without this the token leads nowhere.
      api
        .createSource({ provider: "sdk", name: "Tessary SDK", baseUrl: "tessary://sdk", credentials: {} })
        .then(() => qc.invalidateQueries({ queryKey: ["sources"] }))
        .catch(() => {
          /* best-effort: a failure here must not block showing the token */
        });
    },
    onError: (err) => toast.error("Could not create token", err instanceof ApiError ? err.message : String(err)),
  });
  return { token, issue: () => m.mutate(), issuing: m.isPending };
}

// ---- shared atoms ------------------------------------------------------------

export function CodingAgentLabel() {
  return (
    <span className="inline-flex items-center gap-1.5 text-label uppercase text-muted mb-2.5">
      <Code2 size={13} strokeWidth={1.7} aria-hidden="true" />
      Coding agent
    </span>
  );
}

export function PromptCard({ prompt, onCopy }: { prompt: string; onCopy: () => void }) {
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

/**
 * Exported for {@link ConnectGate}'s Endpoint/Header fields — same read-only-plus-copy shape.
 *
 * `copyValue` exists for the one field whose display is not what you want on the clipboard:
 * ConnectGate elides the bearer token on screen and has to copy the whole thing.
 */
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

export function BackLink({ onClick, label = "Back to sources" }: { onClick: () => void; label?: string }) {
  return (
    <button onClick={onClick} className="inline-flex items-center gap-1.5 text-small text-muted hover:text-fg mb-2.5">
      <ChevronLeft size={13} strokeWidth={1.7} aria-hidden="true" />
      {label}
    </button>
  );
}
