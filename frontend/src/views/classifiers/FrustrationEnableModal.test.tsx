// SPDX-License-Identifier: Apache-2.0
/*
 * FrustrationEnableModal: an unconfigured provider asks for its key, and confirm writes the key, then
 * the lane's model, then the enable, in that order, so a failure part way never leaves the classifier
 * on without a provider. A provider that already has a key skips the key write.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ModelSettingsResponse, ProviderCredentialListResponse } from "../../api/types";
import { FrustrationEnableModal } from "./FrustrationEnableModal";

const calls: string[] = [];
const getModelSettings = vi.fn<() => Promise<ModelSettingsResponse>>();
const listProviderCredentials = vi.fn<() => Promise<ProviderCredentialListResponse>>();
const upsertProviderCredential = vi.fn(async (provider: string, body: { api_key?: string }) => {
  calls.push(`upsert ${provider} ${body.api_key}`);
  return {};
});
const setLaneModel = vi.fn(async (lane: string, body: { model_key: string }) => {
  calls.push(`lane ${lane} ${body.model_key}`);
  return {};
});
const setClassifierEnabled = vi.fn(async (id: string, enabled: boolean) => {
  calls.push(`enable ${id} ${enabled}`);
  return {};
});

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({
      orgSlug: "acme",
      projectSlug: "default",
      api: { base: "/api/orgs/acme/projects/default", getModelSettings, setLaneModel, setClassifierEnabled },
      orgApi: { base: "/api/orgs/acme", listProviderCredentials, upsertProviderCredential },
    }),
  };
});

beforeAll(() => {
  // jsdom implements <dialog> but not showModal/close.
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute("open");
  };
});

afterEach(() => {
  cleanup();
  calls.length = 0;
  getModelSettings.mockReset();
  listProviderCredentials.mockReset();
  upsertProviderCredential.mockClear();
  setLaneModel.mockClear();
  setClassifierEnabled.mockClear();
});

const SETTINGS = {
  groups: [],
  lanes: [
    {
      id: "frustration",
      label: "Frustration",
      description: "Scores each eligible user turn.",
      group: "decision_calls",
      provider_options: [
        {
          provider: "TYPESAFE",
          label: "TypeSafe",
          model_keys: ["TYPESAFE:jev-latest"],
          default_model_key: "TYPESAFE:jev-latest",
        },
        {
          provider: "OPENROUTER",
          label: "OpenRouter",
          model_keys: ["OPENROUTER:typesafe/jev-latest"],
          default_model_key: "OPENROUTER:typesafe/jev-latest",
        },
      ],
      effective_model_key: null,
      automatic: true,
    },
  ],
  models: [],
  catalog_models: [],
  rates: [],
  settings: [],
  configured_providers: [],
} as ModelSettingsResponse;

function credential(provider: "OPENROUTER" | "TYPESAFE"): ProviderCredentialListResponse["credentials"][number] {
  return {
    id: `cred-${provider}`,
    provider,
    base_url_override: "",
    has_api_key: true,
    aws_region: "",
    has_aws_credentials: false,
    bedrock_model_arn: "",
    custom_model_name: "",
    auth_mode: "api_key",
    created_at: "2026-09-01T00:00:00Z",
    updated_at: "2026-09-01T00:00:00Z",
  };
}

function renderModal(onEnabled = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <FrustrationEnableModal classifierId="clf-1" onClose={vi.fn()} onEnabled={onEnabled} />
    </QueryClientProvider>,
  );
  return onEnabled;
}

describe("FrustrationEnableModal", () => {
  it("asks for the key of an unconfigured provider, then saves it, sets the lane and enables, in order", async () => {
    getModelSettings.mockResolvedValue(SETTINGS);
    listProviderCredentials.mockResolvedValue({ credentials: [] });
    const onEnabled = renderModal();

    const key = await screen.findByLabelText("TypeSafe API key");
    expect(screen.getByRole("button", { name: "Save key and enable" })).toHaveProperty("disabled", true);
    screen.getByText(/Sends redacted user messages to TypeSafe/);

    fireEvent.change(key, { target: { value: "ts-secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Save key and enable" }));

    await waitFor(() => expect(onEnabled).toHaveBeenCalled());
    expect(calls).toEqual(["upsert TYPESAFE ts-secret", "lane frustration TYPESAFE:jev-latest", "enable clf-1 true"]);
  });

  it("defaults to the provider that already has a key and writes no key", async () => {
    getModelSettings.mockResolvedValue(SETTINGS);
    listProviderCredentials.mockResolvedValue({ credentials: [credential("OPENROUTER")] });
    const onEnabled = renderModal();

    expect(await screen.findByLabelText(/OpenRouter/)).toHaveProperty("checked", true);
    expect(screen.queryByLabelText(/API key/)).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Enable" }));

    await waitFor(() => expect(onEnabled).toHaveBeenCalled());
    expect(calls).toEqual(["lane frustration OPENROUTER:typesafe/jev-latest", "enable clf-1 true"]);
  });

  it("shows the key field when the other, unconfigured provider is chosen", async () => {
    getModelSettings.mockResolvedValue(SETTINGS);
    listProviderCredentials.mockResolvedValue({ credentials: [credential("OPENROUTER")] });
    renderModal();

    fireEvent.click(await screen.findByLabelText(/TypeSafe/));

    await screen.findByLabelText("TypeSafe API key");
  });
});
