// SPDX-License-Identifier: Apache-2.0
/*
 * Enabling Frustration spends the org's own provider credit, so the switch opens this instead of
 * flipping: pick the provider the lane runs on, add its key if the org has none, and read what will be
 * sent and what it costs before anything is.
 *
 * Confirm is three writes in order: the key (only when the chosen provider has none), the lane's
 * model, then the enable. The server refuses the enable without a usable key, so a failure part way
 * leaves the classifier off, never on without a provider.
 */
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ModelProvider } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, ErrorNote, Field, Input, LoadingRow, Modal } from "../../ui";

/** `ModelLane.FRUSTRATION`'s wire name. */
export const FRUSTRATION_LANE = "frustration";

/** Frustration's detector key: enabling it spends the org's own provider credit, so it opens this modal. */
export const FRUSTRATION_DETECTOR = "frustration";

export function FrustrationEnableModal({
  classifierId,
  onClose,
  onEnabled,
}: {
  classifierId: string;
  onClose: () => void;
  onEnabled: () => void;
}) {
  const { api, orgApi } = useTenant();
  const qc = useQueryClient();

  const settingsQ = useQuery({ queryKey: ["model-settings", api.base], queryFn: api.getModelSettings });
  const credentialsQ = useQuery({
    queryKey: ["provider-credentials", orgApi.base],
    queryFn: orgApi.listProviderCredentials,
  });

  const options = useMemo(
    () => settingsQ.data?.lanes.find((l) => l.id === FRUSTRATION_LANE)?.provider_options ?? [],
    [settingsQ.data],
  );
  const configured = useMemo(() => {
    const out = new Set<ModelProvider>();
    for (const c of credentialsQ.data?.credentials ?? []) if (c.has_api_key) out.add(c.provider);
    return out;
  }, [credentialsQ.data]);

  const [picked, setPicked] = useState<ModelProvider | null>(null);
  const [apiKey, setApiKey] = useState("");

  const choice =
    options.find((o) => o.provider === picked) ?? options.find((o) => configured.has(o.provider)) ?? options[0];
  const needsKey = choice != null && !configured.has(choice.provider);

  const enableM = useMutation({
    mutationFn: async () => {
      if (!choice) throw new Error("No provider offers the Frustration lane.");
      if (needsKey) await orgApi.upsertProviderCredential(choice.provider, { api_key: apiKey.trim() });
      await api.setLaneModel(FRUSTRATION_LANE, { model_key: choice.default_model_key });
      return api.setClassifierEnabled(classifierId, true);
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: ["provider-credentials", orgApi.base] });
      void qc.invalidateQueries({ queryKey: ["model-settings", api.base] });
      void qc.invalidateQueries({ queryKey: ["classifiers", api.base] });
    },
    onSuccess: () => onEnabled(),
  });

  const loading = settingsQ.isLoading || credentialsQ.isLoading;
  const loadError = settingsQ.error ?? credentialsQ.error;
  const canConfirm = choice != null && (!needsKey || apiKey.trim().length > 0) && !enableM.isPending;

  return (
    <Modal
      open
      onClose={onClose}
      title="Enable Frustration"
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" loading={enableM.isPending} disabled={!canConfirm} onClick={() => enableM.mutate()}>
            {needsKey ? "Save key and enable" : "Enable"}
          </Button>
        </>
      }
    >
      {loading && <LoadingRow />}
      {loadError && <ErrorNote error={loadError} />}
      {!loading && !loadError && options.length === 0 && (
        <p className="text-small text-muted m-0">No provider offers the Frustration lane in this build.</p>
      )}
      {choice && (
        <>
          <div id="frustration-provider-label" className="text-small font-medium text-fg-secondary mb-1.5">
            Provider
          </div>
          <div
            className="rounded-card border border-border divide-y divide-border"
            role="radiogroup"
            aria-labelledby="frustration-provider-label"
          >
            {options.map((o) => (
              <label key={o.provider} className="flex items-start gap-3 px-4 py-3 cursor-pointer">
                <input
                  type="radio"
                  name="frustration-provider"
                  value={o.provider}
                  checked={choice.provider === o.provider}
                  disabled={enableM.isPending}
                  onChange={() => setPicked(o.provider)}
                  className="mt-1"
                />
                <span className="min-w-0">
                  <span className="block text-small text-fg">{o.label}</span>
                  <span className="block text-label text-muted">
                    {configured.has(o.provider) ? "Key stored" : "No key added"}
                  </span>
                </span>
              </label>
            ))}
          </div>

          {needsKey && (
            <Field
              className="mt-3.5"
              label={`${choice.label} API key`}
              hint="Tessary encrypts the key and uses it across your organization."
            >
              {(p) => (
                <Input
                  {...p}
                  type="password"
                  autoComplete="off"
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                />
              )}
            </Field>
          )}

          <div className="mt-3.5 text-small">
            <div className="font-medium text-fg-secondary">Once enabled, Frustration</div>
            <ul className="text-muted mt-1.5 mb-0 pl-4.5 list-disc flex flex-col gap-1">
              <li>Sends redacted user messages to {choice.label} for TypeSafe's Jev model to score.</li>
              <li>Costs about $0.04 per 1,000 messages on your {choice.label} key.</li>
              <li>
                Learns each call site's normal rate from its first 200 conversations, then opens a case when the
                rate rises.
              </li>
            </ul>
          </div>
        </>
      )}
      {enableM.isError && <ErrorNote className="mt-3" error={enableM.error} />}
    </Modal>
  );
}
