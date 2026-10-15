// SPDX-License-Identifier: Apache-2.0
// "Not now" is stored in this browser only, so each teammate still sees the banner once.
import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { MessageCircleWarning } from "lucide-react";
import type { Classifier, ModelProvider } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button } from "../../ui";
import { FRUSTRATION_LANE, FrustrationEnableModal } from "./FrustrationEnableModal";

export function FrustrationBanner({ classifier }: { classifier: Classifier }) {
  const { api, orgApi, orgSlug, projectSlug } = useTenant();
  const storageKey = `tsy-frustration-banner-dismissed:${orgSlug}/${projectSlug}`;
  const [dismissed, setDismissed] = useState<boolean>(() => {
    try {
      return localStorage.getItem(storageKey) === "1";
    } catch {
      return false;
    }
  });
  const [enabling, setEnabling] = useState(false);
  const stopEnabling = () => setEnabling(false);

  const settingsQ = useQuery({ queryKey: ["model-settings", api.base], queryFn: api.getModelSettings });
  const credentialsQ = useQuery({
    queryKey: ["provider-credentials", orgApi.base],
    queryFn: orgApi.listProviderCredentials,
  });

  const storedLabel = useMemo(() => {
    const configured = new Set<ModelProvider>();
    for (const c of credentialsQ.data?.credentials ?? []) if (c.has_api_key) configured.add(c.provider);
    const options = settingsQ.data?.lanes.find((l) => l.id === FRUSTRATION_LANE)?.provider_options ?? [];
    return options.find((o) => configured.has(o.provider))?.label ?? null;
  }, [settingsQ.data, credentialsQ.data]);

  if (classifier.enabled || dismissed) return null;

  const dismiss = () => {
    setDismissed(true);
    try {
      localStorage.setItem(storageKey, "1");
    } catch {
    }
  };

  return (
    <section
      aria-label="Enable Frustration"
      className="flex items-center gap-4 mb-6 py-4 pr-4 pl-4.5 bg-surface border border-border rounded-card"
    >
      <div
        aria-hidden="true"
        className="size-9 shrink-0 flex items-center justify-center rounded-control bg-raised text-fg-secondary"
      >
        <MessageCircleWarning size={18} strokeWidth={1.75} />
      </div>
      <div className="flex-1 min-w-0">
        <div className="text-body font-medium text-fg">Frustration classifier is off</div>
        <p className="text-small text-muted m-0 mt-0.5" style={{ maxWidth: 560 }}>
          Turn it on to find sessions where users get frustrated with your agent.{" "}
          {storedLabel ? `Runs on your organization's ${storedLabel} key.` : "Runs on your OpenRouter or TypeSafe key."}
        </p>
      </div>
      <div className="flex items-center gap-2 shrink-0">
        <Button variant="ghost" onClick={dismiss}>
          Not now
        </Button>
        <Button variant="primary" onClick={() => setEnabling(true)}>
          Enable Frustration
        </Button>
      </div>
      {enabling && (
        <FrustrationEnableModal
          classifierId={classifier.id}
          onClose={stopEnabling}
          onEnabled={stopEnabling}
        />
      )}
    </section>
  );
}
