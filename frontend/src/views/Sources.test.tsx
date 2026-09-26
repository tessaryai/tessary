// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → Sources and the OTLP connect story it opens. The bugs worth catching: a snippet copied with
 * no token or a blank where the token goes (it fails with a 401 and no clue why), a token minted with
 * more than write scope or on mount rather than on request, a snippet for one runtime shown under
 * another's tab, and a failed list read that looks like no sources.
 */
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, type IngestionSource } from "../api/types";
import { currentLocation, pending, renderRoute } from "../test/render";
import { Sources } from "./Sources";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  listSources: vi.fn(),
  createApiKey: vi.fn(),
  createSource: vi.fn(),
}));

vi.mock("../tenant/TenantContext", () => ({
  useProjectApi: () => api,
  useTenant: () => ({ orgSlug: "acme", projectSlug: "default" }),
}));

const SOURCE = { id: "s-1", name: "Tessary SDK", provider: "sdk", baseUrl: "tessary://sdk" } as IngestionSource;
const ENDPOINT = `${window.location.origin}/v1/traces`;
const writeText = vi.fn();

beforeEach(() => {
  api.listSources.mockResolvedValue([SOURCE]);
  api.createApiKey.mockResolvedValue({ plaintext: "tsy_w_secret" });
  api.createSource.mockResolvedValue({ id: "s-2" });
  writeText.mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const dialog = () => screen.getByRole("dialog");
const snippet = () => within(dialog()).getByText(/OTEL_|opentelemetry|otlptracehttp|exporters:/, { selector: "code" }).textContent!;

describe("the source list", () => {
  it("lists each connected source, and opens the connect story from either control", async () => {
    renderRoute(<Sources />);

    expect(await screen.findByText("tessary://sdk")).toBeTruthy();
    expect(screen.getByText("Connected")).toBeTruthy();
    const connects = screen.getAllByRole("button", { name: /Connect source/ });
    expect(connects).toHaveLength(2);

    fireEvent.click(connects[1]);
    expect(within(dialog()).getByText("Forward your OpenTelemetry traces")).toBeTruthy();
    fireEvent.click(within(dialog()).getByRole("button", { name: "Done" }));
    expect(screen.queryByRole("dialog")).toBeNull();

    fireEvent.click(connects[0]);
    expect(screen.getByRole("dialog")).toBeTruthy();
  });

  it("offers a first source when there are none", async () => {
    api.listSources.mockResolvedValue([]);
    renderRoute(<Sources />);

    expect(await screen.findByText("No sources yet")).toBeTruthy();
    expect(screen.getAllByRole("button", { name: "Connect source" })).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Connect source" }));
    expect(screen.getByRole("dialog")).toBeTruthy();
  });

  it("names a failed read by its code, and shows the loading line before it", async () => {
    api.listSources.mockReturnValueOnce(pending());
    renderRoute(<Sources />);
    expect(screen.getByText(/Loading sources/)).toBeTruthy();
    cleanup();

    api.listSources.mockRejectedValue(new ApiError(503, { code: "SOURCES.UNAVAILABLE", message: "try later" }));
    renderRoute(<Sources />);
    expect(await screen.findByText("SOURCES.UNAVAILABLE")).toBeTruthy();
    expect(screen.queryByText("No sources yet")).toBeNull();
  });

  it("goes to the bundle import", async () => {
    renderRoute(<Sources />);

    fireEvent.click(screen.getByRole("button", { name: "Import bundle" }));

    expect(currentLocation()).toBe("/orgs/acme/projects/default/settings/import");
  });
});

describe("connecting over OTLP", () => {
  const openStory = async () => {
    renderRoute(<Sources />);
    fireEvent.click((await screen.findAllByRole("button", { name: /Connect source/ }))[0]);
  };

  it("puts a loud placeholder where the token goes until one is created, and mints only on request", async () => {
    await openStory();

    expect(snippet()).toContain("Authorization=Bearer <create a connection token above>");
    expect(snippet()).toContain(`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=${ENDPOINT}`);
    expect(within(dialog()).getByText(/this fills itself in/)).toBeTruthy();
    expect(api.createApiKey).not.toHaveBeenCalled();
  });

  it("mints a write-scoped token, registers the SDK source, and fills the token in everywhere", async () => {
    await openStory();

    fireEvent.click(within(dialog()).getByRole("button", { name: "Create a connection token" }));

    expect(await within(dialog()).findByText("Authorization: Bearer tsy_w_secret")).toBeTruthy();
    expect(api.createApiKey).toHaveBeenCalledWith({ name: "OTLP ingest", scope: "write" });
    await waitFor(() =>
      expect(api.createSource).toHaveBeenCalledWith({
        provider: "sdk",
        name: "Tessary SDK",
        baseUrl: "tessary://sdk",
        credentials: {},
      }),
    );
    expect(snippet()).toContain("Authorization=Bearer tsy_w_secret");
    expect(within(dialog()).queryByText(/this fills itself in/)).toBeNull();
  });

  it("still shows the token when registering the SDK source fails", async () => {
    api.createSource.mockRejectedValue(new Error("exists"));
    await openStory();

    fireEvent.click(within(dialog()).getByRole("button", { name: "Create a connection token" }));

    expect(await within(dialog()).findByText("Authorization: Bearer tsy_w_secret")).toBeTruthy();
  });

  it("names why no token was made", async () => {
    api.createApiKey.mockRejectedValue(new ApiError(403, { code: "AUTH.FORBIDDEN", message: "Not allowed" }));
    await openStory();

    fireEvent.click(within(dialog()).getByRole("button", { name: "Create a connection token" }));

    expect(await screen.findByText("Could not create token")).toBeTruthy();
  });

  it.each([
    ["Python", 'endpoint="', 'headers={"Authorization": "Bearer '],
    ["TypeScript", 'url: "', 'headers: { Authorization: "Bearer '],
    ["Go", 'WithEndpointURL("', '"Authorization": "Bearer '],
    ["Collector", "traces_endpoint: ", 'Authorization: "Bearer '],
  ])("gives %s its own exporter, with this endpoint and the placeholder token", async (tab, endpointAt, tokenAt) => {
    await openStory();

    fireEvent.click(within(dialog()).getByRole("tab", { name: tab }));

    expect(within(dialog()).getByRole("tab", { name: tab }).getAttribute("aria-selected")).toBe("true");
    expect(snippet()).toContain(`${endpointAt}${ENDPOINT}`);
    expect(snippet()).toContain(`${tokenAt}<create a connection token above>`);
  });

  it("copies the snippet on screen, and the prompt, endpoint, and header", async () => {
    await openStory();
    fireEvent.click(within(dialog()).getByRole("tab", { name: "Go" }));
    const copies = () => within(dialog()).getAllByRole("button", { name: /^Copy/ });

    fireEvent.click(copies().at(-1)!);
    await waitFor(() => expect(writeText).toHaveBeenLastCalledWith(snippet()));

    fireEvent.click(within(dialog()).getByRole("button", { name: /Copy prompt/ }));
    fireEvent.click(within(within(dialog()).getByText("Endpoint").parentElement!).getByRole("button", { name: /Copy/ }));
    await waitFor(() => expect(writeText).toHaveBeenLastCalledWith(ENDPOINT));

    fireEvent.click(within(dialog()).getByRole("button", { name: "Create a connection token" }));
    const header = (await within(dialog()).findByText("Authorization: Bearer tsy_w_secret")).parentElement!;
    fireEvent.click(within(header).getByRole("button", { name: /Copy/ }));
    await waitFor(() => expect(writeText).toHaveBeenLastCalledWith("Authorization: Bearer tsy_w_secret"));
  });

  it("says to copy the snippet by hand when the clipboard refuses", async () => {
    writeText.mockRejectedValue(new Error("denied"));
    await openStory();

    fireEvent.click(within(dialog()).getAllByRole("button", { name: /^Copy/ }).at(-1)!);

    expect(await screen.findByText("Select the snippet and copy it manually.")).toBeTruthy();
  });
});
