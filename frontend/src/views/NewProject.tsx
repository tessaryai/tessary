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
    // The route pattern does not change, so ProjectShell and Sidebar would resolve the new slug against a
    // stale project list without this invalidation.
    onSuccess: async (project) => {
      await qc.invalidateQueries({ queryKey: ["projects", orgSlug] });
      nav(`/orgs/${orgSlug}/projects/${project.slug}/triage`, { replace: true });
    },
  });

  const error = create.isError ? (create.error as ApiError).message : null;

  return (
    <div className="min-h-screen bg-bg text-fg flex items-center justify-center px-6">
      <div className="w-full max-w-md">
        <header className="mb-8">
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
