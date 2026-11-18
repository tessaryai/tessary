// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import type { ApiError, CreatePromptRequest, PromptBody, PromptSummary, PromptType } from "../../api/types";
import { useProjectApi, useTenant } from "../../tenant/TenantContext";
import { Button, ErrorNote, Field, Input, Modal, SegmentedControl, useToast } from "../../ui";
import { parseConfig } from "./ConfigEditor";
import { hasBodyErrors, NO_BODY_ERRORS, validateBody } from "./PromptBodyEditor";
import { invalidatePromptReads } from "./promptReads";
import { parseTags, TAGS_ERROR } from "./TagsEditor";
import { VersionFields } from "./VersionEditorModal";

/** The server's name rule, in the order a reader fixes it. */
function nameError(name: string): string | null {
  if (!name) return "Enter a name.";
  if (name.length > 255) return "Use at most 255 characters.";
  if (/[\s|]/.test(name)) return "A name cannot contain spaces or |.";
  if (/^\/|\/$|\/\//.test(name)) return "Folders are separated by one /, with a name on both sides.";
  return null;
}

const TYPES = [
  { value: "text" as const, label: "Text" },
  { value: "chat" as const, label: "Chat" },
];

export function NewPromptModal({ onClose }: { onClose: () => void }) {
  const api = useProjectApi();
  const { orgSlug, projectSlug } = useTenant();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const toast = useToast();
  const [name, setName] = useState("");
  const [type, setType] = useState<PromptType>("text");
  const [bodies, setBodies] = useState<Record<PromptType, PromptBody>>({ text: "", chat: [{ role: "system", content: "" }] });
  const [configText, setConfigText] = useState("");
  const [tagsText, setTagsText] = useState("");
  const [labels, setLabels] = useState<string[]>([]);
  const [commit, setCommit] = useState("");
  const [errors, setErrors] = useState({
    name: null as string | null,
    body: NO_BODY_ERRORS,
    config: null as string | null,
    tags: false,
  });
  const [existing, setExisting] = useState<PromptSummary | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const promptsPath = `/orgs/${orgSlug}/projects/${projectSlug}/prompts`;

  const create = useMutation({
    mutationFn: (req: CreatePromptRequest) => api.createPrompt(req),
    onMutate: () => {
      setExisting(null);
      setFailure(null);
    },
    onSuccess: (d) => {
      invalidatePromptReads(qc, api.base);
      toast.success("Prompt created");
      navigate(`${promptsPath}/${encodeURIComponent(d.id)}`);
    },
    onError: async (err, req) => {
      if ((err as ApiError).code === "PROMPTS.NAME_EXISTS") {
        const page = await api.listPrompts({ name: req.name }).catch(() => null);
        const hit = page?.items[0];
        if (hit) return setExisting(hit);
      }
      setFailure(err);
    },
  });

  const submit = () => {
    const body = bodies[type];
    const config = parseConfig(configText);
    const tags = parseTags(tagsText);
    const next = { name: nameError(name), body: validateBody(body), config: config.ok ? null : config.error, tags: !tags.ok };
    setErrors(next);
    if (next.name === null && !hasBodyErrors(next.body) && config.ok && tags.ok) {
      create.mutate({ name, type, body, config: config.value, labels, tags: tags.tags, commit_message: commit || null });
    }
  };

  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      title="New prompt"
      footer={
        <>
          <Button size="sm" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button size="sm" variant="primary" loading={create.isPending} onClick={submit}>
            Create prompt
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field
          label="Name"
          hint="Use / to group prompts into folders, for example support/greeting."
          error={
            existing ? (
              <>
                A prompt named {existing.name} exists.{" "}
                <Link to={`${promptsPath}/${encodeURIComponent(existing.id)}`} className="text-link hover:text-link-hover">
                  Open it
                </Link>{" "}
                to create a new version.
              </>
            ) : (
              errors.name
            )
          }
        >
          {(p) => <Input {...p} value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <SegmentedControl ariaLabel="Type" value={type} onChange={setType} options={TYPES} />
        <VersionFields
          type={type}
          body={bodies[type]}
          onBody={(b) => setBodies({ ...bodies, [type]: b })}
          bodyErrors={errors.body}
          configText={configText}
          onConfigText={setConfigText}
          configError={errors.config}
          labels={labels}
          onLabels={setLabels}
          commit={commit}
          onCommit={setCommit}
          afterConfig={
            <Field label="Tags" hint="Separate tags with commas." error={errors.tags ? TAGS_ERROR : null}>
              {(p) => <Input {...p} value={tagsText} onChange={(e) => setTagsText(e.target.value)} />}
            </Field>
          }
        />
        {failure !== null && <ErrorNote error={failure} />}
      </div>
    </Modal>
  );
}
