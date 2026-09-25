// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → Git integration, and the dialog that binds a repository from anywhere. Three connect
 * paths land here (a token, the GitHub App install, a self-registered App), and every GitHub callback
 * returns here with a flag the page must act on once and then strip, so a reload does not repeat it.
 * The bugs worth catching: a pasted URL bound as the wrong owner/name, an empty token sent as a
 * credential, a picker choice sent without the sealed token it came from, and a callback flag that
 * toasts forever.
 */
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/types";
import { currentParams, pending, renderRoute } from "../../test/render";
import { parseRepo, tokenTemplateUrl } from "../components/ConnectRepositoryDialog";
import { GitIntegration } from "./GitIntegration";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  getGitIntegration: vi.fn(),
  getGithubInstallUrl: vi.fn(),
  getGithubAuthorizeUrl: vi.fn(),
  getGithubManifestStart: vi.fn(),
  getGithubInstallationOptions: vi.fn(),
  selectGithubInstallation: vi.fn(),
  connectGit: vi.fn(),
  disconnectGit: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({ useProjectApi: () => api }));

const CONNECTED = { repoOwner: "acme", repoName: "app", defaultBranch: "main", provider: "github", host: "github.example.com" };

beforeEach(() => {
  api.getGitIntegration.mockResolvedValue(null);
  window.location.hash = "";
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

describe("parseRepo", () => {
  it.each([
    ["acme/app", { owner: "acme", name: "app" }],
    ["https://github.com/acme/app", { owner: "acme", name: "app" }],
    ["https://www.github.com/acme/app/tree/main", { owner: "acme", name: "app" }],
    ["git@github.com:acme/app.git", { owner: "acme", name: "app" }],
    ["github.com/acme/app/", { owner: "acme", name: "app" }],
    ["  acme//app  ", { owner: "acme", name: "app" }],
    ["acme", null],
    ["https://github.com/acme", null],
    ["   ", null],
  ])("%s", (input, parsed) => {
    expect(parseRepo(input)).toEqual(parsed);
  });

  it("prefills GitHub's token form for the repository, within GitHub's 40-character name limit", () => {
    const url = new URL(tokenTemplateUrl({ owner: "a-very-long-organization-name", name: "service" }));
    expect(url.origin + url.pathname).toBe("https://github.com/settings/personal-access-tokens/new");
    expect(url.searchParams.get("name")).toBe("Tessary: a-very-long-organization-name/s");
    expect(url.searchParams.get("target_name")).toBe("a-very-long-organization-name");
    expect(url.searchParams.get("description")).toContain("select only a-very-long-organization-name/service");
    expect(url.searchParams.get("contents")).toBe("read");
    expect(url.searchParams.get("expires_in")).toBe("90");

    const bare = new URL(tokenTemplateUrl(null));
    expect(bare.searchParams.get("name")).toBe("Tessary");
    expect(bare.searchParams.has("target_name")).toBe(false);
  });
});

describe("a connected repository", () => {
  it("names the repository and branch, and disconnects it", async () => {
    api.getGitIntegration.mockResolvedValue(CONNECTED);
    api.disconnectGit.mockResolvedValue({ deleted: true });
    renderRoute(<GitIntegration />);

    expect(await screen.findByText("acme/app")).toBeTruthy();
    expect(screen.getByText("@main")).toBeTruthy();
    expect(screen.getByText("github · github.example.com")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Disconnect" }));
    expect(await screen.findByText("Repository disconnected")).toBeTruthy();
  });

  it("names a refused disconnect, and a host-less provider without a stray separator", async () => {
    api.getGitIntegration.mockResolvedValue({ ...CONNECTED, host: "" });
    api.disconnectGit.mockRejectedValue(new ApiError(409, { code: "GIT.IN_USE", message: "an RCA is reading it" }));
    renderRoute(<GitIntegration />);

    expect(await screen.findByText("github")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Disconnect" }));
    expect(await screen.findByText(/an RCA is reading it/)).toBeTruthy();
  });

  it("shows the read's loading and failure", async () => {
    api.getGitIntegration.mockImplementation(pending);
    renderRoute(<GitIntegration />);
    expect(screen.queryByText("No repository connected")).toBeNull();
    cleanup();

    api.getGitIntegration.mockRejectedValue(new Error("git read failed"));
    renderRoute(<GitIntegration />);
    expect(await screen.findByText(/git read failed/)).toBeTruthy();
  });
});

describe("connecting with a token", () => {
  it("binds the owner and name parsed from a pasted URL, with the token trimmed", async () => {
    api.connectGit.mockResolvedValue(CONNECTED);
    renderRoute(<GitIntegration />);
    fireEvent.click(await screen.findByRole("button", { name: "Connect repository" }));

    const dialog = screen.getByRole("dialog");
    const connect = within(dialog).getByRole("button", { name: "Connect repository" }) as HTMLButtonElement;
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Repository" }), { target: { value: "acme" } });
    expect(connect.disabled).toBe(true);
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Repository" }), { target: { value: "https://github.com/acme/app.git" } });
    fireEvent.change(within(dialog).getByLabelText("Access token"), { target: { value: "  github_pat_x  " } });
    fireEvent.click(connect);

    await waitFor(() => expect(api.connectGit).toHaveBeenCalledWith({ provider: "github", repoOwner: "acme", repoName: "app", token: "github_pat_x" }));
    expect(await screen.findByText("Repository connected")).toBeTruthy();
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("sends no token at all when the field is blank, and always says a connect failed", async () => {
    api.connectGit.mockRejectedValueOnce(new ApiError(403, { code: "GIT.NO_ACCESS", message: "the App cannot reach acme/app" })).mockRejectedValueOnce(new ApiError(500, { code: "X", message: "" }));
    renderRoute(<GitIntegration />);
    fireEvent.click(await screen.findByRole("button", { name: "Connect repository" }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Repository" }), { target: { value: "acme/app" } });
    fireEvent.change(within(dialog).getByLabelText("Access token"), { target: { value: "   " } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Connect repository" }));

    await waitFor(() => expect(api.connectGit).toHaveBeenCalledWith({ provider: "github", repoOwner: "acme", repoName: "app", token: undefined }));
    expect((await screen.findByRole("alert")).textContent).toBe("Connection failed. the App cannot reach acme/app");

    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Connect repository" }));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toBe("Connection failed. The request failed. Try again."));
  });

  it("explains how to make a token, opening GitHub prefilled for the typed repository", async () => {
    const open = vi.spyOn(window, "open").mockImplementation(() => null);
    renderRoute(<GitIntegration />);
    fireEvent.click(await screen.findByRole("button", { name: "Connect repository" }));
    const dialog = screen.getByRole("dialog");

    fireEvent.click(within(dialog).getByRole("button", { name: "How to create a token" }));
    const step2 = (text: string) => (_: string, el: Element | null) => !!el?.classList.contains("text-fg-secondary") && el.textContent === text;
    expect(within(dialog).getByText(step2("Under Repository access, select your repository."))).toBeTruthy();
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Repository" }), { target: { value: "acme/app" } });
    expect(within(dialog).getByText(step2("Under Repository access, select acme/app."))).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Create a token on GitHub" }));
    expect(open).toHaveBeenCalledWith(tokenTemplateUrl({ owner: "acme", name: "app" }), "_blank", "noopener,noreferrer");

    fireEvent.click(within(dialog).getByRole("button", { name: "How to create a token" }));
    expect(within(dialog).queryByRole("button", { name: "Create a token on GitHub" })).toBeNull();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

describe("the GitHub App routes", () => {
  it("sends the browser to the install and authorize URLs the server returns", async () => {
    api.getGithubInstallUrl.mockResolvedValue({ url: "#installing" });
    api.getGithubAuthorizeUrl.mockResolvedValue({ url: "#authorizing" });
    renderRoute(<GitIntegration />);
    fireEvent.click(await screen.findByRole("button", { name: "Use a GitHub App instead" }));

    fireEvent.click(screen.getByRole("button", { name: "Install the GitHub App" }));
    await waitFor(() => expect(window.location.hash).toBe("#installing"));
    fireEvent.click(screen.getByRole("button", { name: "Use an existing installation" }));
    await waitFor(() => expect(window.location.hash).toBe("#authorizing"));
  });

  it("falls back to the token dialog when no App is configured, rather than dead-ending", async () => {
    api.getGithubInstallUrl.mockRejectedValue(new Error("no app"));
    api.getGithubAuthorizeUrl.mockRejectedValue(new Error("no app"));
    renderRoute(<GitIntegration />);
    fireEvent.click(await screen.findByRole("button", { name: "Use a GitHub App instead" }));

    fireEvent.click(screen.getByRole("button", { name: "Install the GitHub App" }));
    expect(await screen.findByRole("dialog")).toBeTruthy();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Use a GitHub App instead" }));
    expect(screen.queryByRole("dialog")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Use an existing installation" }));
    expect(await screen.findByRole("dialog")).toBeTruthy();
  });

  it("posts the App manifest to GitHub as a form field, and names a failure to start", async () => {
    const submit = vi.spyOn(HTMLFormElement.prototype, "submit").mockImplementation(() => {});
    api.getGithubManifestStart.mockResolvedValueOnce({ url: "https://github.com/settings/apps/new", manifest: '{"name":"tessary"}' }).mockRejectedValueOnce(new Error("x"));
    renderRoute(<GitIntegration />);
    fireEvent.click(await screen.findByRole("button", { name: "Use a GitHub App instead" }));

    fireEvent.click(screen.getByRole("button", { name: "Set up your own GitHub App" }));
    await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
    const form = submit.mock.contexts[0] as HTMLFormElement;
    expect(form.method.toUpperCase()).toBe("POST");
    expect(form.action).toBe("https://github.com/settings/apps/new");
    expect((form.elements.namedItem("manifest") as HTMLInputElement).value).toBe('{"name":"tessary"}');

    fireEvent.click(screen.getByRole("button", { name: "Set up your own GitHub App" }));
    expect(await screen.findByText("Could not start GitHub App setup")).toBeTruthy();
  });
});

describe("the callbacks from GitHub", () => {
  it("acts on each flag once and strips it, keeping the rest of the URL", async () => {
    renderRoute(<GitIntegration />, { route: "/settings/git?connected=1&keep=1" });
    expect(await screen.findByText("RCA can now read this repository.")).toBeTruthy();
    expect(currentParams().toString()).toBe("keep=1");
    cleanup();

    renderRoute(<GitIntegration />, { route: "/settings/git?github_app_connected=1" });
    expect(await screen.findByText("GitHub App configured")).toBeTruthy();
    expect(currentParams().toString()).toBe("");
    cleanup();

    renderRoute(<GitIntegration />, { route: "/settings/git?github_error=bad_state" });
    expect(await screen.findByText('bad_state. Try again, or select "Use an existing installation".')).toBeTruthy();
    expect(currentParams().toString()).toBe("");
  });

  it("asks which repository when the installation reaches several, and binds the choice with its token", async () => {
    api.getGithubInstallationOptions.mockResolvedValue({
      candidates: [
        { installationId: 7, owner: "acme", name: "app", defaultBranch: "main" },
        { installationId: 7, owner: "acme", name: "worker", defaultBranch: "trunk" },
      ],
    });
    api.selectGithubInstallation.mockRejectedValueOnce(new Error("token expired")).mockResolvedValue(CONNECTED);
    renderRoute(<GitIntegration />, { route: "/settings/git?install_select=sealed-tok" });

    const choice = await screen.findByRole("button", { name: /acme\/worker/ });
    expect(api.getGithubInstallationOptions).toHaveBeenCalledWith("sealed-tok");
    fireEvent.click(choice);
    expect(await screen.findByText(/token expired/)).toBeTruthy();

    fireEvent.click(choice);
    await waitFor(() => expect(api.selectGithubInstallation).toHaveBeenLastCalledWith({ token: "sealed-tok", installationId: 7, repoOwner: "acme", repoName: "worker" }));
    await waitFor(() => expect(currentParams().has("install_select")).toBe(false));
    expect(await screen.findByText("Repository connected")).toBeTruthy();
  });

  it("drops the picker's token when it is dismissed, and says when its options could not load", async () => {
    api.getGithubInstallationOptions.mockRejectedValue(new Error("options unavailable"));
    renderRoute(<GitIntegration />, { route: "/settings/git?install_select=sealed-tok" });

    expect(await screen.findByText(/options unavailable/)).toBeTruthy();
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    await waitFor(() => expect(currentParams().has("install_select")).toBe(false));
  });
});
