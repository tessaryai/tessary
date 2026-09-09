// SPDX-License-Identifier: Apache-2.0
import { createContext, useContext, useMemo, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { JsonView } from "@uiw/react-json-view";
import { cn, useCopy } from "../../ui";

/* -------------------------------------------------------------------------- */
/* Public surface                                                             */
/* -------------------------------------------------------------------------- */

export function PayloadViewer({
  payload,
  label,
  maxHeight = 320,
  fill = false,
}: {
  payload: string | null;
  label?: string;
  maxHeight?: number;
  fill?: boolean;
}) {
  const [mode, setMode] = useState<"formatted" | "raw">("formatted");
  const { copied, copy } = useCopy();
  const parsed = useMemo(() => parsePayload(payload), [payload]);

  if (payload == null || payload === "") {
    return (
      <div className={fill ? "flex h-full flex-col min-h-0" : undefined}>
        {label && <PanelLabel>{label}</PanelLabel>}
        <span className="italic text-subtle text-small">(none)</span>
      </div>
    );
  }

  return (
    <div className={fill ? "flex h-full flex-col min-h-0" : undefined}>
      <div className={cn("flex items-center justify-between gap-2 mb-1", fill && "shrink-0")}>
        {label ? <PanelLabel>{label}</PanelLabel> : <span />}
        <div className="flex items-center gap-1">
          <Segmented
            value={mode}
            onChange={setMode}
            options={[
              { id: "formatted", label: "Formatted" },
              { id: "raw", label: "Raw" },
            ]}
          />
          <button
            type="button"
            onClick={() => void copy(payload)}
            className="text-label uppercase text-muted hover:text-fg px-1.5 py-0.5 transition-colors"
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            {copied ? "Copied" : "Copy"}
          </button>
        </div>
      </div>
      <div
        className={cn("overflow-auto", fill && "flex-1 min-h-0")}
        style={fill ? undefined : { maxHeight }}
      >
        {mode === "raw" ? (
          <pre className="font-mono whitespace-pre-wrap text-fg text-small">{payload}</pre>
        ) : (
          <RenderParsed parsed={parsed} />
        )}
      </div>
    </div>
  );
}

/**
 * Input / Output as a vertical split — two bordered panels, ~50/50 by default,
 * each scrolling internally, with a draggable divider to re-split. Fills its
 * parent's height (parent must be a min-h-0 flex child).
 */
export function SpanIO({ input, output }: { input: string | null; output: string | null }) {
  const [topPct, setTopPct] = useState(50);
  const rootRef = useRef<HTMLDivElement>(null);

  const startDrag = (e: React.PointerEvent<HTMLDivElement>) => {
    e.preventDefault();
    const handle = e.currentTarget;
    handle.setPointerCapture(e.pointerId);
    const move = (ev: PointerEvent) => {
      const root = rootRef.current;
      if (!root) return;
      const rect = root.getBoundingClientRect();
      const pct = ((ev.clientY - rect.top) / rect.height) * 100;
      setTopPct(Math.min(80, Math.max(20, pct)));
    };
    const up = () => {
      handle.releasePointerCapture?.(e.pointerId);
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  };

  return (
    <div ref={rootRef} className="flex h-full flex-col min-h-0">
      <div className="min-h-0" style={{ height: `${topPct}%` }}>
        <IOCard label="Input" payload={input} />
      </div>
      <div
        onPointerDown={startDrag}
        role="separator"
        aria-orientation="horizontal"
        className="shrink-0 h-1.5 my-1 cursor-row-resize rounded-control bg-border hover:bg-accent transition-colors"
        style={{ transitionDuration: "var(--duration-micro)" }}
      />
      <div className="flex-1 min-h-0">
        <IOCard label="Output" payload={output} />
      </div>
    </div>
  );
}

function IOCard({ label, payload }: { label: string; payload: string | null }) {
  return (
    <div className="h-full flex flex-col min-h-0 rounded-card border border-border bg-surface overflow-hidden p-3">
      <PayloadViewer fill label={label} payload={payload} />
    </div>
  );
}

function RenderParsed({ parsed }: { parsed: Parsed }) {
  if (parsed.kind === "chat") return <Conversation messages={parsed.messages} />;
  if (parsed.kind === "json") return <JsonTree value={parsed.value} collapsed={3} />;
  return <Markdown>{parsed.text}</Markdown>;
}

/**
 * Render a raw payload string the same way {@link PayloadViewer}'s formatted mode
 * does (chat → conversation, json → tree, else markdown), but with no chrome:
 * no label, no segmented control, no scroll box. Shared by the trace inspector so
 * span input/output bodies render exactly like the payload panel. When `payload`
 * is empty it renders a quiet "(none)".
 */
export function PayloadBody({ payload }: { payload: string | null }) {
  const parsed = useMemo(() => parsePayload(payload), [payload]);
  if (payload == null || payload === "") {
    return <span className="italic text-subtle text-small">(none)</span>;
  }
  return <RenderParsed parsed={parsed} />;
}

/** Re-exported parsing + tree primitives so sibling trace components share one renderer. */
export { JsonTree, Conversation, parsePayload };
export type { ChatMessage, Parsed };

export function Markdown({ children }: { children: string }) {
  return (
    <div className="payload-md">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>
    </div>
  );
}

export function formatMetaValue(v: unknown): { short: string; full: string } {
  if (v == null) return { short: "—", full: "—" };
  if (typeof v === "string") return { short: truncate(v), full: v };
  if (typeof v === "number" || typeof v === "boolean") {
    const s = String(v);
    return { short: s, full: s };
  }
  const full = JSON.stringify(v, null, 2);
  const oneLine = JSON.stringify(v);
  return { short: truncate(oneLine), full };
}

/* -------------------------------------------------------------------------- */
/* Rendering pieces                                                           */
/* -------------------------------------------------------------------------- */

function PanelLabel({ children }: { children: React.ReactNode }) {
  return <div className="text-label uppercase text-muted">{children}</div>;
}

function Segmented<T extends string>({
  value,
  onChange,
  options,
}: {
  value: T;
  onChange: (v: T) => void;
  options: ReadonlyArray<{ id: T; label: string }>;
}) {
  return (
    <div className="inline-flex rounded-control border border-border overflow-hidden">
      {options.map((o) => {
        const active = o.id === value;
        return (
          <button
            key={o.id}
            type="button"
            onClick={() => onChange(o.id)}
            className={cn(
              "text-label uppercase px-2 py-0.5 transition-colors",
              active ? "bg-selected text-fg" : "text-muted hover:text-fg",
            )}
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

function Conversation({ messages }: { messages: ChatMessage[] }) {
  return (
    <div className="space-y-4">
      {messages.map((m, i) => (
        <Message key={i} message={m} />
      ))}
    </div>
  );
}

/**
 * One conversation message. Its content array may carry images (OpenAI
 * `image_url`/`input_image`, Anthropic `image.source`); those are rendered as
 * inline <img> with the rest (text + structured JSON) still shown via JsonTree.
 * Image extraction runs per-message (not only at a top-level branch) because the
 * dominant OpenAI/Anthropic shape routes the whole array here through
 * `Conversation`, so a top-level-only fix would still show JSON for the common case.
 */
/**
 * Resolves an externalized image ref (a {@code media_object} id) to a servable URL — the
 * {@code GET /api/orgs/{org}/projects/{proj}/media/{id}} endpoint. Provided by the trace surface, which
 * knows the org/project slugs; absent elsewhere, where an {@code image_ref} renders as a quiet chip
 * rather than a broken image.
 */
export const MediaResolverContext = createContext<((mediaId: string) => string) | null>(null);

function Message({ message }: { message: ChatMessage }) {
  const resolveMedia = useContext(MediaResolverContext);
  const media = useMemo(() => extractMedia(message.raw, resolveMedia), [message.raw, resolveMedia]);
  // The decoded <img>/link below already shows the picture/document; rendering the multi-MB
  // base64/data-URI a second time inside the JSON tree is pure noise (and weight).
  // Elide recognised media payloads to a short placeholder for the tree only —
  // the original `message.raw` is untouched, and the Raw toggle still shows it verbatim.
  const treeValue = useMemo(() => redactMediaData(message.raw), [message.raw]);
  // A message that is nothing but media shows the media. The tree earns its
  // place on mixed content, where it carries the text and structure around the
  // media; on its own it is `{"type":"image_ref","data":"01M0…"}` stacked above
  // the very thing it points at. Only when every media part resolved, though — a chip
  // means the bytes are unreachable, and then the id in the tree is all the
  // reader has left.
  const onlyMedia = useMemo(() => isAllMediaBlocks(message.raw), [message.raw]);
  const hideTree = onlyMedia && media.length > 0 && media.every((m) => m.kind === "img" || m.kind === "doc");
  return (
    <div className="space-y-2">
      {!hideTree && <JsonTree value={treeValue} collapsed={2} />}
      {media.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {media.map((m, i) => (
            <MediaPart key={i} media={m} />
          ))}
        </div>
      )}
    </div>
  );
}

/** A resolved, render-safe image or document part, or an "unsupported" chip for a content
 *  block we recognise as media but won't render (bad scheme, disallowed MIME). */
type MediaView =
  | { kind: "img"; src: string }
  | { kind: "doc"; src: string; label?: string }
  | { kind: "chip"; label: string };

function MediaPart({ media }: { media: MediaView }) {
  const [expanded, setExpanded] = useState(false);
  if (media.kind === "chip") {
    return (
      <span className="inline-flex items-center rounded-control border border-border bg-bg px-2 py-0.5 text-label uppercase text-muted">
        {media.label}
      </span>
    );
  }
  if (media.kind === "doc") {
    // Never an inline <embed>/<iframe> — a PDF can carry active content; let the browser handle it
    // out-of-process in its own tab instead of this trace-view page's context.
    return (
      <a
        href={media.src}
        target="_blank"
        rel="noopener noreferrer"
        className="inline-flex items-center gap-1.5 rounded-control border border-border bg-bg px-2 py-1 text-small text-fg hover:bg-bg-hover">
        <span aria-hidden>📄</span>
        {media.label || "View PDF"}
      </a>
    );
  }
  return (
    <button
      type="button"
      onClick={() => setExpanded((e) => !e)}
      className="rounded-card border border-border bg-bg overflow-hidden p-0 cursor-zoom-in"
      title={expanded ? "Shrink image" : "Expand image"}
    >
      <img
        src={media.src}
        alt="trace image"
        className="block object-contain"
        style={{ maxHeight: expanded ? 640 : 160, maxWidth: "100%" }}
      />
    </button>
  );
}

function JsonTree({ value, collapsed }: { value: unknown; collapsed: number }) {
  if (value === null || typeof value !== "object") {
    return (
      <pre className="font-mono whitespace-pre-wrap text-fg text-small">
        {typeof value === "string" ? value : String(value)}
      </pre>
    );
  }
  return (
    <div className="payload-json">
      <JsonView
        value={value as object}
        style={jsonTheme}
        collapsed={collapsed}
        // upstream alpha mistypes this as number; runtime concatenates it as a string
        stringEllipsis={"" as unknown as number}
        displayDataTypes={false}
        displayObjectSize={false}
        enableClipboard={false}
      />
    </div>
  );
}

const jsonTheme = {
  "--w-rjv-font-family": "var(--font-mono)",
  "--w-rjv-color": "var(--color-muted)",
  "--w-rjv-key-string": "var(--color-fg)",
  "--w-rjv-background-color": "transparent",
  "--w-rjv-line-color": "var(--color-border)",
  "--w-rjv-arrow-color": "var(--color-accent)",
  "--w-rjv-info-color": "var(--color-subtle)",
  "--w-rjv-curlybraces-color": "var(--color-muted)",
  "--w-rjv-brackets-color": "var(--color-muted)",
  "--w-rjv-colon-color": "var(--color-muted)",
  "--w-rjv-quotes-color": "var(--color-success)",
  "--w-rjv-quotes-string-color": "var(--color-success)",
  "--w-rjv-type-string-color": "var(--color-success)",
  "--w-rjv-type-int-color": "var(--color-accent)",
  "--w-rjv-type-float-color": "var(--color-accent)",
  "--w-rjv-type-bigint-color": "var(--color-accent)",
  "--w-rjv-type-boolean-color": "var(--color-accent)",
  "--w-rjv-type-date-color": "var(--color-muted)",
  "--w-rjv-type-url-color": "var(--color-accent)",
  "--w-rjv-type-null-color": "var(--color-subtle)",
  "--w-rjv-type-nan-color": "var(--color-subtle)",
  "--w-rjv-type-undefined-color": "var(--color-subtle)",
  fontSize: "var(--text-small)",
} as React.CSSProperties;

/* -------------------------------------------------------------------------- */
/* Parsing                                                                    */
/* -------------------------------------------------------------------------- */

type ChatMessage = { role: string; raw: unknown };

type Parsed =
  | { kind: "chat"; messages: ChatMessage[] }
  | { kind: "json"; value: unknown }
  | { kind: "text"; text: string };

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => !!v && typeof v === "object" && !Array.isArray(v);

function parsePayload(payload: string | null): Parsed {
  if (payload == null) return { kind: "text", text: "" };
  const t = payload.trimStart();
  if (t.length === 0 || (t[0] !== "{" && t[0] !== "[")) {
    return { kind: "text", text: payload };
  }
  let root: unknown;
  try {
    root = JSON.parse(payload);
  } catch {
    return { kind: "text", text: payload };
  }

  if (Array.isArray(root)) {
    if (root.some((n) => isObj(n) && "role" in n)) {
      return { kind: "chat", messages: root.filter(isObj).map(messageFromObj) };
    }
    if (root.length > 0 && root.every((n) => isObj(n) && "type" in n)) {
      return { kind: "chat", messages: [{ role: "", raw: root }] };
    }
    return { kind: "json", value: root };
  }
  if (isObj(root)) {
    if ("role" in root) return { kind: "chat", messages: [messageFromObj(root)] };
    if ("type" in root) return { kind: "chat", messages: [{ role: "", raw: root }] };
    return { kind: "json", value: root };
  }
  return { kind: "json", value: root };
}

function messageFromObj(m: Obj): ChatMessage {
  return { role: typeof m.role === "string" ? m.role : "", raw: m };
}

function truncate(s: string, n = 32): string {
  return s.length > n ? `${s.slice(0, n)}…` : s;
}

/* -------------------------------------------------------------------------- */
/* Media extraction + XSS guard (image + document)                            */
/* -------------------------------------------------------------------------- */
//
// Mirrors the backend ai.tessary.model.ContentExtractor content-block
// shapes: OpenAI `image_url` / `input_image` / `output_image` / `input_file` / `file`, and Anthropic
// `image` / `document` with `source.{base64,url}`. The platform's own first-class blocks
// `{type:"image_url",url}` / `{type:"image_b64",data,mediaType}` and their document counterparts
// (`document_url`, `document_b64`, `document_ref`) are also recognised. Keep this in sync with that
// extractor when shapes change — this is a fourth, independently-kept-in-sync vocabulary alongside the
// Java ContentBlock enum, the judge-boundary switch, and the Python/Java classifier-context switch (see
// devdocs/reference/media-contract.md).

/** Base64 image MIME types we will render. image/svg+xml is DELIBERATELY excluded:
 *  SVG is an active document (can carry <script>) and a data: URL of it would
 *  execute in the img/object context for some sinks — never allow it. */
const ALLOWED_IMAGE_MIME = new Set(["image/png", "image/jpeg", "image/gif", "image/webp"]);

/**
 * Resolve a remote/data image URL to a render-safe src, or null to reject.
 * Accept ONLY `https://` (no http: mixed-content / SSRF-ish internal refs) and
 * `data:image/<allowed>;…` (reject data:text, data:application, data:image/svg+xml,
 * and relative/other schemes). Returns the original string when accepted.
 */
function safeImageUrl(url: unknown): string | null {
  if (typeof url !== "string") return null;
  const u = url.trim();
  if (u.startsWith("https://")) return u;
  if (u.startsWith("data:")) {
    const m = /^data:([^;,]+)[;,]/.exec(u);
    const mime = m ? m[1].toLowerCase() : "";
    return ALLOWED_IMAGE_MIME.has(mime) ? u : null;
  }
  return null;
}

/** The one document type this run ships (PDF only — no audio/video). */
const ALLOWED_DOCUMENT_MIME = new Set(["application/pdf"]);

/** {@link safeImageUrl}'s document counterpart: `https://` or a `data:application/pdf;base64,…` URI. */
function safeDocumentUrl(url: unknown): string | null {
  if (typeof url !== "string") return null;
  const u = url.trim();
  if (u.startsWith("https://")) return u;
  if (u.startsWith("data:")) {
    const m = /^data:([^;,]+)[;,]/.exec(u);
    const mime = m ? m[1].toLowerCase() : "";
    return ALLOWED_DOCUMENT_MIME.has(mime) ? u : null;
  }
  return null;
}

/** Build a data: URL from raw base64 + media type, gated by the MIME allowlist. */
/** The base64 alphabet and nothing else — the cheap test for "is this actually bytes". */
const BASE64_ONLY = /^[A-Za-z0-9+/=]+$/;

function safeBase64Image(data: unknown, mediaType: unknown): string | null {
  if (typeof data !== "string" || data.length === 0) return null;
  const mime = typeof mediaType === "string" ? mediaType.toLowerCase() : "image/png";
  if (!ALLOWED_IMAGE_MIME.has(mime)) return null;
  // A producer that strips image bytes leaves prose in their place — 7,930 spans
  // here hold `[image image/jpeg 149908 chars base64 — typed copy …]`. Concatenated
  // into a data: URI that is a broken <img>, and worse, one the caller cannot tell
  // from a real one, so it counted as a resolved image and suppressed the JSON that
  // was the only remaining record. Anything outside the alphabet is not bytes.
  const bytes = data.replace(/\s+/g, "");
  if (bytes.length === 0 || !BASE64_ONLY.test(bytes)) return null;
  return `data:${mime};base64,${bytes}`;
}

/** {@link safeBase64Image}'s document counterpart, gated by {@link ALLOWED_DOCUMENT_MIME}. */
function safeBase64Document(data: unknown, mediaType: unknown): string | null {
  if (typeof data !== "string" || data.length === 0) return null;
  const mime = typeof mediaType === "string" ? mediaType.toLowerCase() : "application/pdf";
  if (!ALLOWED_DOCUMENT_MIME.has(mime)) return null;
  const bytes = data.replace(/\s+/g, "");
  if (bytes.length === 0 || !BASE64_ONLY.test(bytes)) return null;
  return `data:${mime};base64,${bytes}`;
}

const UNSUPPORTED_CHIP: MediaView = { kind: "chip", label: "unsupported image" };
const UNSUPPORTED_DOCUMENT_CHIP: MediaView = { kind: "chip", label: "unsupported document" };

/** Every block type {@link extractMedia} recognises as an image. */
const IMAGE_BLOCK_TYPES = new Set([
  "image_ref",
  "image_url",
  "input_image",
  "output_image",
  "image_b64",
  "image",
]);

/** Every block type {@link extractMedia} recognises as a document (PDF only — no audio/video).
 *  Includes the raw wire aliases (`document`, `input_file`, `file`) alongside the platform's own
 *  first-class `document_*` kinds, mirroring {@link IMAGE_BLOCK_TYPES}'s pattern. */
const DOCUMENT_BLOCK_TYPES = new Set(["document_ref", "document_b64", "document_url", "document", "input_file", "file"]);

/** The union {@link isAllMediaBlocks} checks against. */
const MEDIA_BLOCK_TYPES = new Set([...IMAGE_BLOCK_TYPES, ...DOCUMENT_BLOCK_TYPES]);

/**
 * True when a message carries media and nothing else — the case where the JSON
 * tree has nothing to say that the rendered image/document does not say better. Deliberately
 * strict: an unrecognised block, a stray text part, or a payload that is not a
 * block array at all all answer false, so the tree stays wherever it might still
 * be carrying information.
 */
function isAllMediaBlocks(raw: unknown): boolean {
  const body = Array.isArray(raw) ? raw : isObj(raw) ? (raw.content ?? raw.parts) : null;
  if (!Array.isArray(body) || body.length === 0) return false;
  return body.every((b) => isObj(b) && typeof b.type === "string" && MEDIA_BLOCK_TYPES.has(b.type));
}

/** Pull every renderable image/document (or unsupported-chip) out of a message's raw value.
 *  Walks the message `content` array (or a bare content-block array) and matches the
 *  same shapes ContentExtractor parses on the backend. Non-media parts are ignored
 *  here (JsonTree still renders them); only recognised media yields an entry. */
function extractMedia(raw: unknown, resolveMedia?: ((mediaId: string) => string) | null): MediaView[] {
  const out: MediaView[] = [];
  const visit = (node: unknown) => {
    if (Array.isArray(node)) {
      node.forEach(visit);
      return;
    }
    if (!isObj(node)) return;
    const part = node as Obj;
    const type = typeof part.type === "string" ? part.type : "";

    if (type === "image_ref") {
      // Externalized image: {type:"image_ref", data:<mediaId>, mediaType}. Rendered by-ref via
      // the media endpoint when the surface supplies a resolver; a quiet chip otherwise.
      const mediaId = typeof part.data === "string" ? part.data : "";
      if (mediaId && resolveMedia) out.push({ kind: "img", src: resolveMedia(mediaId) });
      else out.push({ kind: "chip", label: "stored image" });
      return;
    }
    if (type === "document_ref") {
      // Externalized document. MediaController already serves arbitrary stored mediaType, so the
      // same resolver endpoint works unchanged — no backend change needed for this to render.
      const mediaId = typeof part.data === "string" ? part.data : "";
      if (mediaId && resolveMedia) out.push({ kind: "doc", src: resolveMedia(mediaId) });
      else out.push({ kind: "chip", label: "stored document" });
      return;
    }
    if (type === "image_url") {
      // OpenAI: image_url is either {url} or a bare string. Also the platform's own block.
      const iu = part.image_url;
      const url = isObj(iu) ? iu.url : typeof iu === "string" ? iu : part.url;
      pushImageUrl(out, url);
      return;
    }
    if (type === "document_url") {
      pushDocumentUrl(out, part.url);
      return;
    }
    if (type === "input_image" || type === "output_image") {
      pushImageUrl(out, part.image_url ?? part.url);
      return;
    }
    if (type === "image_b64") {
      // Platform first-class base64 block.
      const src = safeBase64Image(part.data, part.mediaType);
      out.push(src ? { kind: "img", src } : UNSUPPORTED_CHIP);
      return;
    }
    if (type === "document_b64") {
      const src = safeBase64Document(part.data, part.mediaType);
      out.push(src ? { kind: "doc", src } : UNSUPPORTED_DOCUMENT_CHIP);
      return;
    }
    if (type === "image") {
      // Anthropic: {type:"image", source:{type:"base64"|"url", ...}}.
      const s = part.source;
      if (isObj(s)) {
        if (s.type === "base64") {
          const src = safeBase64Image(s.data, s.media_type);
          out.push(src ? { kind: "img", src } : UNSUPPORTED_CHIP);
        } else if (s.type === "url") {
          pushImageUrl(out, s.url);
        }
      }
      return;
    }
    if (type === "document") {
      // Anthropic: {type:"document", source:{type:"base64"|"url", ...}}.
      const s = part.source;
      if (isObj(s)) {
        if (s.type === "base64") {
          const src = safeBase64Document(s.data, s.media_type);
          out.push(src ? { kind: "doc", src } : UNSUPPORTED_DOCUMENT_CHIP);
        } else if (s.type === "url") {
          pushDocumentUrl(out, s.url);
        }
      }
      return;
    }
    if (type === "input_file" || type === "file") {
      // OpenAI document input: file_data (a data: URI) or a bare url/file_url field.
      const fileData = part.file_data;
      if (typeof fileData === "string" && fileData.startsWith("data:")) {
        pushDocumentUrl(out, fileData);
      } else {
        pushDocumentUrl(out, part.url ?? part.file_url);
      }
      return;
    }
    // A message wrapper: descend into its content array.
    if ("content" in part) visit(part.content);
  };
  visit(raw);
  return out;
}

function pushImageUrl(out: MediaView[], url: unknown) {
  const src = safeImageUrl(url);
  out.push(src ? { kind: "img", src } : UNSUPPORTED_CHIP);
}

function pushDocumentUrl(out: MediaView[], url: unknown, label?: string) {
  const src = safeDocumentUrl(url);
  out.push(src ? { kind: "doc", src, label } : UNSUPPORTED_DOCUMENT_CHIP);
}

/** A short stand-in for an elided inline media payload, with its size so the tree
 *  still conveys roughly how big the blob was. */
function mediaElision(len: number, kind: "image" | "document"): string {
  const kb = len / 1024;
  const size = kb >= 1 ? `${kb.toFixed(1)} KB` : `${len} chars`;
  return `<${kind} data elided, ${size}>`;
}

/** If `v` is an inline media payload the decoded <img>/link already shows — a raw base64
 *  blob (a dedicated `data` field) or a `data:` URI — return its short placeholder;
 *  otherwise null. Plain http(s) URLs are left intact (short, useful context). */
function elideUrl(v: unknown, kind: "image" | "document" = "image"): string | null {
  return typeof v === "string" && v.startsWith("data:") ? mediaElision(v.length, kind) : null;
}
function elideData(v: unknown, kind: "image" | "document" = "image"): string | null {
  return typeof v === "string" && v.length > 0 ? mediaElision(v.length, kind) : null;
}

/**
 * Deep-clone `raw`, replacing recognised inline media payloads (raw base64 in the
 * dedicated `data` fields, or `data:` URIs in url fields) with a short placeholder so
 * the JSON tree doesn't duplicate the megabytes the decoded <img>/link already renders.
 * Mirrors the shapes `extractMedia` matches — same OpenAI/Anthropic/platform image AND
 * document blocks — and is a no-op everywhere else (incl. plain http(s) URLs), so non-media
 * structure renders unchanged. Never mutates the input.
 */
export function redactMediaData(raw: unknown): unknown {
  const visit = (node: unknown): unknown => {
    if (Array.isArray(node)) return node.map(visit);
    if (!isObj(node)) return node;
    const part = node as Obj;
    const type = typeof part.type === "string" ? part.type : "";

    if (type === "image_url") {
      const iu = part.image_url;
      if (isObj(iu)) {
        const url = elideUrl(iu.url);
        return url ? { ...part, image_url: { ...iu, url } } : part;
      }
      const inline = elideUrl(iu);
      if (inline) return { ...part, image_url: inline };
      const bare = elideUrl(part.url);
      return bare ? { ...part, url: bare } : part;
    }
    if (type === "document_url") {
      const bare = elideUrl(part.url, "document");
      return bare ? { ...part, url: bare } : part;
    }
    if (type === "input_image" || type === "output_image") {
      const imgUrl = elideUrl(part.image_url);
      const url = elideUrl(part.url);
      if (!imgUrl && !url) return part;
      const next: Obj = { ...part };
      if (imgUrl) next.image_url = imgUrl;
      if (url) next.url = url;
      return next;
    }
    if (type === "input_file" || type === "file") {
      const fileData = elideUrl(part.file_data, "document");
      const url = elideUrl(part.url, "document");
      const fileUrl = elideUrl(part.file_url, "document");
      if (!fileData && !url && !fileUrl) return part;
      const next: Obj = { ...part };
      if (fileData) next.file_data = fileData;
      if (url) next.url = url;
      if (fileUrl) next.file_url = fileUrl;
      return next;
    }
    if (type === "image_b64") {
      const data = elideData(part.data);
      return data ? { ...part, data } : part;
    }
    if (type === "document_b64") {
      const data = elideData(part.data, "document");
      return data ? { ...part, data } : part;
    }
    if (type === "image_ref" || type === "document_ref") {
      // `data` here is only a short media id (see ContentBlock.imageRef/documentRef), never a blob —
      // nothing to elide there. `text` is the field that can carry a real payload: MediaExternalizer
      // populates a document_ref's `text` with up to 200,000 chars of extracted PDF text at ingest, and
      // without this case it fell through to the generic recursion and rendered inline unredacted.
      const kind = type === "document_ref" ? "document" : "image";
      const text = elideData(part.text, kind);
      return text ? { ...part, text } : part;
    }
    if (type === "image" || type === "document") {
      const kind = type === "document" ? "document" : "image";
      const s = part.source;
      if (isObj(s)) {
        const data = elideData(s.data, kind);
        const url = elideUrl(s.url, kind);
        if (!data && !url) return part;
        const next: Obj = { ...s };
        if (data) next.data = data;
        if (url) next.url = url;
        return { ...part, source: next };
      }
      return part;
    }
    // Recurse through wrappers (a message with a `content` array) and any other object,
    // rebuilding only when a nested value actually changed.
    const next: Obj = {};
    let changed = false;
    for (const [k, val] of Object.entries(part)) {
      const mapped = visit(val);
      next[k] = mapped;
      if (mapped !== val) changed = true;
    }
    return changed ? next : part;
  };
  return visit(raw);
}
