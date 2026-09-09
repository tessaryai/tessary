// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { auth } from "../api/client";
import { ApiError } from "../api/types";
import { Button, Field, Input, Textarea } from "../ui";

export function NewProject() {
  const { orgSlug } = useParams<{ orgSlug: string }>();
  const nav = useNavigate();
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");

  const create = useMutation({
    mutationFn: () =>
      auth.createProject(orgSlug!, {
        name: name.trim(),
        description: description.trim() || null,
      }),
    // A fresh project has no tagged span, so ProjectShell puts it behind ConnectGate — the same
    // connect screen first-run gets, which is where a new project actually needs to land. Invalidate
    // the org's project list first: the route pattern doesn't change, so ProjectShell and Sidebar
    // don't remount and would otherwise resolve the new slug against a pre-creation list.
    onSuccess: async (project) => {
      await qc.invalidateQueries({ queryKey: ["projects", orgSlug] });
      nav(`/orgs/${orgSlug}/projects/${project.slug}/triage`, { replace: true });
    },
  });

  if (!orgSlug) return null;

  const error = create.isError ? ((create.error as ApiError).message ?? "Could not create project") : null;

  return (
    <div className="min-h-screen bg-bg text-fg flex items-center justify-center px-6">
      <div className="w-full max-w-md">
        <header className="mb-8">
          {/* This used to double as the org-creation wizard's "Step 2 of 2" — that numbering
              now lives on Signup/ConnectGate. This view is reached two ways today: never as part of
              first-run (a fresh account's default project already exists by the time anyone can see
              this route), and always live from the Sidebar's "+ New project" action on an
              already-onboarded org, which has nothing to do with step numbering. Plain heading. */}
          <h1 className="text-h1 text-fg">New project</h1>
          <p className="text-body text-muted mt-2">
            A project owns its sources, classifiers, cases, and tokens. You can add more later.
          </p>
        </header>

        <div className="space-y-5">
          <Field label="Project name" required>
            {(p) => (
              <Input
                {...p}
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Support agent"
                autoFocus
              />
            )}
          </Field>

          <Field label="Description" hint="Optional. A one-line description for your teammates.">
            {(p) => (
              <Textarea
                {...p}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={2}
              />
            )}
          </Field>

          {error && <div className="text-small text-error">{error}</div>}

          <Button
            variant="primary"
            className="w-full"
            onClick={() => create.mutate()}
            disabled={!name.trim()}
            loading={create.isPending}
          >
            {create.isPending ? "Creating…" : "Create project"}
          </Button>
        </div>
      </div>
    </div>
  );
}
