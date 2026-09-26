// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → MCP tokens. As with API keys, the page turns on the secret shown once: the bugs worth
 * catching are a secret that survives into the next open of the dialog, a revoke that skips its
 * confirmation or hits the wrong token, a revoked token still offering Revoke, and a config block
 * that points the client somewhere other than this deployment's /mcp.
 */
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { McpTokenView } from "../../api/types-auth";
import { renderRoute } from "../../test/render";
import { McpTokens } from "./McpTokens";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  listMcpTokens: vi.fn(),
  issueMcpToken: vi.fn(),
  revokeMcpToken: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({ useProjectApi: () => api }));

const token = (over: Partial<McpTokenView> = {}): McpTokenView => ({
  id: "t-1",
  name: "my-laptop",
  token_prefix: "tsy_m_ab12",
  created_at: "2026-09-25T11:00:00Z",
  last_used_at: null,
  revoked_at: null,
  created_by_user_id: "u-1",
  ...over,
});

let confirmReply = true;
const writeText = vi.fn();
beforeEach(() => {
  confirmReply = true;
  vi.spyOn(window, "confirm").mockImplementation(() => confirmReply);
  writeText.mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
  api.listMcpTokens.mockResolvedValue([token()]);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)));
const dialog = () => screen.getByRole("dialog");
const row = (name: string) => screen.getByText(name).closest<HTMLElement>("div.flex")!;

describe("the token list", () => {
  it("shows each token's prefix and use, and gives a revoked token no Revoke", async () => {
    api.listMcpTokens.mockResolvedValue([
      token(),
      token({ id: "t-2", name: "ci", last_used_at: "2026-09-25T11:00:00Z", revoked_at: "2026-09-25T11:30:00Z" }),
    ]);
    renderRoute(<McpTokens />);

    await screen.findByText("my-laptop");
    expect(within(row("my-laptop")).getByText("tsy_m_ab12…")).toBeTruthy();
    expect(within(row("my-laptop")).getByText("Never")).toBeTruthy();
    expect(within(row("ci")).getByText("Revoked")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Revoke ci" })).toBeNull();
    expect(screen.getByRole("button", { name: "Revoke my-laptop" })).toBeTruthy();
  });

  it("offers a first token and hides the config when there are none, and says when the list could not load", async () => {
    api.listMcpTokens.mockResolvedValue([]);
    renderRoute(<McpTokens />);
    expect(await screen.findByText("No tokens yet")).toBeTruthy();
    expect(screen.getAllByRole("button", { name: "New token" })).toHaveLength(2);
    expect(screen.queryByText("MCP config")).toBeNull();
    cleanup();

    api.listMcpTokens.mockRejectedValue(new Error("tokens unavailable"));
    renderRoute(<McpTokens />);
    expect(await screen.findByText("tokens unavailable")).toBeTruthy();
  });
});

describe("issuing a token", () => {
  it("sends the trimmed name, shows the secret once, and never again", async () => {
    api.issueMcpToken.mockResolvedValue({ token: token({ name: "ci" }), plaintext: "tsy_m_secret", warning: "once" });
    renderRoute(<McpTokens />);
    fireEvent.click((await screen.findAllByRole("button", { name: "New token" }))[0]);

    const issue = within(dialog()).getByRole("button", { name: "Issue token" }) as HTMLButtonElement;
    expect(issue.disabled).toBe(true);
    fireEvent.change(within(dialog()).getByRole("textbox", { name: "Name" }), { target: { value: "  ci  " } });
    fireEvent.click(issue);

    await waitFor(() => expect(api.issueMcpToken).toHaveBeenCalledWith("ci"));
    expect(await within(dialog()).findByText("tsy_m_secret")).toBeTruthy();
    expect(within(dialog()).getByRole("heading", { name: "Token ci created" })).toBeTruthy();

    fireEvent.click(within(dialog()).getByRole("button", { name: "Done" }));
    expect(screen.queryByText("tsy_m_secret")).toBeNull();
    fireEvent.click(screen.getAllByRole("button", { name: "New token" })[0]);
    expect(screen.queryByText("tsy_m_secret")).toBeNull();
    expect((within(dialog()).getByRole("textbox", { name: "Name" }) as HTMLInputElement).value).toBe("");
  });

  it("does not reveal a secret that arrives after Cancel on the next open", async () => {
    let answer: (v: unknown) => void = () => {};
    api.issueMcpToken.mockReturnValue(new Promise((resolve) => (answer = resolve)));
    renderRoute(<McpTokens />);
    fireEvent.click((await screen.findAllByRole("button", { name: "New token" }))[0]);
    fireEvent.change(within(dialog()).getByRole("textbox", { name: "Name" }), { target: { value: "late" } });
    fireEvent.click(within(dialog()).getByRole("button", { name: "Issue token" }));
    fireEvent.click(within(dialog()).getByRole("button", { name: "Cancel" }));

    answer({ token: token({ name: "late" }), plaintext: "tsy_m_late", warning: "once" });
    await settle();
    fireEvent.click(screen.getAllByRole("button", { name: "New token" })[0]);

    expect(screen.queryByText("tsy_m_late")).toBeNull();
    expect(within(dialog()).getByRole("textbox", { name: "Name" })).toBeTruthy();
  });

  it("issues on Enter, does nothing on Enter with no name, and says why an issue was refused", async () => {
    api.issueMcpToken.mockRejectedValue(new Error("name taken"));
    renderRoute(<McpTokens />);
    fireEvent.click((await screen.findAllByRole("button", { name: "New token" }))[0]);
    const name = within(dialog()).getByRole("textbox", { name: "Name" });

    fireEvent.keyDown(name, { key: "Enter" });
    await settle();
    expect(api.issueMcpToken).not.toHaveBeenCalled();

    fireEvent.change(name, { target: { value: "dup" } });
    fireEvent.keyDown(name, { key: "Enter" });
    expect(await screen.findByText("Could not issue token")).toBeTruthy();
    expect(screen.getByText("name taken")).toBeTruthy();

    fireEvent.click(within(dialog()).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("tells the reader to copy the secret by hand when the clipboard refuses", async () => {
    api.issueMcpToken.mockResolvedValue({ token: token(), plaintext: "tsy_m_secret", warning: "once" });
    writeText.mockRejectedValue(new Error("denied"));
    renderRoute(<McpTokens />);
    fireEvent.click((await screen.findAllByRole("button", { name: "New token" }))[0]);
    fireEvent.change(within(dialog()).getByRole("textbox", { name: "Name" }), { target: { value: "x" } });
    fireEvent.click(within(dialog()).getByRole("button", { name: "Issue token" }));

    fireEvent.click(await within(dialog()).findByRole("button", { name: "Copy" }));
    expect(await screen.findByText("Copy the token manually.")).toBeTruthy();
  });
});

describe("revoking", () => {
  it("revokes only after confirmation, spins while it runs, and names a refused revoke", async () => {
    let finish: (v: unknown) => void = () => {};
    api.revokeMcpToken
      .mockRejectedValueOnce(new Error("already revoked"))
      .mockReturnValueOnce(new Promise((resolve) => (finish = resolve)));
    api.listMcpTokens.mockResolvedValue([token({ id: "t-0", name: "other" }), token()]);
    renderRoute(<McpTokens />);
    const revoke = await screen.findByRole("button", { name: "Revoke my-laptop" });

    confirmReply = false;
    fireEvent.click(revoke);
    await settle();
    expect(api.revokeMcpToken).not.toHaveBeenCalled();

    confirmReply = true;
    fireEvent.click(revoke);
    expect(await screen.findByText("Could not revoke token")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Revoke my-laptop" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Revoke my-laptop" })).toBeNull());
    expect(screen.getByRole("button", { name: "Revoke other" })).toBeTruthy();
    expect(api.revokeMcpToken).toHaveBeenLastCalledWith("t-1");

    finish({ revoked: true });
    expect(await screen.findByText("Token revoked")).toBeTruthy();
  });
});

describe("the MCP config", () => {
  it("points the client at this deployment's /mcp and copies exactly what it shows", async () => {
    renderRoute(<McpTokens />);
    await screen.findByText("my-laptop");

    const shown = screen.getByText(/"mcpServers"/).textContent!;
    expect(JSON.parse(shown)).toEqual({
      mcpServers: {
        tessary: {
          type: "http",
          url: `${window.location.origin}/mcp`,
          headers: { Authorization: "Bearer $TESSARY_TOKEN" },
        },
      },
    });

    fireEvent.click(screen.getByRole("button", { name: "Copy" }));
    expect(await screen.findByText("Config copied")).toBeTruthy();
    expect(writeText).toHaveBeenCalledWith(shown);
    expect(screen.getByRole("button", { name: "Copied" })).toBeTruthy();
  });

  it("says to copy the config by hand when the clipboard refuses", async () => {
    writeText.mockRejectedValue(new Error("denied"));
    renderRoute(<McpTokens />);
    await screen.findByText("my-laptop");

    fireEvent.click(screen.getByRole("button", { name: "Copy" }));

    expect(await screen.findByText("Copy the config manually.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Copy" })).toBeTruthy();
  });
});
