// SPDX-License-Identifier: Apache-2.0
/*
 * Resetting a classifier deletes what it found and learned and checks every kept trace again, which can
 * cost real money, so it asks twice: first what goes and what it costs, then the classifier's name typed
 * back.
 */
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle } from "lucide-react";
import type { Classifier } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, ErrorNote, Input, Modal, useToast } from "../../ui";
import { FRUSTRATION_DETECTOR } from "./FrustrationEnableModal";
import { GROUNDEDNESS_DETECTOR } from "./groundedness";

const RECHECK_COST: Record<string, string> = {
  [FRUSTRATION_DETECTOR]: "Frustration calls your provider again for every user turn it checks.",
  [GROUNDEDNESS_DETECTOR]: "Groundedness runs the model again on every answer it checks.",
};

export function ClassifierResetModal({ classifier, onClose }: { classifier: Classifier; onClose: () => void }) {
  const { api } = useTenant();
  const qc = useQueryClient();
  const toast = useToast();
  const [step, setStep] = useState<"explain" | "confirm">("explain");
  const [typed, setTyped] = useState("");

  const resetM = useMutation({
    mutationFn: () => api.resetClassifier(classifier.id),
    onSuccess: () => {
      for (const key of ["classifiers", "classifier-health", "classifier-volume", "behavior-findings"]) {
        void qc.invalidateQueries({ queryKey: [key, api.base] });
      }
      void qc.invalidateQueries({ queryKey: ["classifier-events", api.base, classifier.id] });
      void qc.invalidateQueries({ queryKey: ["classifier-findings", api.base, classifier.id] });
      toast.success(`${classifier.name} reset`, "It checks every kept trace again, oldest first.");
      onClose();
    },
  });

  const confirmed = typed.trim() === classifier.name;
  const recheckCost = RECHECK_COST[classifier.detector];

  if (step === "explain") {
    return (
      <Modal
        open
        onClose={onClose}
        title={`Reset ${classifier.name}?`}
        footer={
          <>
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button variant="danger" onClick={() => setStep("confirm")}>
              Continue
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3 text-small">
          <p className="m-0 text-fg-secondary">
            {classifier.name} starts again from the oldest trace Tessary still keeps. This deletes:
          </p>
          <ul className="m-0 pl-5 text-fg-secondary">
            <li>Its detections</li>
            <li>The normal rates and baselines it learned, including baselines you accepted</li>
            <li>Its open findings that have no ruling</li>
          </ul>
          <p className="m-0 text-fg-secondary">Ruled findings and their cases stay.</p>
          <div
            className="flex items-start gap-2.5 rounded-card border border-border-strong px-3.5 py-3"
            style={{ backgroundColor: "var(--color-warning-subtle)" }}
          >
            <AlertTriangle size={14} strokeWidth={1.75} className="text-warning mt-0.5 shrink-0" aria-hidden="true" />
            <div>
              <div className="text-fg">This can cost money</div>
              <div className="text-fg-secondary mt-0.5">
                {recheckCost && <>{recheckCost} </>}
                Each new finding can start a triage run, and each run is paid.
              </div>
            </div>
          </div>
          {!classifier.enabled && (
            <p className="m-0 text-fg-secondary">
              {classifier.name} is off. It starts checking traces when you turn it on.
            </p>
          )}
        </div>
      </Modal>
    );
  }

  return (
    <Modal
      open
      onClose={onClose}
      title="Confirm reset"
      footer={
        <>
          <Button variant="ghost" onClick={() => setStep("explain")}>
            Back
          </Button>
          <Button
            variant="danger"
            disabled={!confirmed}
            loading={resetM.isPending}
            onClick={() => resetM.mutate()}
          >
            Reset
          </Button>
        </>
      }
    >
      <div className="text-small text-fg">
        Type <code className="font-mono text-error">{classifier.name}</code> to reset it.
      </div>
      <Input
        value={typed}
        onChange={(e) => setTyped(e.target.value)}
        placeholder={classifier.name}
        className="mt-2"
        aria-label="Type the classifier name to confirm the reset"
        autoFocus
      />
      {resetM.isError && <ErrorNote className="mt-3" error={resetM.error} />}
    </Modal>
  );
}
