// SPDX-License-Identifier: Apache-2.0
/*
 * Providers.tsx's first test — none existed before this file (only
 * src/routeManifest.smoke.test.tsx covered this view, and only for "renders something without
 * throwing" under mocked-empty data; see that file's own header).
 *
 * The real bug this pins (AC1 inverted): the component gated its error branch on
 * `catalog.isError` alone. When the credentials query failed and the catalog query succeeded,
 * `credByProvider` silently built an empty map and every platform rendered "Not configured" — a
 * false empty state indistinguishable from a genuinely fresh, keyless install. Fixed to
 * `catalog.isError || credentials.isError`.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type {
  ModelProvider,
  PlatformDescriptor,
  ProviderCatalogResponse,
  ProviderCredentialListResponse,
  ProviderCredentialView,
  UpsertProviderCredentialRequest,
} from "../../api/types";
import { ApiError } from "../../api/types";
import { ToastProvider } from "../../ui/Toast";
import { Providers } from "./Providers";

// ---- collaborator mocks ------------------------------------------------------------------
// Providers.tsx reads its API surface via useOrgApi() (TenantContext) — provider
// credentials moved off the project-scoped API.

const listProviderCatalog = vi.fn<() => Promise<ProviderCatalogResponse>>();
const listProviderCredentials = vi.fn<() => Promise<ProviderCredentialListResponse>>();
const upsertProviderCredential =
  vi.fn<(provider: ModelProvider, body: UpsertProviderCredentialRequest) => Promise<unknown>>();
const deleteProviderCredential = vi.fn<(provider: ModelProvider) => Promise<unknown>>();

// A full-replacement vi.mock() here (dropping every export but useOrgApi) leaked across
// vitest's shared module registry into src/routeManifest.smoke.test.tsx running in the same
// worker, breaking every route that calls useTenant() with "No useTenant export is defined on
// the mock". importOriginal keeps every real export except the one this file actually stubs.
vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useOrgApi: () => ({
      base: "/api/orgs/acme",
      listProviderCatalog,
      listProviderCredentials,
      upsertProviderCredential,
      deleteProviderCredential,
    }),
  };
});

// ---- fixtures ------------------------------------------------------------------------------
// Ollama (the platform's one AUTH_NONE, platform-funded provider) was dropped by the
// maker filter — every provider now requires a key, so ANTHROPIC stands in as the second fixture
// instead, and `platform_funded` is gone from PlatformDescriptor entirely.

const OPENAI: PlatformDescriptor = {
  id: "OPENAI",
  label: "OpenAI",
  auth: "api_key",
  default_base_url: "https://api.openai.com/v1",
  supports_base_url: true,
  used_by: [],
};

const ANTHROPIC: PlatformDescriptor = {
  id: "ANTHROPIC",
  label: "Anthropic",
  auth: "api_key",
  default_base_url: "https://api.anthropic.com/v1",
  supports_base_url: true,
  used_by: [],
};

const BEDROCK: PlatformDescriptor = {
  id: "BEDROCK",
  label: "Amazon Bedrock",
  auth: "aws",
  default_base_url: "",
  supports_base_url: false,
  used_by: [],
};

/** A stored credential as the backend's View sends it: has_* booleans, never the secret itself. */
function storedCred(provider: ModelProvider, over: Partial<ProviderCredentialView> = {}): ProviderCredentialView {
  return {
    id: `cred_${provider}`,
    provider,
    base_url_override: "",
    has_api_key: false,
    aws_region: "",
    has_aws_credentials: false,
    bedrock_model_arn: "",
    custom_model_name: "",
    auth_mode: "api_key",
    created_at: "2026-01-01T00:00:00Z",
    updated_at: "2026-01-01T00:00:00Z",
    ...over,
  };
}

/** Opens the stored credential's modal, saves it untouched or as edited by `edit`, returns the body sent. */
async function saveEdit(label: string, edit: () => void = () => {}): Promise<UpsertProviderCredentialRequest> {
  await waitFor(() => screen.getByText(label));
  const row = screen.getByText(label).closest("div")!.parentElement!;
  fireEvent.click(within(row).getByRole("button", { name: "Edit key" }));
  edit();
  fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
  await waitFor(() => expect(upsertProviderCredential).toHaveBeenCalledTimes(1));
  return upsertProviderCredential.mock.calls[0][1];
}

const TYPESAFE: PlatformDescriptor = {
  id: "TYPESAFE",
  label: "TypeSafe",
  auth: "api_key",
  default_base_url: "https://api.typesafe.ai",
  supports_base_url: true,
  used_by: ["frustration"],
};

function renderProviders() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <Providers />
      </ToastProvider>
    </QueryClientProvider>,
  );
}

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
  // vi.resetAllMocks() is a WORKER-GLOBAL reset, not file-scoped -- when this file runs in the
  // same vitest worker as src/routeManifest.smoke.test.tsx, it silently wiped out that file's own
  // vi.fn()-backed mock implementations (e.g. auth.getCapabilities) mid-run, breaking dozens of
  // unrelated route mounts with "No queryFn was passed" errors. Reset only this file's own mocks.
  listProviderCatalog.mockReset();
  listProviderCredentials.mockReset();
  upsertProviderCredential.mockReset();
  deleteProviderCredential.mockReset();
  vi.restoreAllMocks();
});

describe("Providers", () => {
  it("names what a single-purpose provider is for, and says nothing on a chat provider", async () => {
    listProviderCatalog.mockResolvedValue({ platforms: [OPENAI, TYPESAFE], models: [] });
    listProviderCredentials.mockResolvedValue({ credentials: [] });

    renderProviders();

    await waitFor(() => screen.getByText("TypeSafe"));
    expect(screen.getAllByText(/^Used by /)).toHaveLength(1);
    screen.getByText("Used by Frustration");
  });

  it("renders Not configured for a healthy catalog with zero stored credentials", async () => {
    listProviderCatalog.mockResolvedValue({ platforms: [OPENAI, ANTHROPIC], models: [] });
    listProviderCredentials.mockResolvedValue({ credentials: [] });

    renderProviders();

    await waitFor(() => screen.getByText("OpenAI"));

    const openAiRow = screen.getByText("OpenAI").closest("div")!.parentElement!;
    within(openAiRow).getByText("Not configured");

    // No provider is platform-funded any more (Ollama was the one exception) — every
    // unconfigured platform reads "Not configured", never "No key needed".
    const anthropicRow = screen.getByText("Anthropic").closest("div")!.parentElement!;
    within(anthropicRow).getByText("Not configured");

    expect(screen.queryByText(/could not load/i)).toBeNull();
  });

  it("shows an error, not a false empty state, when the credentials query fails", async () => {
    listProviderCatalog.mockResolvedValue({ platforms: [OPENAI, ANTHROPIC], models: [] });
    listProviderCredentials.mockRejectedValue(
      new ApiError(401, { code: "AUTH.UNAUTHORIZED", message: "Unauthorized" }),
    );

    renderProviders();

    await waitFor(() => screen.getByText(/Unauthorized/));

    // The regression this test guards: before the fix, a failed credentials fetch fell through
    // to the success branch and rendered every platform as "Not configured" instead.
    expect(screen.queryByText("Not configured")).toBeNull();
    expect(screen.queryByText("OpenAI")).toBeNull();
  });

  it("renders Configured for a stored credential", async () => {
    listProviderCatalog.mockResolvedValue({ platforms: [OPENAI, ANTHROPIC], models: [] });
    listProviderCredentials.mockResolvedValue({ credentials: [storedCred("OPENAI", { has_api_key: true })] });

    renderProviders();

    const openAiRow = (await screen.findByText("OpenAI")).closest("div")!.parentElement!;
    within(openAiRow).getByText("Configured");
  });

  // Bug: saving a provider edit with the key field left blank sends a key. The upsert contract's
  // "keep the stored key" signal is an omitted field; a blank string survives today only because the
  // server also skips blank secrets.
  it("keeps the stored API key when an edit is saved with the key field blank", async () => {
    listProviderCatalog.mockResolvedValue({ platforms: [OPENAI], models: [] });
    listProviderCredentials.mockResolvedValue({ credentials: [storedCred("OPENAI", { has_api_key: true })] });
    upsertProviderCredential.mockResolvedValue({});

    renderProviders();
    const body = await saveEdit("OpenAI", () =>
      fireEvent.change(screen.getByLabelText("Base URL override"), { target: { value: "https://proxy.example/v1" } }),
    );

    expect(upsertProviderCredential.mock.calls[0][0]).toBe("OPENAI");
    expect(body.base_url_override).toBe("https://proxy.example/v1");
    expect(body.api_key).toBeUndefined();
  });

  // Bug: switching a Bedrock credential to IAM role still sends the keys typed before the switch,
  // and the server seals them over the stored ones. An IAM-role save leaves both key fields out.
  it("sends no AWS keys for an IAM-role Bedrock credential, even ones typed before switching", async () => {
    listProviderCatalog.mockResolvedValue({ platforms: [BEDROCK], models: [] });
    listProviderCredentials.mockResolvedValue({
      credentials: [storedCred("BEDROCK", { has_aws_credentials: true, aws_region: "us-west-2" })],
    });
    upsertProviderCredential.mockResolvedValue({});

    renderProviders();
    const body = await saveEdit("Amazon Bedrock", () => {
      fireEvent.change(screen.getByLabelText("AWS access key"), { target: { value: "AKIATYPEDBEFORE" } });
      fireEvent.change(screen.getByLabelText("AWS secret key"), { target: { value: "typed-before-switch" } });
      fireEvent.click(screen.getByRole("button", { name: "IAM role" }));
    });

    expect(body.auth_mode).toBe("iam_role");
    expect(body.aws_region).toBe("us-west-2");
    expect(body.aws_access_key).toBeUndefined();
    expect(body.aws_secret_key).toBeUndefined();
  });

  it("reads each provider's models and the credential it expects", async () => {
    const model = (provider: ModelProvider, display_name: string) =>
      ({ provider, display_name }) as ProviderCatalogResponse["models"][number];
    listProviderCatalog.mockResolvedValue({
      platforms: [OPENAI, ANTHROPIC, BEDROCK],
      models: [model("OPENAI", "GPT A"), model("ANTHROPIC", "Claude"), model("OPENAI", "GPT B"), model("OPENAI", "GPT C")],
    });
    listProviderCredentials.mockResolvedValue({ credentials: [] });

    renderProviders();
    await screen.findByText("Amazon Bedrock");

    const card = (label: string) => screen.getByText(label).closest("div")!.parentElement!;
    expect(within(card("OpenAI")).getByText("3 models · GPT A, GPT B…")).toBeTruthy();
    expect(within(card("OpenAI")).getByText("API key · base URL")).toBeTruthy();
    expect(within(card("Anthropic")).getByText("1 model · Claude")).toBeTruthy();
    expect(within(card("Amazon Bedrock")).getByText("0 models available")).toBeTruthy();
    expect(within(card("Amazon Bedrock")).getByText("Access key · secret · region")).toBeTruthy();
  });

  it("adds a first key trimmed, and closes once it is saved", async () => {
    listProviderCatalog.mockResolvedValue({ platforms: [OPENAI], models: [] });
    listProviderCredentials.mockResolvedValue({ credentials: [] });
    upsertProviderCredential.mockResolvedValue({});

    renderProviders();
    fireEvent.click(await screen.findByRole("button", { name: "Add key" }));
    fireEvent.change(screen.getByLabelText("API key (required)"), { target: { value: "  sk-new  " } });
    fireEvent.click(screen.getByRole("button", { name: "Test and save" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(upsertProviderCredential.mock.calls[0][1].api_key).toBe("sk-new");
  });

  it("names a rejected key and retries it as edited, and Cancel closes without saving", async () => {
    listProviderCatalog.mockResolvedValue({ platforms: [OPENAI], models: [] });
    listProviderCredentials.mockResolvedValue({ credentials: [] });
    upsertProviderCredential.mockRejectedValueOnce(new Error("401 from provider")).mockResolvedValue({});

    renderProviders();
    fireEvent.click(await screen.findByRole("button", { name: "Add key" }));
    fireEvent.change(screen.getByLabelText("API key (required)"), { target: { value: "sk-bad" } });
    fireEvent.click(screen.getByRole("button", { name: "Test and save" }));

    expect(await screen.findByText("The provider rejected this key")).toBeTruthy();
    expect(screen.getByText("401 from provider")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("API key (required)"), { target: { value: "sk-good" } });
    fireEvent.click(screen.getByRole("button", { name: "Edit and retry" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(upsertProviderCredential.mock.calls[1][1].api_key).toBe("sk-good");

    fireEvent.click(screen.getByRole("button", { name: "Add key" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(upsertProviderCredential).toHaveBeenCalledTimes(2);
  });

  it("requires a base URL and names the model for a custom endpoint", async () => {
    const CUSTOM: PlatformDescriptor = { ...OPENAI, id: "CUSTOM", label: "Custom endpoint", default_base_url: "" };
    listProviderCatalog.mockResolvedValue({ platforms: [CUSTOM], models: [] });
    listProviderCredentials.mockResolvedValue({ credentials: [] });
    upsertProviderCredential.mockResolvedValue({});

    renderProviders();
    fireEvent.click(await screen.findByRole("button", { name: "Add key" }));
    fireEvent.change(screen.getByLabelText("API key (required)"), { target: { value: "k" } });
    fireEvent.change(screen.getByLabelText("Base URL (required)"), { target: { value: " https://llm.internal/v1 " } });
    fireEvent.change(screen.getByLabelText("Custom model name"), { target: { value: " llama-3.3-70b " } });
    fireEvent.click(screen.getByRole("button", { name: "Test and save" }));

    await waitFor(() => expect(upsertProviderCredential).toHaveBeenCalled());
    expect(upsertProviderCredential.mock.calls[0]).toEqual([
      "CUSTOM",
      expect.objectContaining({ base_url_override: "https://llm.internal/v1", custom_model_name: "llama-3.3-70b" }),
    ]);
  });

  it("keeps a custom endpoint's stored model name when the field is left blank", async () => {
    const CUSTOM: PlatformDescriptor = { ...OPENAI, id: "CUSTOM", label: "Custom endpoint", default_base_url: "" };
    listProviderCatalog.mockResolvedValue({ platforms: [CUSTOM], models: [] });
    listProviderCredentials.mockResolvedValue({
      credentials: [storedCred("CUSTOM", { has_api_key: true, custom_model_name: "llama-3.3-70b" })],
    });
    upsertProviderCredential.mockResolvedValue({});

    renderProviders();
    const body = await saveEdit("Custom endpoint", () =>
      fireEvent.change(screen.getByLabelText("Custom model name"), { target: { value: "   " } }),
    );

    expect(body.custom_model_name).toBeUndefined();
  });

  it("sends typed AWS keys and region when a Bedrock credential goes back to access keys", async () => {
    listProviderCatalog.mockResolvedValue({ platforms: [BEDROCK], models: [] });
    listProviderCredentials.mockResolvedValue({ credentials: [] });
    upsertProviderCredential.mockResolvedValue({});

    renderProviders();
    fireEvent.click(await screen.findByRole("button", { name: "Add key" }));
    fireEvent.click(screen.getByRole("button", { name: "IAM role" }));
    fireEvent.click(screen.getByRole("button", { name: "Access + secret key" }));
    fireEvent.change(screen.getByLabelText("AWS region"), { target: { value: " eu-west-1 " } });
    fireEvent.change(screen.getByLabelText("AWS access key"), { target: { value: " AKIA1 " } });
    fireEvent.change(screen.getByLabelText("AWS secret key"), { target: { value: " s3cret " } });
    fireEvent.click(screen.getByRole("button", { name: "Test and save" }));

    await waitFor(() => expect(upsertProviderCredential).toHaveBeenCalled());
    expect(upsertProviderCredential.mock.calls[0][1]).toMatchObject({
      auth_mode: "api_key",
      aws_region: "eu-west-1",
      aws_access_key: "AKIA1",
      aws_secret_key: "s3cret",
      api_key: undefined,
    });
  });

  it("removes a stored key only after confirmation, and names a refused remove", async () => {
    listProviderCatalog.mockResolvedValue({ platforms: [OPENAI, ANTHROPIC], models: [] });
    listProviderCredentials.mockResolvedValue({
      credentials: [storedCred("OPENAI", { has_api_key: true }), storedCred("ANTHROPIC", { has_api_key: true })],
    });
    deleteProviderCredential.mockRejectedValueOnce(new Error("in use")).mockResolvedValue({});
    let confirmReply = false;
    vi.spyOn(window, "confirm").mockImplementation(() => confirmReply);

    renderProviders();
    await screen.findByText("Anthropic");
    const remove = () =>
      within(screen.getByText("Anthropic").closest("div")!.parentElement!).getByRole("button", { name: "Remove" });

    fireEvent.click(remove());
    await act(() => new Promise((resolve) => setTimeout(resolve, 0)));
    expect(deleteProviderCredential).not.toHaveBeenCalled();

    confirmReply = true;
    fireEvent.click(remove());
    expect(await screen.findByText("Could not remove key")).toBeTruthy();
    fireEvent.click(remove());
    expect(await screen.findByText("Provider key removed")).toBeTruthy();
    expect(deleteProviderCredential).toHaveBeenLastCalledWith("ANTHROPIC");
  });
});
