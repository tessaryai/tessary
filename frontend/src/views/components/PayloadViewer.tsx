// SPDX-License-Identifier: Apache-2.0
import { createContext, useContext, useMemo, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { JsonView } from "@uiw/react-json-view";
import { cn, useCopy } from "../../ui";
import { jsonTheme } from "./jsonTheme";

export function PayloadViewer({
  payload,
  label,
  maxHeight = 320,
}: {
  payload: string;
  label: string;
  maxHeight?: number;
}) {
  const [mode, setMode] = useState<"formatted" | "raw">("formatted");
  const { copied, copy } = useCopy();
  const parsed = useMemo(() => parsePayload(payload), [payload]);

  return (
    <div>
      <div className="flex items-center justify-between gap-2 mb-1">
        <PanelLabel>{label}</PanelLabel>
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
      <div className="overflow-auto" style={{ maxHeight }}>
        {mode === "raw" ? (
          <pre className="font-mono whitespace-pre-wrap text-fg text-small">{payload}</pre>
        ) : (
          <RenderParsed parsed={parsed} />
        )}
      </div>
    </div>
  );
}

function RenderParsed({ parsed }: { parsed: Parsed }) {
  if (parsed.kind === "chat") return <Conversation messages={parsed.messages} />;
  if (parsed.kind === "json") return <JsonTree value={parsed.value} collapsed={3} />;
  return <Markdown>{parsed.text}</Markdown>;
}

/** The formatted rendering without chrome, so span bodies render like the payload panel. */
export function PayloadBody({ payload }: { payload: string | null }) {
  const parsed = useMemo(() => parsePayload(payload), [payload]);
  if (payload == null || payload === "") {
    return <span className="italic text-subtle text-small">(none)</span>;
  }
  return <RenderParsed parsed={parsed} />;
}

export { parsePayload };
export type { ChatMessage };

export function Markdown({ children }: { children: string }) {
  return (
    <div className="payload-md">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>
    </div>
  );
}

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

/** Resolves a media_object id to a servable URL; absent outside the trace surface, where an image_ref renders as a chip. */
export const MediaResolverContext = createContext<((mediaId: string) => string) | null>(null);

function Message({ message }: { message: ChatMessage }) {
  const resolveMedia = useContext(MediaResolverContext);
  const media = useMemo(() => extractMedia(message.raw, resolveMedia), [message.raw, resolveMedia]);
  // The rendered media already shows the bytes, so the tree elides them; the Raw toggle still shows them.
  const treeValue = useMemo(() => redactMediaData(message.raw) as object, [message.raw]);
  // A media-only message shows just the media, unless a part is a chip: then the id in the tree is all
  // the reader has.
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

type MediaView =
  | { kind: "img"; src: string }
  | { kind: "doc"; src: string }
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
    // Never an inline <embed>/<iframe>: a PDF can carry active content, so it opens in its own tab.
    return (
      <a
        href={media.src}
        target="_blank"
        rel="noopener noreferrer"
        className="inline-flex items-center gap-1.5 rounded-control border border-border bg-bg px-2 py-1 text-small text-fg hover:bg-bg-hover">
        <span aria-hidden>📄</span>
        View PDF
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

function JsonTree({ value, collapsed }: { value: object; collapsed: number }) {
  return (
    <div className="payload-json">
      <JsonView
        value={value}
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


type ChatMessage = { role: string; raw: unknown };

type Parsed =
  | { kind: "chat"; messages: ChatMessage[] }
  | { kind: "json"; value: object }
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
  const obj = root as Obj;
  if ("role" in obj) return { kind: "chat", messages: [messageFromObj(obj)] };
  if ("type" in obj) return { kind: "chat", messages: [{ role: "", raw: obj }] };
  return { kind: "json", value: obj };
}

function messageFromObj(m: Obj): ChatMessage {
  return { role: typeof m.role === "string" ? m.role : "", raw: m };
}

// Mirrors the backend ContentExtractor's block shapes; keep in sync with it (devdocs/reference/media-contract.md).

/** image/svg+xml is excluded: SVG is an active document that can carry <script>. */
const ALLOWED_IMAGE_MIME = new Set(["image/png", "image/jpeg", "image/gif", "image/webp"]);

const ALLOWED_DOCUMENT_MIME = new Set(["application/pdf"]);

/** Only `https://` and `data:<allowed>`; everything else is rejected. */
function safeUrl(url: unknown, allowed: Set<string>): string | null {
  if (typeof url !== "string") return null;
  const u = url.trim();
  if (u.startsWith("https://")) return u;
  if (u.startsWith("data:")) {
    const m = /^data:([^;,]+)[;,]/.exec(u);
    const mime = m ? m[1].toLowerCase() : "";
    return allowed.has(mime) ? u : null;
  }
  return null;
}

const BASE64_ONLY = /^[A-Za-z0-9+/=]+$/;

function safeBase64Image(data: unknown, mediaType: unknown): string | null {
  if (typeof data !== "string" || data.length === 0) return null;
  const mime = typeof mediaType === "string" ? mediaType.toLowerCase() : "image/png";
  if (!ALLOWED_IMAGE_MIME.has(mime)) return null;
  // A producer that strips image bytes leaves prose in their place, which would make a broken <img> that
  // counts as resolved and hides the JSON. Anything outside the base64 alphabet is not bytes.
  const bytes = data.replace(/\s+/g, "");
  if (bytes.length === 0 || !BASE64_ONLY.test(bytes)) return null;
  return `data:${mime};base64,${bytes}`;
}

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

const IMAGE_BLOCK_TYPES = new Set([
  "image_ref",
  "image_url",
  "input_image",
  "output_image",
  "image_b64",
  "image",
]);

const DOCUMENT_BLOCK_TYPES = new Set(["document_ref", "document_b64", "document_url", "document", "input_file", "file"]);

const MEDIA_BLOCK_TYPES = new Set([...IMAGE_BLOCK_TYPES, ...DOCUMENT_BLOCK_TYPES]);

/**
 * Strict: an unrecognised block or stray text part answers false, so the tree stays wherever it may
 * still carry information.
 */
function isAllMediaBlocks(raw: unknown): boolean {
  const body = Array.isArray(raw) ? raw : isObj(raw) ? (raw.content ?? raw.parts) : null;
  if (!Array.isArray(body) || body.length === 0) return false;
  return body.every((b) => isObj(b) && typeof b.type === "string" && MEDIA_BLOCK_TYPES.has(b.type));
}

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
      const mediaId = typeof part.data === "string" ? part.data : "";
      if (mediaId && resolveMedia) out.push({ kind: "img", src: resolveMedia(mediaId) });
      else out.push({ kind: "chip", label: "stored image" });
      return;
    }
    if (type === "document_ref") {
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
    if ("content" in part) visit(part.content);
  };
  visit(raw);
  return out;
}

function pushImageUrl(out: MediaView[], url: unknown) {
  const src = safeUrl(url, ALLOWED_IMAGE_MIME);
  out.push(src ? { kind: "img", src } : UNSUPPORTED_CHIP);
}

function pushDocumentUrl(out: MediaView[], url: unknown) {
  const src = safeUrl(url, ALLOWED_DOCUMENT_MIME);
  out.push(src ? { kind: "doc", src } : UNSUPPORTED_DOCUMENT_CHIP);
}

function mediaElision(len: number, kind: "image" | "document"): string {
  const kb = len / 1024;
  const size = kb >= 1 ? `${kb.toFixed(1)} KB` : `${len} chars`;
  return `<${kind} data elided, ${size}>`;
}

/** Plain http(s) URLs are short, useful context and stay. */
function elideUrl(v: unknown, kind: "image" | "document" = "image"): string | null {
  return typeof v === "string" && v.startsWith("data:") ? mediaElision(v.length, kind) : null;
}
function elideData(v: unknown, kind: "image" | "document" = "image"): string | null {
  return typeof v === "string" && v.length > 0 ? mediaElision(v.length, kind) : null;
}

/** Mirrors `extractMedia`'s shapes and never mutates the input. */
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
      // `data` here is a short media id, but `text` can hold up to 200,000 chars of extracted PDF text.
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
