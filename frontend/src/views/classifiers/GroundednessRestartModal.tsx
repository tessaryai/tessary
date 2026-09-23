// SPDX-License-Identifier: Apache-2.0
/*
 * The model stopped answering, and restarting it is the setup guide's restart section, run by a coding
 * agent on the machine that hosts it. This hands over that prompt for where the model runs, then waits:
 * it polls the status and closes itself once the model is scoring again.
 */
import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import type { GroundednessMode } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, Modal, Spinner } from "../../ui";
import { PromptBlock } from "../components/PromptBlock";
import { SETUP_POLL_MS } from "./GroundednessEnableModal";
import { groundednessStatusKey, restartPrompt, setupGuide } from "./groundedness";

export function GroundednessRestartModal({
  classifierId,
  mode,
  setupRef,
  onClose,
}: {
  classifierId: string;
  mode: GroundednessMode;
  setupRef: string;
  onClose: () => void;
}) {
  const { api } = useTenant();
  const statusQ = useQuery({
    queryKey: groundednessStatusKey(api.base, classifierId),
    queryFn: () => api.getGroundednessStatus(classifierId),
    refetchInterval: SETUP_POLL_MS,
    retry: false,
  });

  const scoring = statusQ.isSuccess && statusQ.data.state === "on";
  useEffect(() => {
    if (scoring) onClose();
  }, [scoring, onClose]);

  return (
    <Modal
      open
      onClose={onClose}
      title="Restart model"
      footer={
        <Button variant="secondary" onClick={onClose}>
          Close
        </Button>
      }
    >
      <div className="flex flex-col gap-3.5">
        <PromptBlock
          label="Paste into your coding agent"
          prompt={restartPrompt(mode, setupRef)}
          highlight={`${setupGuide(mode, setupRef)}#restart`}
          onCopy={() => {}}
        />
        <div className="flex items-center gap-2.5 rounded-card bg-raised px-3.5 py-3 text-small text-fg">
          <Spinner size="sm" className="text-fg" />
          <span>Waiting for the model...</span>
        </div>
      </div>
    </Modal>
  );
}
