// SPDX-License-Identifier: Apache-2.0
/*
 * Enabling Groundedness needs its model running somewhere first, and a coding agent does that setup,
 * so the switch opens this instead of flipping: pick where the model runs, copy the prompt that points
 * the agent at the matching setup guide, and watch the three steps tick over.
 *
 * The modal learns each step from the status endpoint, polled while it is open. The agent restarts
 * Tessary part way through, so a read that fails because nothing is answering is the "Restart Tessary"
 * step, not an error. Once the model answers, the modal enables the classifier itself, once.
 */
import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Circle } from "lucide-react";
import type { GroundednessMode } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, ErrorNote, LoadingRow, Modal, SegmentedControl, Spinner, cn } from "../../ui";
import { PromptBlock } from "../components/PromptBlock";
import {
  MODE_LABEL,
  SETUP_REQUIREMENTS,
  groundednessStatusKey,
  isRestartingError,
  setupGuide,
  setupPrompt,
} from "./groundedness";

/** `ConnectGate`'s interval: fast enough that the steps tick over while the user watches. */
export const SETUP_POLL_MS = 3500;

const STEPS = ["Set up model", "Restart Tessary", "Start scoring"] as const;

export function GroundednessEnableModal({
  classifierId,
  onClose,
  onEnabled,
  onPromptCopied,
}: {
  classifierId: string;
  onClose: () => void;
  onEnabled: () => void;
  onPromptCopied: () => void;
}) {
  const { api } = useTenant();
  const qc = useQueryClient();
  const statusKey = groundednessStatusKey(api.base, classifierId);

  const statusQ = useQuery({
    queryKey: statusKey,
    queryFn: () => api.getGroundednessStatus(classifierId),
    refetchInterval: SETUP_POLL_MS,
    // A failed read is the restart step: the next poll is the retry.
    retry: false,
  });
  const status = statusQ.data;

  const [picked, setPicked] = useState<GroundednessMode | null>(null);
  const mode = picked ?? status?.mode ?? "dev";
  const ref = status?.setup_ref ?? "main";

  const restarting = statusQ.isError && isRestartingError(statusQ.error);
  const [sawRestart, setSawRestart] = useState(false);
  useEffect(() => {
    if (restarting) setSawRestart(true);
  }, [restarting]);

  // Whether the model URL was set when the modal opened: set later means the setup wrote the .env and
  // Tessary came back with it, so the restart is done or under way.
  const [initialConfigured, setInitialConfigured] = useState<boolean | null>(null);
  useEffect(() => {
    if (status && initialConfigured === null) setInitialConfigured(status.configured);
  }, [status, initialConfigured]);

  const enableM = useMutation({
    mutationFn: () => api.setClassifierEnabled(classifierId, true),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["classifiers", api.base] });
      void qc.invalidateQueries({ queryKey: statusKey });
      onEnabled();
    },
  });

  const available = statusQ.isSuccess && status?.available === true;
  const enabledOnce = useRef(false);
  useEffect(() => {
    if (!available || enabledOnce.current) return;
    enabledOnce.current = true;
    enableM.mutate();
  }, [available, enableM.mutate]);

  const done = enableM.isSuccess;
  const active = done
    ? STEPS.length
    : available
      ? 2
      : restarting || sawRestart || (initialConfigured === false && status?.configured === true)
        ? 1
        : 0;

  return (
    <Modal
      open
      onClose={onClose}
      title="Enable Groundedness"
      footer={
        done ? (
          <Button variant="primary" onClick={onClose}>
            Done
          </Button>
        ) : (
          <Button variant="secondary" onClick={onClose}>
            Close
          </Button>
        )
      }
    >
      {done ? (
        <div className="flex flex-col gap-3.5">
          <div
            className="flex items-center gap-2.5 rounded-card px-3.5 py-3 text-small text-fg"
            style={{ backgroundColor: "var(--color-success-subtle)" }}
          >
            <Check size={15} strokeWidth={2} className="text-success shrink-0" aria-hidden="true" />
            Groundedness enabled
          </div>
          <SetupSteps active={active} />
        </div>
      ) : statusQ.isPending ? (
        <LoadingRow />
      ) : (
        <div className="flex flex-col gap-3.5">
          <div>
            <div className="font-mono text-label uppercase text-muted mb-2">Where the model runs</div>
            <SegmentedControl<GroundednessMode>
              ariaLabel="Where the model runs"
              value={mode}
              onChange={setPicked}
              options={[
                { value: "dev", label: MODE_LABEL.dev },
                { value: "production", label: MODE_LABEL.production },
              ]}
            />
          </div>
          <PromptBlock
            label="Paste into your coding agent"
            prompt={setupPrompt(mode, ref)}
            highlight={setupGuide(mode, ref)}
            onCopy={onPromptCopied}
          />
          <p className="m-0 text-small text-muted">{SETUP_REQUIREMENTS[mode]}</p>
          <SetupSteps active={active} />
          {statusQ.isError && !restarting && <ErrorNote error={statusQ.error} />}
          {enableM.isError && <ErrorNote error={enableM.error} />}
          <p className="m-0 text-small text-muted">You can close this. Setup continues.</p>
        </div>
      )}
    </Modal>
  );
}

/**
 * The three setup steps: done before `active`, in progress at it, waiting after it. Local on purpose:
 * `ui/` has no stepper, and one use doesn't make one.
 */
function SetupSteps({ active }: { active: number }) {
  return (
    <ol
      aria-label="Setup steps"
      className="m-0 flex flex-col gap-2.25 rounded-card bg-raised px-3.5 py-3"
      style={{ listStyle: "none" }}
    >
      {STEPS.map((label, i) => {
        const state = i < active ? "done" : i === active ? "active" : "waiting";
        return (
          <li
            key={label}
            aria-current={state === "active" ? "step" : undefined}
            className={cn(
              "flex items-center gap-2.5 text-small",
              state === "done" ? "text-fg-secondary" : state === "active" ? "text-fg" : "text-subtle",
            )}
          >
            <span className="flex size-4.5 shrink-0 items-center justify-center">
              {state === "done" ? (
                <span
                  className="flex size-4.5 items-center justify-center rounded-full text-success"
                  style={{ backgroundColor: "var(--color-success-subtle)" }}
                >
                  <Check size={11} strokeWidth={2.5} aria-hidden="true" />
                </span>
              ) : state === "active" ? (
                <Spinner size="sm" className="text-fg" />
              ) : (
                <Circle size={14} strokeWidth={1.75} aria-hidden="true" />
              )}
            </span>
            {label}
          </li>
        );
      })}
    </ol>
  );
}
