// SPDX-License-Identifier: Apache-2.0
/*
 * Models.tsx: a "Decision models" lane is a provider select and nothing else. Each provider serves
 * one decision model, so the server sends `model_selectable: false` for the group and the row must
 * draw no model, tier or effort control. An agent lane beside it keeps its model select.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { CatalogEntry, ModelSettingsResponse } from "../../api/types";
import { ToastProvider } from "../../ui/Toast";
import { Models } from "./Models";

const getModelSettings = vi.fn<() => Promise<ModelSettingsResponse>>();

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useProjectApi: () => ({
      base: "/api/orgs/acme/projects/default",
      getModelSettings,
      setLaneModel: vi.fn(),
      resetLaneModel: vi.fn(),
    }),
  };
});

function entry(provider: CatalogEntry["provider"], modelName: string, flags: Partial<CatalogEntry>): CatalogEntry {
  return {
    provider,
    vendor: "Vendor",
    model_name: modelName,
    display_name: modelName,
    strict_json_schema: false,
    effort_levels: [],
    default_base_url: "https://example.invalid",
    agentic: false,
    decision: false,
    ...flags,
  };
}

const SETTINGS: ModelSettingsResponse = {
  groups: [
    {
      id: "agent_vm",
      label: "Agent in a VM",
      description: "A model id handed to an agent.",
      model_selectable: true,
    },
    {
      id: "decision_calls",
      label: "Decision models",
      description: "One question per turn.",
      model_selectable: false,
    },
  ],
  lanes: [
    {
      id: "rca",
      label: "RCA",
      description: "Investigates a mover.",
      group: "agent_vm",
      provider_options: [
        {
          provider: "OPENROUTER",
          label: "OpenRouter",
          model_keys: ["OPENROUTER:openai/gpt-5.6-terra"],
          default_model_key: "OPENROUTER:openai/gpt-5.6-terra",
        },
      ],
      effective_model_key: "OPENROUTER:openai/gpt-5.6-terra",
      automatic: true,
    },
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
      effective_model_key: "TYPESAFE:jev-latest",
      automatic: true,
    },
  ],
  models: [],
  catalog_models: [
    entry("OPENROUTER", "openai/gpt-5.6-terra", { agentic: true }),
    entry("TYPESAFE", "jev-latest", { decision: true }),
    entry("OPENROUTER", "typesafe/jev-latest", { decision: true }),
  ],
  rates: [],
  settings: [],
  configured_providers: ["TYPESAFE", "OPENROUTER"],
};

function renderModels() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={qc}>
        <ToastProvider>
          <Models />
        </ToastProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("Models", () => {
  it("renders the decision group with a provider select and no model control", async () => {
    getModelSettings.mockResolvedValue(SETTINGS);
    renderModels();

    expect(await screen.findByRole("heading", { name: "Decision models" })).toBeTruthy();
    const provider = screen.getByLabelText("Frustration provider") as HTMLSelectElement;
    const options = Array.from(provider.options).map((o) => o.textContent);
    expect(options).toEqual(["Automatic (TypeSafe)", "TypeSafe", "OpenRouter"]);

    expect(screen.queryByLabelText("Frustration model")).toBeNull();
    expect(screen.getByLabelText("RCA model")).toBeTruthy();
  });
});
