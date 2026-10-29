// SPDX-License-Identifier: Apache-2.0
import { useState, type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import type { ApiError, PromptBody, PromptConfig, PromptType, PromptVersionView } from "../../api/types";
import { searchPatch } from "../../lib/searchPatch";
import { useProjectApi } from "../../tenant/TenantContext";
import { Button, Field, Input, Modal, useToast } from "../../ui";
import { ConfigEditor, parseConfig } from "./ConfigEditor";
import { LabelChecklist } from "./LabelEditor";
import { type BodyErrors, hasBodyErrors, NO_BODY_ERRORS, PromptBodyEditor, validateBody } from "./PromptBodyEditor";
import { invalidatePromptReads } from "./promptReads";

/** What both version forms share: body, config, deployment labels and the commit message. */
export function VersionFields({
  type,
  body,
  onBody,
  bodyErrors,
  configText,
  onConfigText,
  configError,
  labels,
  onLabels,
  commit,
  onCommit,
  afterConfig,
}: {
  type: PromptType;
  body: PromptBody;
  onBody: (b: PromptBody) => void;
  bodyErrors: BodyErrors;
  configText: string;
  onConfigText: (t: string) => void;
  configError: string | null;
  labels: string[];
  onLabels: (l: string[]) => void;
  commit: string;
  onCommit: (c: string) => void;
  afterConfig?: ReactNode;
}) {
  return (
    <>
      <PromptBodyEditor type={type} value={body} onChange={onBody} errors={bodyErrors} />
      <ConfigEditor value={configText} onChange={onConfigText} error={configError} />
      {afterConfig}
      <div className="flex flex-col gap-1.5">
        <div className="text-label uppercase text-muted">Deployment labels</div>
        <div className="rounded-card bg-raised py-1">
          <LabelChecklist value={labels} options={[]} onChange={onLabels} />
        </div>
      </div>
      <Field label="Commit message">
        {(p) => <Input {...p} maxLength={500} value={commit} onChange={(e) => onCommit(e.target.value)} />}
      </Field>
    </>
  );
}

export type VersionEditorModalProps = {
  open: boolean;
  promptId: string;
  name: string;
  type: PromptType;
  initialBody: PromptBody;
  initialConfig: PromptConfig;
  onClose: () => void;
  onCreated?: (v: PromptVersionView) => void;
};

/** Opened from the detail page's New version and the playground's Save as new version. */
export function VersionEditorModal(props: VersionEditorModalProps) {
  if (!props.open) return null;
  return <VersionEditor {...props} />;
}

function VersionEditor({ promptId, name, type, initialBody, initialConfig, onClose, onCreated }: VersionEditorModalProps) {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const [body, setBody] = useState(initialBody);
  const [configText, setConfigText] = useState(JSON.stringify(initialConfig, null, 2));
  const [labels, setLabels] = useState<string[]>([]);
  const [commit, setCommit] = useState("");
  const [bodyErrors, setBodyErrors] = useState(NO_BODY_ERRORS);
  const [configError, setConfigError] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: (config: PromptConfig) =>
      api.createPromptVersion(promptId, { body, config, labels, commit_message: commit || null }),
    onSuccess: (v) => {
      invalidatePromptReads(qc, api.base, promptId);
      searchPatch(params, setParams)({ version: String(v.version), tab: "body" });
      toast.success(`Version ${v.version} created`);
      onCreated?.(v);
      onClose();
    },
    onError: (err) => toast.error("Could not create version", (err as ApiError).message),
  });

  const submit = () => {
    const errors = validateBody(body);
    const config = parseConfig(configText);
    setBodyErrors(errors);
    setConfigError(config.ok ? null : config.error);
    if (config.ok && !hasBodyErrors(errors)) create.mutate(config.value);
  };

  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      title={`New version of ${name}`}
      footer={
        <>
          <Button size="sm" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button size="sm" variant="primary" loading={create.isPending} onClick={submit}>
            Create version
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <VersionFields
          type={type}
          body={body}
          onBody={setBody}
          bodyErrors={bodyErrors}
          configText={configText}
          onConfigText={setConfigText}
          configError={configError}
          labels={labels}
          onLabels={setLabels}
          commit={commit}
          onCommit={setCommit}
        />
      </div>
    </Modal>
  );
}
