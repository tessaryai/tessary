// SPDX-License-Identifier: Apache-2.0
/*
 * FrustrationBanner: shown only while Frustration is off, "Not now" hides it for this project in this
 * browser, the copy names a key the org already holds, and Enable opens the enable modal.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Classifier, ModelSettingsResponse, ProviderCredentialListResponse } from "../../api/types";
import { FrustrationBanner } from "./FrustrationBanner";

const getModelSettings = vi.fn<() => Promise<ModelSettingsResponse>>();
const listProviderCredentials = vi.fn<() => Promise<ProviderCredentialListResponse>>();

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({
      orgSlug: "acme",
      projectSlug: "default",
      api: { base: "/api/orgs/acme/projects/default", getModelSettings },
      orgApi: { base: "/api/orgs/acme", listProviderCredentials },
    }),
  };
});

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute("open");
  };
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  getModelSettings.mockReset();
  listProviderCredentials.mockReset();
});

const SETTINGS = {
  groups: [],
  lanes: [
    {
      id: "frustration",
      label: "Frustration",
      description: "",
      group: "decision_calls",
      provider_options: [
        {
          provider: "OPENROUTER",
          label: "OpenRouter",
          model_keys: ["OPENROUTER:typesafe/jev-latest"],
          default_model_key: "OPENROUTER:typesafe/jev-latest",
        },
        {
          provider: "TYPESAFE",
          label: "TypeSafe",
          model_keys: ["TYPESAFE:jev-latest"],
          default_model_key: "TYPESAFE:jev-latest",
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

function frustration(enabled: boolean): Classifier {
  return { id: "clf-1", detector: "frustration", name: "Frustration", enabled } as Classifier;
}

function renderBanner(enabled = false) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <FrustrationBanner classifier={frustration(enabled)} />
    </QueryClientProvider>,
  );
}

describe("FrustrationBanner", () => {
  it("offers either provider's key while the org has none", async () => {
    getModelSettings.mockResolvedValue(SETTINGS);
    listProviderCredentials.mockResolvedValue({ credentials: [] });
    renderBanner();

    screen.getByText("Frustration classifier is off");
    await screen.findByText(/Runs on your OpenRouter or TypeSafe key\./);
  });

  it("names the key the org already holds", async () => {
    getModelSettings.mockResolvedValue(SETTINGS);
    listProviderCredentials.mockResolvedValue({
      credentials: [{ provider: "TYPESAFE", has_api_key: true }] as ProviderCredentialListResponse["credentials"],
    });
    renderBanner();

    await screen.findByText(/Runs on your organization's TypeSafe key\./);
  });

  it("is not shown once Frustration is on", () => {
    getModelSettings.mockResolvedValue(SETTINGS);
    listProviderCredentials.mockResolvedValue({ credentials: [] });
    renderBanner(true);

    expect(screen.queryByText("Frustration classifier is off")).toBeNull();
  });

  it("hides on Not now and stays hidden for this project", () => {
    getModelSettings.mockResolvedValue(SETTINGS);
    listProviderCredentials.mockResolvedValue({ credentials: [] });
    renderBanner();

    fireEvent.click(screen.getByRole("button", { name: "Not now" }));
    expect(screen.queryByText("Frustration classifier is off")).toBeNull();
    expect(localStorage.getItem("tsy-frustration-banner-dismissed:acme/default")).toBe("1");

    cleanup();
    renderBanner();
    expect(screen.queryByText("Frustration classifier is off")).toBeNull();
  });

  it("opens the enable modal", async () => {
    getModelSettings.mockResolvedValue(SETTINGS);
    listProviderCredentials.mockResolvedValue({ credentials: [] });
    renderBanner();

    fireEvent.click(screen.getByRole("button", { name: "Enable Frustration" }));
    await screen.findByRole("heading", { name: "Enable Frustration" });
    await screen.findByLabelText("OpenRouter API key");
  });
});
