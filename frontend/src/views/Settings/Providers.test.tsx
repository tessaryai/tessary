// SPDX-License-Identifier: Apache-2.0
/*
 * Providers.tsx's first test — none existed before this file (only
 * src/routeManifest.smoke.test.tsx covered this view, and only for "renders something without
 * throwing" under mocked-empty data; see that file's own header).
 *
 * The real bug this pins (issue #861, AC1 inverted): the component gated its error branch on
 * `catalog.isError` alone. When the credentials query failed and the catalog query succeeded,
 * `credByProvider` silently built an empty map and every platform rendered "Not configured" — a
 * false empty state indistinguishable from a genuinely fresh, keyless install. Fixed to
 * `catalog.isError || credentials.isError`.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { PlatformDescriptor, ProviderCatalogResponse, ProviderCredentialListResponse } from "../../api/types";
import { ApiError } from "../../api/types";
import { ToastProvider } from "../../ui/Toast";
import { Providers } from "./Providers";

// ---- collaborator mocks ------------------------------------------------------------------
// Providers.tsx reads its API surface via useOrgApi() (TenantContext) — #939 D1 moved provider
// credentials off the project-scoped API. Not exercised by these tests (the credential modal is
// never opened), so it is stubbed just enough to satisfy the module's imports.

const listProviderCatalog = vi.fn<() => Promise<ProviderCatalogResponse>>();
const listProviderCredentials = vi.fn<() => Promise<ProviderCredentialListResponse>>();

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
      upsertProviderCredential: vi.fn(),
      deleteProviderCredential: vi.fn(),
    }),
  };
});

// ---- fixtures ------------------------------------------------------------------------------
// #939 D6: Ollama (the platform's one AUTH_NONE, platform-funded provider) was dropped by the
// maker filter — every provider now requires a key, so ANTHROPIC stands in as the second fixture
// instead, and `platform_funded` is gone from PlatformDescriptor entirely.

const OPENAI: PlatformDescriptor = {
  id: "OPENAI",
  label: "OpenAI",
  auth: "api_key",
  default_base_url: "https://api.openai.com/v1",
  supports_base_url: true,
};

const ANTHROPIC: PlatformDescriptor = {
  id: "ANTHROPIC",
  label: "Anthropic",
  auth: "api_key",
  default_base_url: "https://api.anthropic.com/v1",
  supports_base_url: true,
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

afterEach(() => {
  cleanup();
  // vi.resetAllMocks() is a WORKER-GLOBAL reset, not file-scoped -- when this file runs in the
  // same vitest worker as src/routeManifest.smoke.test.tsx, it silently wiped out that file's own
  // vi.fn()-backed mock implementations (e.g. auth.getCapabilities) mid-run, breaking dozens of
  // unrelated route mounts with "No queryFn was passed" errors. Reset only this file's own mocks.
  listProviderCatalog.mockReset();
  listProviderCredentials.mockReset();
});

describe("Providers", () => {
  it("renders Not configured for a healthy catalog with zero stored credentials", async () => {
    listProviderCatalog.mockResolvedValue({ platforms: [OPENAI, ANTHROPIC], models: [] });
    listProviderCredentials.mockResolvedValue({ credentials: [] });

    renderProviders();

    await waitFor(() => screen.getByText("OpenAI"));

    const openAiRow = screen.getByText("OpenAI").closest("div")!.parentElement!;
    within(openAiRow).getByText("Not configured");

    // #939 D6: no provider is platform-funded any more (Ollama was the one exception) — every
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

  it("renders Configured for a stored credential and never puts a secret value in the DOM", async () => {
    listProviderCatalog.mockResolvedValue({ platforms: [OPENAI, ANTHROPIC], models: [] });
    listProviderCredentials.mockResolvedValue({
      credentials: [
        {
          id: "cred_1",
          provider: "OPENAI",
          base_url_override: "",
          has_api_key: true,
          aws_region: "",
          has_aws_credentials: false,
          bedrock_model_arn: "",
          custom_model_name: "",
          auth_mode: "api_key",
          created_at: "2026-01-01T00:00:00Z",
          updated_at: "2026-01-01T00:00:00Z",
        },
      ],
    });

    renderProviders();

    await waitFor(() => screen.getByText("Configured"));

    // The View the backend returns never carries a raw secret (only has_* booleans) — this
    // asserts the rendered DOM upholds the same contract on the way out.
    expect(document.body.innerHTML).not.toMatch(/sk-[a-zA-Z0-9]/);
    expect(document.body.innerHTML.toLowerCase()).not.toContain("api_key_sealed");
  });
});
