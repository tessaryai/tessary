// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useProjectApi } from "../../tenant/TenantContext";
import { ApiError } from "../../api/types";
import type { RedactionRuleView } from "../../api/types";
import {
  Badge,
  Button,
  Field,
  Input,
  Modal,
  PageBody,
  PageHeader,
  Section,
  Spinner,
  Textarea,
  cn,
  useToast,
} from "../../ui";

/**
 * Settings → PII redaction.
 *
 * The rules authored here are the EXACT rules the server-side write-path guard applies before any trace
 * content is persisted (a producer may mirror the same redaction client-side, before transmit). The page
 * is two halves: a ledger of the
 * project's rules (built-in starters you can disable, custom rules you fully own) and a live playground
 * that previews a pattern — or the whole active rule set — against sample text before you commit it.
 *
 * Redaction is a deliberate, declared substitution — never a silent truncation: the preview shows you
 * exactly what reaches the substrate.
 */

const SAMPLE_PLACEHOLDER =
  "Paste trace content here, for example: email jane.doe@example.com, call 415-555-1234, SSN 123-45-6789.";

export function PiiRedaction() {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();

  const rulesQuery = useQuery({
    queryKey: ["redaction-rules", api.base],
    queryFn: api.listRedactionRules,
  });

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<RedactionRuleView | null>(null);
  const [name, setName] = useState("");
  const [pattern, setPattern] = useState("");
  const [replacement, setReplacement] = useState("[REDACTED]");

  // Playground state.
  const [sample, setSample] = useState("");
  const [preview, setPreview] = useState<{ redacted: string; matches: number } | null>(null);

  const invalidate = () => qc.invalidateQueries({ queryKey: ["redaction-rules", api.base] });

  const save = useMutation({
    mutationFn: () => {
      const body = { name: name.trim(), pattern, replacement };
      return editing ? api.updateRedactionRule(editing.id, body) : api.createRedactionRule(body);
    },
    onSuccess: () => {
      invalidate();
      closeModal();
      toast.success(editing ? "Rule updated" : "Rule created");
    },
    onError: (err) => toast.error("Could not save rule", (err as ApiError).message),
  });

  const toggle = useMutation({
    mutationFn: (r: RedactionRuleView) => api.setRedactionRuleEnabled(r.id, !r.enabled),
    onSuccess: () => invalidate(),
    onError: (err) => toast.error("Could not update rule", (err as ApiError).message),
  });

  const remove = useMutation({
    mutationFn: (id: string) => api.deleteRedactionRule(id),
    onSuccess: () => {
      invalidate();
      toast.success("Rule deleted");
    },
    onError: (err) => toast.error("Could not delete rule", (err as ApiError).message),
  });

  const runPreview = useMutation({
    mutationFn: () =>
      api.previewRedaction(
        pattern.trim()
          ? { pattern, replacement, sample_text: sample }
          : { sample_text: sample },
      ),
    onSuccess: (r) => setPreview({ redacted: r.redacted, matches: r.matches }),
    onError: (err) => toast.error("Preview failed", (err as ApiError).message),
  });

  function openCreate() {
    setEditing(null);
    setName("");
    setPattern("");
    setReplacement("[REDACTED]");
    setModalOpen(true);
  }

  function openEdit(r: RedactionRuleView) {
    setEditing(r);
    setName(r.name);
    setPattern(r.pattern);
    setReplacement(r.replacement);
    setModalOpen(true);
  }

  function closeModal() {
    setModalOpen(false);
    setEditing(null);
  }

  const rules = rulesQuery.data?.rules ?? [];

  return (
    <PageBody size="narrow">
      <PageHeader
        eyebrow="Security & access"
        title="PII redaction"
        subtitle="Rules applied to trace content before Tessary stores it, so unredacted PII (personally identifiable information) never reaches the platform. Built-in rules can be disabled. Custom rules are yours to write and test."
        actions={
          <Button variant="primary" onClick={openCreate}>
            New rule
          </Button>
        }
      />

      <Section>
        {rulesQuery.isLoading ? (
          <div className="flex items-center gap-2 py-12 text-small text-muted">
            <Spinner size="sm" />
            Loading rules…
          </div>
        ) : (
          <div className="overflow-hidden rounded-card border border-border">
            <div className="flex items-center gap-3 px-4 py-2.5 bg-surface border-b border-border text-column-header text-muted">
              <span className="flex-1">Rule</span>
              <span className="w-40">Replacement</span>
              <span className="w-24">State</span>
              <span className="w-28 text-right" />
            </div>
            {rules.map((r) => (
              <div
                key={r.id}
                className={cn(
                  "flex items-center gap-3 px-4 py-3 border-b border-border last:border-b-0",
                  !r.enabled && "opacity-60",
                )}
              >
                <span className="flex-1 min-w-0">
                  <span className="flex items-center gap-2">
                    <span className="text-small text-fg truncate">{r.name}</span>
                    {r.built_in && <Badge tone="neutral">Built-in</Badge>}
                  </span>
                  <span className="block mt-0.5 font-mono text-label text-muted truncate">{r.pattern}</span>
                </span>
                <span className="w-40 font-mono text-label text-muted truncate">{r.replacement}</span>
                <span className="w-24">
                  <Badge tone={r.enabled ? "success" : "neutral"}>{r.enabled ? "Enabled" : "Disabled"}</Badge>
                </span>
                <span className="w-28 flex justify-end gap-2">
                  <button
                    type="button"
                    onClick={() => toggle.mutate(r)}
                    className="text-label text-accent hover:text-accent-hover">
                    {r.enabled ? "Disable" : "Enable"}
                  </button>
                  {!r.built_in && (
                    <>
                      <button
                        type="button"
                        onClick={() => openEdit(r)}
                        className="text-label text-muted hover:text-fg">
                        Edit
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          if (confirm(`Delete "${r.name}"? This permanently deletes the rule.`)) remove.mutate(r.id);
                        }}
                        className="text-label text-error hover:text-error">
                        Delete
                      </button>
                    </>
                  )}
                </span>
              </div>
            ))}
          </div>
        )}
      </Section>

      <Section
        title="Playground"
        subtitle="Preview a pattern (or leave it blank to test the whole active rule set) against sample text. Matches are substituted, never truncated."
      >
        <Field label="Sample text">
          {(p) => (
            <Textarea
              {...p}
              rows={4}
              value={sample}
              onChange={(e) => setSample(e.target.value)}
              placeholder={SAMPLE_PLACEHOLDER}
            />
          )}
        </Field>
        <div className="mt-3 grid grid-cols-2 gap-3">
          <Field label="Pattern (optional)" hint="A Java regular expression. Leave blank to preview every enabled rule.">
            {(p) => (
              <Input {...p} value={pattern} onChange={(e) => setPattern(e.target.value)} placeholder="\\d{3}-\\d{2}-\\d{4}" />
            )}
          </Field>
          <Field label="Replacement">
            {(p) => (
              <Input {...p} value={replacement} onChange={(e) => setReplacement(e.target.value)} placeholder="[REDACTED]" />
            )}
          </Field>
        </div>
        <div className="mt-3 flex items-center gap-3">
          <Button variant="secondary" loading={runPreview.isPending} disabled={!sample} onClick={() => runPreview.mutate()}>
            Preview redaction
          </Button>
          {preview && (
            <span className="text-label text-muted">
              {preview.matches} {preview.matches === 1 ? "match" : "matches"} redacted
            </span>
          )}
        </div>
        {preview && (
          <div className="mt-3 overflow-hidden rounded-card border border-border">
            <div className="px-4 py-2 bg-surface border-b border-border text-column-header text-muted">
              Redacted result
            </div>
            <pre className="px-4 py-3.5 font-mono text-small text-fg leading-relaxed whitespace-pre-wrap overflow-x-auto">
              {preview.redacted}
            </pre>
          </div>
        )}
      </Section>

      <Modal
        open={modalOpen}
        onClose={closeModal}
        size="sm"
        title={editing ? "Edit rule" : "New rule"}
        subtitle="Every match of the pattern is replaced with the replacement text before the trace is stored."
        footer={
          <>
            <Button variant="ghost" onClick={closeModal}>
              Cancel
            </Button>
            <Button
              variant="primary"
              loading={save.isPending}
              disabled={!name.trim() || !pattern || !replacement}
              onClick={() => save.mutate()}
            >
              {editing ? "Save changes" : "Create rule"}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <Field label="Name">
            {(p) => <Input {...p} value={name} autoFocus onChange={(e) => setName(e.target.value)} placeholder="Account id" />}
          </Field>
          <Field label="Pattern" hint="A Java regular expression. Every match is redacted.">
            {(p) => <Input {...p} value={pattern} onChange={(e) => setPattern(e.target.value)} placeholder="ACC-\\d{6}" />}
          </Field>
          <Field label="Replacement">
            {(p) => (
              <Input {...p} value={replacement} onChange={(e) => setReplacement(e.target.value)} placeholder="[REDACTED]" />
            )}
          </Field>
        </div>
      </Modal>
    </PageBody>
  );
}
