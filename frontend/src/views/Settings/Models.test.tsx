// SPDX-License-Identifier: Apache-2.0
/*
 * Models.tsx: a "Decision models" lane is a provider select and nothing else. Each provider serves
 * one decision model, so the server sends `model_selectable: false` for the group and the row must
 * draw no model, tier or effort control. An agent lane beside it keeps its model select.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { CatalogEntry, ModelSettingsResponse } from "../../api/types";
import { ToastProvider } from "../../ui/Toast";
import { Models } from "./Models";

const getModelSettings = vi.fn<() => Promise<ModelSettingsResponse>>();
const setLaneModel = vi.fn();
const resetLaneModel = vi.fn();

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useProjectApi: () => ({
      base: "/api/orgs/acme/projects/default",
      getModelSettings,
      setLaneModel,
      resetLaneModel,
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

/*
 * Choosing: a provider pins its own default model, Automatic is a delete rather than a write, and a
 * triage model priced above its provider's default takes two confirmations, because triage runs
 * unattended once per finding and every run bills the org's own key.
 */
const TRIAGE: ModelSettingsResponse = {
  ...SETTINGS,
  lanes: [
    {
      id: "triage",
      label: "Triage",
      description: "Rules on each finding.",
      group: "agent_vm",
      provider_options: [
        {
          provider: "BEDROCK",
          label: "Amazon Bedrock",
          model_keys: ["claude-haiku", "claude-opus", "mystery-model", "gpt-luna"],
          default_model_key: "claude-haiku",
        },
        {
          provider: "OPENROUTER",
          label: "OpenRouter",
          model_keys: ["OPENROUTER:openai/gpt-5.6-terra", "OPENROUTER:other"],
          default_model_key: "OPENROUTER:openai/gpt-5.6-terra",
        },
      ],
      effective_model_key: "claude-haiku",
      automatic: true,
    },
  ],
  models: [
    { model_key: "claude-haiku", display_name: "Claude Haiku", vendor: "Anthropic", agentic: true, endpoint: "RUNTIME", inference_profile_id: "p1" },
    { model_key: "claude-opus", display_name: "Claude Opus", vendor: "Anthropic", agentic: true, endpoint: "RUNTIME", inference_profile_id: "p2" },
    { model_key: "gpt-luna", display_name: "GPT Luna", vendor: "OpenAI", agentic: true, endpoint: "MANTLE", inference_profile_id: "p3" },
  ],
  rates: [
    { model_key: "claude-haiku", input_rate_per_million: 1, output_rate_per_million: 5 },
    { model_key: "claude-opus", input_rate_per_million: 15, output_rate_per_million: null },
  ],
  configured_providers: ["BEDROCK", "OPENROUTER"],
};

const lane = (label: string) => screen.getByLabelText(label).closest<HTMLElement>("div.rounded-card")!;

describe("choosing a model", () => {
  it("marks a pricier triage model and asks twice before setting it", async () => {
    getModelSettings.mockResolvedValue(TRIAGE);
    setLaneModel.mockResolvedValue({ ...TRIAGE, lanes: [{ ...TRIAGE.lanes[0], effective_model_key: "claude-opus", automatic: false }] });
    renderModels();

    const model = (await screen.findByLabelText("Triage model")) as HTMLSelectElement;
    expect(Array.from(model.options).map((o) => o.textContent)).toEqual(["Claude Haiku", "Claude Opus · higher cost", "mystery-model", "GPT Luna"]);

    fireEvent.change(model, { target: { value: "claude-opus" } });
    expect(screen.getByRole("heading", { name: "This model costs more per triage run" })).toBeTruthy();
    expect(screen.getByText(/Claude Opus costs \$15 per million input tokens, more than Claude Haiku's \$1 per million input tokens and \$5 per million output tokens/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(setLaneModel).not.toHaveBeenCalled();

    fireEvent.change(model, { target: { value: "claude-opus" } });
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    fireEvent.click(screen.getByRole("button", { name: "Set Claude Opus on Triage" }));

    await waitFor(() => expect(setLaneModel).toHaveBeenCalledWith("triage", { model_key: "claude-opus" }));
    expect(await screen.findByText("Model updated")).toBeTruthy();
    await waitFor(() => expect((screen.getByLabelText("Triage provider") as HTMLSelectElement).value).toBe("BEDROCK"));
  });

  it("sets an unpriced model directly, and a provider pins that provider's default", async () => {
    getModelSettings.mockResolvedValue(TRIAGE);
    setLaneModel.mockResolvedValue(TRIAGE);
    renderModels();

    fireEvent.change(await screen.findByLabelText("Triage model"), { target: { value: "mystery-model" } });
    await waitFor(() => expect(setLaneModel).toHaveBeenLastCalledWith("triage", { model_key: "mystery-model" }));

    fireEvent.change(screen.getByLabelText("Triage provider"), { target: { value: "OPENROUTER" } });
    await waitFor(() => expect(setLaneModel).toHaveBeenLastCalledWith("triage", { model_key: "OPENROUTER:openai/gpt-5.6-terra" }));
  });

  it("sets a priced triage model directly when its provider's default has no price to compare", async () => {
    const unpricedDefault = {
      ...TRIAGE,
      lanes: [
        {
          ...TRIAGE.lanes[0],
          provider_options: [{ ...TRIAGE.lanes[0].provider_options[0], default_model_key: "mystery-model" }, TRIAGE.lanes[0].provider_options[1]],
        },
      ],
    };
    getModelSettings.mockResolvedValue(unpricedDefault);
    setLaneModel.mockResolvedValue(unpricedDefault);
    renderModels();

    fireEvent.change(await screen.findByLabelText("Triage model"), { target: { value: "claude-opus" } });

    await waitFor(() => expect(setLaneModel).toHaveBeenCalledWith("triage", { model_key: "claude-opus" }));
    expect(screen.queryByRole("heading", { name: "This model costs more per triage run" })).toBeNull();
  });

  it("never sets a model served through a provider the org holds no key for", async () => {
    getModelSettings.mockResolvedValue(TRIAGE);
    renderModels();

    fireEvent.change(await screen.findByLabelText("Triage model"), { target: { value: "gpt-luna" } });
    await new Promise((r) => setTimeout(r, 0));

    expect(setLaneModel).not.toHaveBeenCalled();
  });

  it("returns a pinned lane to Automatic by deleting the choice, and says when a change is refused", async () => {
    const pinned = { ...TRIAGE, lanes: [{ ...TRIAGE.lanes[0], automatic: false }] };
    getModelSettings.mockResolvedValue(pinned);
    resetLaneModel.mockRejectedValueOnce(new Error("provider key revoked")).mockResolvedValueOnce(TRIAGE);
    setLaneModel.mockRejectedValue(new Error("not offered"));
    renderModels();

    const provider = (await screen.findByLabelText("Triage provider")) as HTMLSelectElement;
    expect(provider.value).toBe("BEDROCK");
    fireEvent.change(provider, { target: { value: "" } });
    expect(await screen.findByText("provider key revoked")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Triage provider"), { target: { value: "" } });
    await waitFor(() => expect(resetLaneModel).toHaveBeenCalledTimes(2));
    expect(resetLaneModel).toHaveBeenLastCalledWith("triage");
    expect(await screen.findByText("Model updated")).toBeTruthy();

    fireEvent.change(screen.getByLabelText("Triage model"), { target: { value: "mystery-model" } });
    expect(await screen.findByText("not offered")).toBeTruthy();
  });

  it("says when a stored choice can no longer run, and when there is no key at all", async () => {
    getModelSettings.mockResolvedValue({
      ...TRIAGE,
      settings: [{ lane: "triage", model_key: "gpt-luna", project_id: "p", created_at: "", updated_at: "", reasoning_effort: null, service_tier: "standard" }],
    });
    renderModels();
    expect(await screen.findByText("GPT Luna is no longer available")).toBeTruthy();
    cleanup();

    getModelSettings.mockResolvedValue({
      ...TRIAGE,
      lanes: [{ ...TRIAGE.lanes[0], automatic: false }],
      settings: [{ lane: "triage", model_key: "claude-haiku", project_id: "p", created_at: "", updated_at: "", reasoning_effort: null, service_tier: "standard" }],
    });
    renderModels();
    await screen.findByLabelText("Triage provider");
    expect(screen.queryByText(/is no longer available/)).toBeNull();
    cleanup();

    getModelSettings.mockResolvedValue({ ...TRIAGE, configured_providers: [] });
    renderModels();
    expect(await screen.findByText("Add a provider key to choose models")).toBeTruthy();
    const provider = screen.getByLabelText("Triage provider") as HTMLSelectElement;
    expect(Array.from(provider.options).map((o) => o.textContent)).toEqual(["No provider configured"]);
    expect(provider.disabled).toBe(true);
    expect(within(lane("Triage model")).getByRole("option", { name: "No model" })).toBeTruthy();
  });

  it("shows the read loading and failing", async () => {
    getModelSettings.mockReturnValue(new Promise(() => {}));
    renderModels();
    expect(screen.queryByRole("heading", { name: "Decision models" })).toBeNull();
    cleanup();

    getModelSettings.mockRejectedValue(new Error("settings unavailable"));
    renderModels();
    expect(await screen.findByText("settings unavailable")).toBeTruthy();
  });

  it("sets nothing when either price confirmation is dismissed", async () => {
    getModelSettings.mockResolvedValue(TRIAGE);
    renderModels();
    const model = await screen.findByLabelText("Triage model");

    fireEvent.change(model, { target: { value: "claude-opus" } });
    fireEvent.keyDown(screen.getAllByRole("dialog")[0], { key: "Escape" });
    fireEvent(screen.getAllByRole("dialog")[0], new Event("cancel", { cancelable: true }));
    expect(screen.queryByRole("heading", { name: "This model costs more per triage run" })).toBeNull();

    fireEvent.change(model, { target: { value: "claude-opus" } });
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("heading", { name: "Change the triage model?" })).toBeNull();

    fireEvent.change(model, { target: { value: "claude-opus" } });
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    expect(screen.queryByRole("heading", { name: "Change the triage model?" })).toBeNull();
    expect(setLaneModel).not.toHaveBeenCalled();
  });
});
