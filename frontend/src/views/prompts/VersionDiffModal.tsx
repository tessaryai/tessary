// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import type { PromptDetail, VersionSummary } from "../../api/types";
import { ErrorNote, Field, LoadingRow, Modal, Select } from "../../ui";
import { type DiffOp, diffBody, diffConfig } from "./promptDiff";
import { usePromptVersion } from "./promptReads";

/** `versions` is newest first; the selected version on the right, the next older one (or newer, for the oldest) on the left. */
export function defaultPair(versions: VersionSummary[], selected: number): { left: number; right: number } {
  const i = versions.findIndex((v) => v.version === selected);
  const partner = i + 1 < versions.length ? versions[i + 1] : versions[i - 1];
  return { left: partner.version, right: selected };
}

const BLOCK = "m-0 whitespace-pre-wrap rounded-card border border-border bg-bg p-3 font-mono text-code text-fg-secondary";

/** The left side keeps what was removed, the right side what was added; the hue marks the change, not a control. */
function DiffColumns({ ops }: { ops: DiffOp[] }) {
  return (
    <div className="grid grid-cols-2 gap-3">
      <pre className={BLOCK}>
        {ops.map((op, i) =>
          op.kind === "added" ? null : op.kind === "removed" ? (
            <del key={i} className="bg-error-subtle">
              {op.text}
            </del>
          ) : (
            op.text
          ),
        )}
      </pre>
      <pre className={BLOCK}>
        {ops.map((op, i) =>
          op.kind === "removed" ? null : op.kind === "added" ? (
            <ins key={i} className="bg-success-subtle no-underline">
              {op.text}
            </ins>
          ) : (
            op.text
          ),
        )}
      </pre>
    </div>
  );
}

function VersionPicker({
  label,
  value,
  versions,
  onChange,
}: {
  label: string;
  value: number;
  versions: VersionSummary[];
  onChange: (v: number) => void;
}) {
  return (
    <Field label={label}>
      {(p) => (
        <Select {...p} value={String(value)} onChange={(e) => onChange(Number(e.target.value))}>
          {versions.map((v) => (
            <option key={v.id} value={v.version}>
              Version {v.version}
            </option>
          ))}
        </Select>
      )}
    </Field>
  );
}

export function VersionDiffModal({
  prompt,
  left: initialLeft,
  right: initialRight,
  onClose,
}: {
  prompt: PromptDetail;
  left: number;
  right: number;
  onClose: () => void;
}) {
  const [left, setLeft] = useState(initialLeft);
  const [right, setRight] = useState(initialRight);
  const l = usePromptVersion(prompt.id, left, true);
  const r = usePromptVersion(prompt.id, right, true);

  const content = () => {
    if (left === right) return <p className="text-small text-muted">Pick two different versions.</p>;
    const failed = [l, r].find((q) => q.isError);
    if (failed) return <ErrorNote error={failed.error} />;
    if ([l, r].some((q) => q.isPending)) return <LoadingRow label="Loading versions…" />;
    const [a, b] = [l.data!, r.data!];
    const body = diffBody(prompt.type, a.body, b.body);
    const config = diffConfig(a.config, b.config);
    if ([...body, ...config].every((op) => op.kind === "same")) {
      return <p className="text-small text-muted">{`No differences between version ${left} and version ${right}.`}</p>;
    }
    return (
      <div className="flex flex-col gap-4">
        <section className="flex flex-col gap-2">
          <h3 className="text-h3 text-fg">Body</h3>
          <DiffColumns ops={body} />
        </section>
        <section className="flex flex-col gap-2">
          <h3 className="text-h3 text-fg">Config</h3>
          <DiffColumns ops={config} />
        </section>
        <p className="text-small text-subtle">Removed text is marked on the left, added text on the right.</p>
      </div>
    );
  };

  return (
    <Modal open onClose={onClose} size="lg" title="Compare versions" subtitle={prompt.name}>
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-2 gap-3">
          <VersionPicker label="Left version" value={left} versions={prompt.versions} onChange={setLeft} />
          <VersionPicker label="Right version" value={right} versions={prompt.versions} onChange={setRight} />
        </div>
        {content()}
      </div>
    </Modal>
  );
}
