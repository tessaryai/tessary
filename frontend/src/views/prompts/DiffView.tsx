// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import type { PromptBody, PromptChatBody, PromptConfig, PromptType } from "../../api/types";
import { Badge, cn } from "../../ui";
import { isPlaceholder } from "./promptCompile";
import { type ChatRow, type ConfigRow, type DiffOp, diffChat, diffConfigKeys, tokenize, wordDiff } from "./promptDiff";

const BLOCK = "m-0 whitespace-pre-wrap break-words font-mono text-code text-fg-secondary";
const CARD = "rounded-card border border-border bg-bg p-3";
const PLACEHOLDER_ROW = "flex items-center gap-2 rounded-card border border-dashed border-border p-3";

/** A hue marks the change, never a control; the text stays grey. */
const Removed = ({ children }: { children: ReactNode }) => <del className="bg-error-subtle">{children}</del>;
const Added = ({ children }: { children: ReactNode }) => <ins className="bg-success-subtle no-underline">{children}</ins>;

/** One side of a word diff: the left keeps removed text, the right keeps added text. */
function Side({ ops, side }: { ops: DiffOp[]; side: "left" | "right" }) {
  return (
    <>
      {ops.map((op, i) => {
        if (op.kind === "same") return op.text;
        if (side === "left") return op.kind === "removed" ? <Removed key={i}>{op.text}</Removed> : null;
        return op.kind === "added" ? <Added key={i}>{op.text}</Added> : null;
      })}
    </>
  );
}

export function DiffColumns({ ops }: { ops: DiffOp[] }) {
  return (
    <div className="grid grid-cols-2 gap-3">
      <pre className={cn(BLOCK, CARD)}>
        <Side ops={ops} side="left" />
      </pre>
      <pre className={cn(BLOCK, CARD)}>
        <Side ops={ops} side="right" />
      </pre>
    </div>
  );
}

/** Unchanged text kept around a change, in tokens (a word and the space after it are two). */
const CONTEXT = 8;

/**
 * Removed and added text in one line, with long unchanged runs cut to a few words on each side of a change, as a
 * preview of an edit reads.
 */
export function DiffInline({ ops }: { ops: DiffOp[] }) {
  return (
    <pre className={BLOCK}>
      {ops.map((op, i) => {
        if (op.kind === "removed") return <Removed key={i}>{op.text}</Removed>;
        if (op.kind === "added") return <Added key={i}>{op.text}</Added>;
        const tokens = tokenize(op.text);
        const first = i === 0;
        const last = i === ops.length - 1;
        if (tokens.length <= CONTEXT * 2) return op.text;
        const head = tokens.slice(0, CONTEXT).join("");
        const tail = tokens.slice(-CONTEXT).join("");
        if (first) return `… ${tail.trimStart()}`;
        if (last) return `${head.trimEnd()} …`;
        return `${head.trimEnd()} … ${tail.trimStart()}`;
      })}
    </pre>
  );
}

function plural(n: number, one: string, many: string) {
  return `${n} ${n === 1 ? one : many}`;
}

/** "1 message changed, 1 unchanged". */
export function chatSummary(rows: ChatRow[]): string {
  const count = (kind: ChatRow["kind"]) => rows.filter((r) => r.kind === kind).length;
  const parts = [
    count("changed") && `${plural(count("changed"), "message", "messages")} changed`,
    count("added") && `${count("added")} added`,
    count("removed") && `${count("removed")} removed`,
    count("same") && `${count("same")} unchanged`,
  ].filter(Boolean);
  return parts.join(", ");
}

/** "1 key changed, 1 key added", or null when nothing changed. */
export function configSummary(rows: ConfigRow[]): string | null {
  const count = (kind: ConfigRow["kind"]) => rows.filter((r) => r.kind === kind).length;
  const parts = (["changed", "added", "removed"] as const)
    .filter((k) => count(k) > 0)
    .map((k) => `${plural(count(k), "key", "keys")} ${k}`);
  return parts.length ? parts.join(", ") : null;
}


/** One side of one chat row; a missing side is an empty slot so the pairs stay level. */
function ChatCell({ row, side, version }: { row: ChatRow; side: "left" | "right"; version: number }) {
  const entry = side === "left" ? ("left" in row ? row.left : null) : "right" in row ? row.right : null;
  if (!entry) return <div aria-hidden="true" />;
  if (isPlaceholder(entry)) {
    const changed = row.kind !== "same";
    return (
      <div className={cn(PLACEHOLDER_ROW, changed && (side === "left" ? "bg-error-subtle" : "bg-success-subtle"))}>
        <Badge>placeholder</Badge>
        <span className="font-mono text-code text-fg-secondary">{entry.name}</span>
      </div>
    );
  }
  const whole = row.kind === "removed" || row.kind === "added";
  return (
    <div className={cn(CARD, "flex flex-col gap-1.5")}>
      <div className="text-label uppercase text-subtle">{`${entry.role} · version ${version}`}</div>
      <pre className={BLOCK}>
        {row.kind === "changed" ? (
          <Side ops={row.ops} side={side} />
        ) : whole ? (
          side === "left" ? <Removed>{entry.content}</Removed> : <Added>{entry.content}</Added>
        ) : (
          entry.content
        )}
      </pre>
    </div>
  );
}

export function ChatColumns({ rows, left, right }: { rows: ChatRow[]; left: number; right: number }) {
  return (
    <div className="grid grid-cols-2 gap-3">
      {rows.map((row, i) => (
        <div key={i} className="contents">
          <ChatCell row={row} side="left" version={left} />
          <ChatCell row={row} side="right" version={right} />
        </div>
      ))}
    </div>
  );
}

function ConfigLine({ row, side }: { row: ConfigRow; side: "left" | "right" }) {
  const key = `"${row.key}": `;
  if (row.kind === "same") return <div>{key + (side === "left" ? row.left : row.right)}</div>;
  if (row.kind === "changed") {
    return (
      <div>
        {key}
        <Side ops={row.ops} side={side} />
      </div>
    );
  }
  if (row.kind === "removed") return side === "left" ? <div><Removed>{key + row.left}</Removed></div> : null;
  return side === "right" ? <div><Added>{key + row.right}</Added></div> : null;
}

export function ConfigColumns({ rows }: { rows: ConfigRow[] }) {
  const empty = <span className="text-subtle">No config</span>;
  return (
    <div className="grid grid-cols-2 gap-3">
      {(["left", "right"] as const).map((side) => {
        const lines = rows.filter((r) => (side === "left" ? r.kind !== "added" : r.kind !== "removed"));
        return (
          <pre key={side} className={cn(BLOCK, CARD)}>
            {lines.length === 0 ? empty : lines.map((r) => <ConfigLine key={r.key} row={r} side={side} />)}
          </pre>
        );
      })}
    </div>
  );
}

/** Every change from `from` to the draft, inline, for the New version screen. */
export function ChangesPreview({
  type,
  from,
  body,
  fromConfig,
  config,
}: {
  type: PromptType;
  from: PromptBody;
  body: PromptBody;
  fromConfig: PromptConfig;
  config: PromptConfig | null;
}) {
  const promptPart = () => {
    if (type === "text") {
      const ops = wordDiff(from as string, body as string);
      if (ops.every((o) => o.kind === "same")) return <p className="text-small text-muted">The prompt has no changes.</p>;
      return (
        <div className={CARD}>
          <DiffInline ops={ops} />
        </div>
      );
    }
    const rows = diffChat(from as PromptChatBody, body as PromptChatBody).filter((r) => r.kind !== "same");
    if (rows.length === 0) return <p className="text-small text-muted">The prompt has no changes.</p>;
    return (
      <div className="flex flex-col gap-2">
        {rows.map((row, i) => {
          const entry = "right" in row ? row.right : row.left;
          const what = row.kind === "changed" ? "changed" : row.kind;
          const head = isPlaceholder(entry) ? `placeholder ${entry.name} · ${what}` : `${entry.role} · ${what}`;
          return (
            <div key={i} className={cn(CARD, "flex flex-col gap-1.5")}>
              <div className="text-label uppercase text-subtle">{head}</div>
              {row.kind === "changed" ? (
                <DiffInline ops={row.ops} />
              ) : isPlaceholder(entry) ? null : (
                <pre className={BLOCK}>{row.kind === "added" ? <Added>{entry.content}</Added> : <Removed>{entry.content}</Removed>}</pre>
              )}
            </div>
          );
        })}
      </div>
    );
  };
  const configPart = () => {
    if (config === null) return <p className="text-small text-muted">The config is not a JSON object yet.</p>;
    const rows = diffConfigKeys(fromConfig, config).filter((r) => r.kind !== "same");
    if (rows.length === 0) return <p className="text-small text-muted">The config has no changes.</p>;
    return (
      <pre className={cn(BLOCK, CARD)}>
        {rows.map((r) => (
          <div key={r.key}>
            {r.kind === "changed" ? (
              <>
                {`"${r.key}": `}
                <DiffInlineOps ops={r.ops} />
              </>
            ) : r.kind === "added" ? (
              <Added>{`"${r.key}": ${r.right}`}</Added>
            ) : (
              <Removed>{`"${r.key}": ${r.left}`}</Removed>
            )}
          </div>
        ))}
      </pre>
    );
  };
  return (
    <div className="flex flex-col gap-3">
      {promptPart()}
      {configPart()}
    </div>
  );
}

function DiffInlineOps({ ops }: { ops: DiffOp[] }) {
  return (
    <>
      {ops.map((op, i) =>
        op.kind === "same" ? op.text : op.kind === "removed" ? <Removed key={i}>{op.text}</Removed> : <Added key={i}>{op.text}</Added>,
      )}
    </>
  );
}
