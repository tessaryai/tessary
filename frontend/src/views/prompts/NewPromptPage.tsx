// SPDX-License-Identifier: Apache-2.0
import { useState, type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import type { ApiError, CreatePromptRequest, PromptBody, PromptSummary, PromptType } from "../../api/types";
import { useProjectApi, useTenant } from "../../tenant/TenantContext";
import { Button, Card, ErrorNote, Field, Input, PageBody, PageHeader, SegmentedControl, Textarea, useToast } from "../../ui";
import { parseConfig } from "./ConfigEditor";
import { LabelChecklist } from "./LabelEditor";
import { Note } from "./promptBits";
import { hasBodyErrors, NO_BODY_ERRORS, PromptBodyEditor, validateBody } from "./PromptBodyEditor";
import { extractVariables } from "./promptCompile";
import { invalidatePromptReads } from "./promptReads";
import { parseTags, TAGS_ERROR } from "./TagsEditor";

/** The server's name rule, on the trimmed name, in the order a reader fixes it. */
export function nameError(name: string): string | null {
  if (!name) return "Enter a name.";
  if (name.length > 255) return "Use at most 255 characters.";
  if (name.includes("|")) return "A name cannot contain |.";
  if (/<[^<>]*>/.test(name)) return "A name cannot contain an HTML tag.";
  if (/^\/|\/$|\/\//.test(name)) return "Folders are separated by one /, with a name on both sides.";
  return null;
}

const TYPES = [
  { value: "text" as const, label: "Text" },
  { value: "chat" as const, label: "Chat" },
];

function Panel({ title, aside, children }: { title: ReactNode; aside?: ReactNode; children: ReactNode }) {
  return (
    <Card className="flex flex-col gap-3 border border-border p-5">
      <div className="flex items-baseline justify-between gap-4">
        <h2 className="text-h3 text-fg">{title}</h2>
        {aside && <p className="text-small text-muted">{aside}</p>}
      </div>
      {children}
    </Card>
  );
}

const Optional = () => <span className="text-muted"> (optional)</span>;

/** The full create form: name, type and tags are set here once; a later version changes the prompt, config and labels. */
export function NewPromptPage() {
  const api = useProjectApi();
  const { orgSlug, projectSlug } = useTenant();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const toast = useToast();
  const [params] = useSearchParams();
  const folder = params.get("folder") ?? "";
  const promptsPath = `/orgs/${orgSlug}/projects/${projectSlug}/prompts`;
  const back = () => navigate(folder ? `${promptsPath}?folder=${encodeURIComponent(folder)}` : promptsPath);

  const [name, setName] = useState(folder ? `${folder}/` : "");
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
  const body = bodies[type];
  const variables = extractVariables(body);

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
    const trimmed = name.trim();
    const config = parseConfig(configText);
    const tags = parseTags(tagsText);
    const next = { name: nameError(trimmed), body: validateBody(body), config: config.ok ? null : config.error, tags: !tags.ok };
    setErrors(next);
    if (next.name === null && !hasBodyErrors(next.body) && config.ok && tags.ok) {
      create.mutate({ name: trimmed, type, body, config: config.value, labels, tags: tags.tags, commit_message: commit || null });
    }
  };

  return (
    <PageBody>
      <PageHeader
        breadcrumb={[{ label: "Prompts", to: promptsPath }, { label: "New prompt" }]}
        title="New prompt"
        actions={<p className="text-small text-muted">Creating the prompt saves version 1.</p>}
        className="border-b border-border pb-5"
      />
      <div className="grid grid-cols-[minmax(0,1fr)_300px] items-start gap-4">
        <div className="flex min-w-0 flex-col gap-4">
          <Card className="flex flex-col gap-4 border border-border p-5">
            <Field
              label="Name"
              hint="Your agent fetches the prompt by this name. Use / to group prompts into folders, for example support/greeting."
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
              {(p) => <Input {...p} className="font-mono" value={name} onChange={(e) => setName(e.target.value)} />}
            </Field>
            <div className="flex flex-col gap-1.5">
              <div className="text-small text-fg">Type</div>
              <div>
                <SegmentedControl ariaLabel="Type" value={type} onChange={setType} options={TYPES} />
              </div>
              <p className="text-small text-muted">
                Text is one string. Chat is a list of messages. The type cannot change after you create the prompt.
              </p>
            </div>
          </Card>
          <Panel title="Prompt">
            <PromptBodyEditor type={type} value={body} onChange={(b) => setBodies({ ...bodies, [type]: b })} errors={errors.body} />
            <div className="flex flex-wrap items-center gap-1.5 text-small text-muted">
              Variables
              {variables.length === 0 ? (
                <span className="text-subtle">None yet</span>
              ) : (
                variables.map((v) => (
                  <code key={v} className="rounded-micro border border-border bg-raised px-1.5 font-mono text-code text-fg-secondary">
                    {v}
                  </code>
                ))
              )}
            </div>
          </Panel>
          <Panel title="Config" aside="Optional. A JSON object that Tessary returns with the prompt, for example the model and its settings.">
            <Textarea
              aria-label="Config JSON"
              className="font-mono"
              rows={6}
              placeholder={'{\n  "model": "your-model",\n  "temperature": 0.2\n}'}
              value={configText}
              onChange={(e) => setConfigText(e.target.value)}
            />
            {errors.config && <Note tone="error">{errors.config}</Note>}
          </Panel>
        </div>
        <div className="flex flex-col gap-4">
          <Card className="flex flex-col gap-2 border border-border py-4">
            <h2 className="px-4 text-h3 text-fg">Deployment labels</h2>
            <LabelChecklist
              value={labels}
              options={[]}
              onChange={setLabels}
              rowHint={(l) => (l === "production" ? "Fetched by default" : null)}
            />
            <p className="px-4 text-small text-muted">
              A fetch with no label gets the version that carries production. With production off, that fetch gets a
              not-found error.
            </p>
          </Card>
          <Card className="border border-border p-4">
            <Field
              label={
                <>
                  Tags
                  <Optional />
                </>
              }
              hint="Separate tags with commas. Tags group prompts in the list and change nothing an agent fetches."
              error={errors.tags ? TAGS_ERROR : null}
            >
              {(p) => <Input {...p} aria-label="Tags" value={tagsText} onChange={(e) => setTagsText(e.target.value)} />}
            </Field>
          </Card>
          <Card className="border border-border p-4">
            <Field
              label={
                <>
                  Commit message
                  <Optional />
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
            <Button onClick={back}>Cancel</Button>
            <Button variant="primary" loading={create.isPending} onClick={submit}>
              Create prompt
            </Button>
          </div>
        </div>
      </div>
    </PageBody>
  );
}
