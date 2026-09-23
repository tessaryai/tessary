// SPDX-License-Identifier: Apache-2.0
/*
 * Turning Groundedness off stops the sweeps, not the model. On a Mac that costs nothing, but in
 * production the GPU instance keeps waking on its schedule, so the confirmation says where to stop it.
 */
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Info } from "lucide-react";
import type { GroundednessMode } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, ErrorNote, Modal } from "../../ui";
import { groundednessStatusKey } from "./groundedness";

export function GroundednessTurnOffModal({
  classifierId,
  mode,
  onClose,
}: {
  classifierId: string;
  /** Undefined while the status is unknown: the production warning then stays out. */
  mode: GroundednessMode | undefined;
  onClose: () => void;
}) {
  const { api } = useTenant();
  const qc = useQueryClient();
  const offM = useMutation({
    mutationFn: () => api.setClassifierEnabled(classifierId, false),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["classifiers", api.base] });
      void qc.invalidateQueries({ queryKey: groundednessStatusKey(api.base, classifierId) });
      onClose();
    },
  });

  return (
    <Modal
      open
      onClose={onClose}
      title="Turn off Groundedness?"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="danger" loading={offM.isPending} onClick={() => offM.mutate()}>
            Turn off
          </Button>
        </>
      }
    >
      <p className="m-0 text-small text-fg-secondary">Findings and detections stay.</p>
      {mode === "production" && (
        <div
          className="mt-3.5 flex items-start gap-2.5 rounded-card border border-border-strong px-3.5 py-3 text-small"
          style={{ backgroundColor: "var(--color-info-subtle)" }}
        >
          <Info size={14} strokeWidth={1.75} className="text-info mt-0.5 shrink-0" aria-hidden="true" />
          <div>
            <div className="text-fg">The AWS instance keeps running</div>
            <div className="text-fg-secondary mt-0.5">
              Delete the groundedness-model stack in CloudFormation to stop charges.
            </div>
          </div>
        </div>
      )}
      {offM.isError && <ErrorNote className="mt-3" error={offM.error} />}
    </Modal>
  );
}
