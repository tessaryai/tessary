// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → API keys. The page turns on one moment: the plaintext secret, shown once at create or
 * rotate and never again. The bugs worth catching are a secret that survives into the next open of
 * the dialog, a rotate that sends the wrong key, a revoke that skips its confirmation, and a revoked
 * key still offering actions.
 */
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ApiKey, ApiKeyAudit } from "../../api/types-auth";
import { pending, renderRoute } from "../../test/render";
import { ApiKeys } from "./ApiKeys";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  listApiKeys: vi.fn(),
  listApiKeyAudit: vi.fn(),
  createApiKey: vi.fn(),
  rotateApiKey: vi.fn(),
  revokeApiKey: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({ useProjectApi: () => api }));

const NOW = new Date("2026-09-25T12:00:00Z");
const key = (over: Partial<ApiKey>): ApiKey => ({
  id: "k-1",
  name: "ingest-prod",
  scope: "write",
  token_prefix: "tsy_w_ab12",
  created_at: "2026-09-25T11:55:00Z",
  last_used_at: null,
  revoked_at: null,
  created_by_user_id: "u-1",
  ...over,
});
const issued = (k: ApiKey, plaintext: string) => ({ key: k, plaintext, warning: "shown once" });

let confirmReply = true;
beforeEach(() => {
  vi.useFakeTimers({ now: NOW, toFake: ["Date"] });
  confirmReply = true;
  vi.spyOn(window, "confirm").mockImplementation(() => confirmReply);
  api.listApiKeys.mockResolvedValue([key({})]);
  api.listApiKeyAudit.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)));
const dialog = () => screen.getByRole("dialog");

describe("the key list", () => {
  it("dates each key relative to now, and gives a revoked key no actions", async () => {
    api.listApiKeys.mockResolvedValue([
      key({ id: "a", name: "fresh", created_at: "2026-09-25T11:59:40Z", last_used_at: null }),
      key({ id: "b", name: "hours", created_at: "2026-09-25T09:00:00Z", last_used_at: "2026-09-25T11:30:00Z" }),
      key({ id: "c", name: "days", created_at: "2026-09-20T12:00:00Z", last_used_at: "2026-09-26T12:00:00Z" }),
      key({ id: "d", name: "old", created_at: "2026-01-05T12:00:00Z", revoked_at: "2026-02-01T00:00:00Z" }),
    ]);
    renderRoute(<ApiKeys />);

    const row = (name: string) => screen.getByText(name).closest<HTMLElement>("div.flex")!;
    expect(within(await screen.findByText("fresh").then(() => row("fresh"))).getByText("Just now")).toBeTruthy();
    expect(within(row("fresh")).getByText("Never")).toBeTruthy();
    expect(within(row("hours")).getByText("3h ago")).toBeTruthy();
    expect(within(row("hours")).getByText("30m ago")).toBeTruthy();
    expect(within(row("days")).getByText("5d ago")).toBeTruthy();
    expect(within(row("old")).getByText("Jan 5, 2026")).toBeTruthy();
    expect(within(row("old")).getByText("Revoked")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Revoke old" })).toBeNull();
    expect(screen.getByRole("button", { name: "Revoke fresh" })).toBeTruthy();
  });

  it("offers a first key when there are none, and says when the list could not load", async () => {
    api.listApiKeys.mockResolvedValue([]);
    renderRoute(<ApiKeys />);
    expect(await screen.findByText("No keys yet")).toBeTruthy();
    expect(screen.getAllByRole("button", { name: "New key" })).toHaveLength(2);
    cleanup();

    api.listApiKeys.mockRejectedValue(new Error("keys unavailable"));
    renderRoute(<ApiKeys />);
    expect(await screen.findByText("keys unavailable")).toBeTruthy();
  });
});

describe("creating a key", () => {
  it("sends the trimmed name and chosen scope, shows the secret once, and never again", async () => {
    api.createApiKey.mockResolvedValue(issued(key({ name: "ci" }), "tsy_a_secret_value"));
    renderRoute(<ApiKeys />);
    fireEvent.click((await screen.findAllByRole("button", { name: "New key" }))[0]);

    const create = within(dialog()).getByRole("button", { name: "Create key" }) as HTMLButtonElement;
    expect(create.disabled).toBe(true);
    fireEvent.change(within(dialog()).getByRole("textbox", { name: "Name" }), { target: { value: "  ci  " } });
    fireEvent.change(within(dialog()).getByRole("combobox", { name: "Scope" }), { target: { value: "admin" } });
    expect(within(dialog()).getByText(/Tool access over MCP/)).toBeTruthy();
    fireEvent.click(create);

    await waitFor(() => expect(api.createApiKey).toHaveBeenCalledWith({ name: "ci", scope: "admin" }));
    expect(await within(dialog()).findByText("tsy_a_secret_value")).toBeTruthy();
    expect(within(dialog()).getByRole("heading", { name: "Key ci created" })).toBeTruthy();

    fireEvent.click(within(dialog()).getByRole("button", { name: "Done" }));
    expect(screen.queryByText("tsy_a_secret_value")).toBeNull();
    fireEvent.click(screen.getAllByRole("button", { name: "New key" })[0]);
    expect(screen.queryByText("tsy_a_secret_value")).toBeNull();
    expect((within(dialog()).getByRole("textbox", { name: "Name" }) as HTMLInputElement).value).toBe("");
  });

  it("creates on Enter, does nothing on Enter with no name, and says why a create was refused", async () => {
    api.createApiKey.mockRejectedValue(new Error("name taken"));
    renderRoute(<ApiKeys />);
    fireEvent.click((await screen.findAllByRole("button", { name: "New key" }))[0]);
    const name = within(dialog()).getByRole("textbox", { name: "Name" });

    fireEvent.keyDown(name, { key: "Enter" });
    await settle();
    expect(api.createApiKey).not.toHaveBeenCalled();

    fireEvent.change(name, { target: { value: "dup" } });
    fireEvent.keyDown(name, { key: "Enter" });
    expect(await screen.findByText("Could not create key")).toBeTruthy();
    expect(screen.getByText("name taken")).toBeTruthy();

    fireEvent.click(within(dialog()).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("tells the reader to copy by hand when the clipboard refuses", async () => {
    api.createApiKey.mockResolvedValue(issued(key({ name: "ci" }), "tsy_a_secret_value"));
    Object.defineProperty(navigator, "clipboard", { value: undefined, configurable: true });
    renderRoute(<ApiKeys />);
    fireEvent.click((await screen.findAllByRole("button", { name: "New key" }))[0]);
    fireEvent.change(within(dialog()).getByRole("textbox", { name: "Name" }), { target: { value: "ci" } });
    fireEvent.click(within(dialog()).getByRole("button", { name: "Create key" }));

    fireEvent.click(await within(dialog()).findByRole("button", { name: "Copy" }));
    expect(await screen.findByText("Copy the key manually.")).toBeTruthy();
  });
});

describe("rotating and revoking", () => {
  it("rotates the key it was opened on and reveals the new secret", async () => {
    api.listApiKeys.mockResolvedValue([key({ id: "k-9", name: "billing", scope: "query", token_prefix: "tsy_q_zz" })]);
    api.rotateApiKey.mockResolvedValue(issued(key({ id: "k-9", name: "billing" }), "tsy_q_rotated"));
    renderRoute(<ApiKeys />);

    fireEvent.click(await screen.findByRole("button", { name: "Rotate billing" }));
    expect(within(dialog()).getByText("tsy_q_zz…")).toBeTruthy();
    fireEvent.click(within(dialog()).getByRole("button", { name: "Rotate key" }));

    await waitFor(() => expect(api.rotateApiKey).toHaveBeenCalledWith("k-9"));
    expect(await within(dialog()).findByText("tsy_q_rotated")).toBeTruthy();
    expect(within(dialog()).getByRole("heading", { name: "Key billing rotated" })).toBeTruthy();
  });

  it("names a refused rotate", async () => {
    api.rotateApiKey.mockRejectedValue(new Error("gone"));
    renderRoute(<ApiKeys />);
    fireEvent.click(await screen.findByRole("button", { name: "Rotate ingest-prod" }));
    fireEvent.click(within(dialog()).getByRole("button", { name: "Rotate key" }));
    expect(await screen.findByText("Could not rotate key")).toBeTruthy();
  });

  it("revokes only after confirmation, and names a refused revoke", async () => {
    api.revokeApiKey.mockRejectedValueOnce(new Error("already revoked")).mockResolvedValue({ revoked: true });
    renderRoute(<ApiKeys />);
    const revoke = await screen.findByRole("button", { name: "Revoke ingest-prod" });

    confirmReply = false;
    fireEvent.click(revoke);
    await settle();
    expect(api.revokeApiKey).not.toHaveBeenCalled();

    confirmReply = true;
    fireEvent.click(revoke);
    expect(await screen.findByText("Could not revoke key")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Revoke ingest-prod" }));
    expect(await screen.findByText("Key revoked")).toBeTruthy();
    expect(api.revokeApiKey).toHaveBeenLastCalledWith("k-1");
  });
});

describe("the audit trail", () => {
  it("names each action's key, and says when there is none or it could not load", async () => {
    const entry = (over: Partial<ApiKeyAudit>): ApiKeyAudit => ({ id: "a", project_id: "p", api_key_id: null, actor_user_id: null, action: "created", details: null, created_at: "2026-09-25T11:00:00Z", ...over });
    api.listApiKeyAudit.mockResolvedValue([
      entry({ id: "1", action: "created", details: "created ingest-prod" }),
      entry({ id: "2", action: "rotated", api_key_id: "0123456789abcdef" }),
      entry({ id: "3", action: "revoked" }),
    ]);
    renderRoute(<ApiKeys />);
    expect(await screen.findByText("created ingest-prod")).toBeTruthy();
    expect(screen.getByText("Key 01234567…")).toBeTruthy();
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
    cleanup();

    api.listApiKeyAudit.mockResolvedValue([]);
    renderRoute(<ApiKeys />);
    expect(await screen.findByText("No activity yet.")).toBeTruthy();
    cleanup();

    api.listApiKeyAudit.mockRejectedValue(new Error("audit unavailable"));
    renderRoute(<ApiKeys />);
    expect(await screen.findByText("audit unavailable")).toBeTruthy();
  });

  it("shows both reads loading", async () => {
    api.listApiKeys.mockImplementation(pending);
    api.listApiKeyAudit.mockImplementation(pending);
    renderRoute(<ApiKeys />);
    expect(await screen.findByText("Loading keys…")).toBeTruthy();
    expect(screen.getByText("Loading activity…")).toBeTruthy();
  });
});
