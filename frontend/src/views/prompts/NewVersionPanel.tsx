// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import type { ApiError, PromptBody, PromptConfig, PromptDetail, PromptVersionView, VersionSummary } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";
import { Button, Card, ErrorNote, Field, Input, Textarea, useToast } from "../../ui";
import { parseConfig } from "./ConfigEditor";
import { ChangesPreview } from "./DiffView";
import { LabelChecklist, labelHint } from "./LabelEditor";
import { Note, Panel, VariableChips, VersionSelect } from "./promptBits";
import { hasBodyErrors, NO_BODY_ERRORS, PromptBodyEditor, validateBody } from "./PromptBodyEditor";
import { extractVariables } from "./promptCompile";
import { invalidatePromptReads } from "./promptReads";

/** An edit carried between the New version screen and the playground, on the version it started from. */
export type VersionDraft = { version: number; body: PromptBody; config: PromptConfig };

const configText = (config: PromptConfig) => (Object.keys(config).length ? JSON.stringify(config, null, 2) : "");

/**
 * The New version screen: the prompt, config, deployment labels and commit message, starting from one version, with
 * a preview of every change from it. `draft` is an edit handed over from the playground.
 */
export function NewVersionPanel({
  detail,
  versions,
  from,
  onFrom,
  draft,
  cancelHref,
  playgroundHref,
  onCreated,
}: {
  detail: PromptDetail;
  versions: VersionSummary[];
  from: PromptVersionView;
  onFrom: (version: number) => void;
  draft?: VersionDraft;
  cancelHref: string;
  playgroundHref: string;
  onCreated: (v: PromptVersionView) => void;
}) {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const [body, setBody] = useState<PromptBody>(draft?.body ?? from.body);
  const [config, setConfig] = useState(configText(draft?.config ?? from.config));
  const [labels, setLabels] = useState<string[]>([]);
  const [commit, setCommit] = useState("");
  const [bodyErrors, setBodyErrors] = useState(NO_BODY_ERRORS);
  const [configError, setConfigError] = useState<string | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const parsed = parseConfig(config);
  const elsewhere = (label: string) => detail.labels.find((l) => l.label === label)?.version;

  const create = useMutation({
    mutationFn: (value: PromptConfig) =>
      api.createPromptVersion(detail.id, { body, config: value, labels, commit_message: commit.trim() || null }),
    onMutate: () => setFailure(null),
    onSuccess: (v) => {
      invalidatePromptReads(qc, api.base, detail.id);
      toast.success(`Version ${v.version} created`);
      onCreated(v);
    },
    onError: (err) => setFailure(err as ApiError),
  });

  const submit = () => {
    const errors = validateBody(body);
    setBodyErrors(errors);
    setConfigError(parsed.ok ? null : parsed.error);
    if (parsed.ok && !hasBodyErrors(errors)) create.mutate(parsed.value);
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border pb-4">
        <div className="flex flex-wrap items-center gap-4">
          <h2 className="text-h2 text-fg">New version</h2>
          <VersionSelect label="Starts from" value={from.version} versions={versions} onChange={onFrom} />
        </div>
        <p className="text-small text-muted">Saving creates a new version. A version never changes after it is created.</p>
      </div>
      <div className="grid grid-cols-[minmax(0,1fr)_300px] items-start gap-4">
        <div className="flex min-w-0 flex-col gap-4">
          <Panel title="Prompt">
            <PromptBodyEditor type={detail.type} value={body} onChange={setBody} errors={bodyErrors} />
            <VariableChips names={extractVariables(body)} />
          </Panel>
          <Panel title="Config" aside="Optional. A JSON object that Tessary returns with the prompt, for example the model and its settings.">
            <Textarea
              aria-label="Config JSON"
              className="font-mono"
              rows={6}
              value={config}
              onChange={(e) => setConfig(e.target.value)}
            />
            {configError && <Note tone="error">{configError}</Note>}
          </Panel>
          <Panel title={`Changes from version ${from.version}`}>
            <ChangesPreview
              type={detail.type}
              from={from.body}
              body={body}
              fromConfig={from.config}
              config={parsed.ok ? parsed.value : null}
            />
          </Panel>
        </div>
        <div className="flex flex-col gap-4">
          <Card className="flex flex-col gap-3 border border-border p-4">
            <h3 className="text-h3 text-fg">Deployment labels</h3>
            <LabelChecklist
              value={labels}
              options={detail.labels.map((l) => l.label)}
              onChange={setLabels}
              rowHint={(label) => labelHint(labels.includes(label), false, elsewhere(label))}
              latest={{ checked: true }}
            />
            <p className="text-small text-muted">Leave production off to test this version first. You can promote it later.</p>
          </Card>
          <Card className="border border-border p-4">
            <Field
              label={
                <>
                  Commit message<span className="text-muted"> (optional)</span>
                </>
              }
            >
              {(p) => (
                <Input {...p} aria-label="Commit message" maxLength={500} value={commit} onChange={(e) => setCommit(e.target.value)} />
              )}
            </Field>
          </Card>
          {failure !== null && <ErrorNote error={failure} />}
          <div className="flex justify-end gap-2">
            <Button onClick={() => navigate(cancelHref)}>Cancel</Button>
            <Button variant="primary" loading={create.isPending} onClick={submit}>
              Create version
            </Button>
          </div>
          <Link
            to={playgroundHref}
            state={{ draft: { version: from.version, body, config: parsed.ok ? parsed.value : from.config } satisfies VersionDraft }}
            className="self-end text-small text-link hover:text-link-hover"
          >
            Test in playground
          </Link>
        </div>
      </div>
    </div>
  );
}
