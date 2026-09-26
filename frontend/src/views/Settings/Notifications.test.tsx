// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → Notifications. The bugs worth catching: a failed read that looks like "nothing is set up",
 * a half-set quiet window saved as if it were one, a webhook URL saved with its stray whitespace, the
 * switch that turns the rule off having no name, and the Slack field showing for an org without Slack.
 */
import { act, cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/types";
import type { AlertChannel, AlertRule } from "../../api/types";
import { renderRoute } from "../../test/render";
import { Notifications } from "./Notifications";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  listAlertRules: vi.fn(),
  listAlertChannels: vi.fn(),
  upsertAlertRule: vi.fn(),
  setAlertRuleEnabled: vi.fn(),
  createAlertChannel: vi.fn(),
  deleteAlertChannel: vi.fn(),
}));
const caps = vi.hoisted(() => new Set<string>());

vi.mock("../../tenant/TenantContext", () => ({ useProjectApi: () => api }));
vi.mock("../../capabilities/useCapabilities", () => ({
  useCapabilities: () => ({ isEnabled: (c: string) => caps.has(c) }),
}));

const rule = (over: Partial<AlertRule> = {}) =>
  ({
    id: "r-1",
    name: "A case opens",
    rule_type: "case_opened",
    enabled: true,
    created_at: "2026-09-01T00:00:00Z",
    policy: { cadence_seconds: 3600, quiet_from: "22:00", quiet_to: "06:00", quiet_zone: "Europe/London" },
    ...over,
  }) as AlertRule;
const channel = (over: Partial<AlertChannel> = {}): AlertChannel => ({
  id: "ch-1",
  name: "Webhook",
  kind: "webhook",
  enabled: true,
  credentialsSet: true,
  createdAt: "2026-09-01T00:00:00Z",
  updatedAt: "2026-09-01T00:00:00Z",
  ...over,
});

beforeEach(() => {
  caps.clear();
  api.listAlertRules.mockResolvedValue([rule({ id: "other", rule_type: "digest", name: "Digest" }), rule()]);
  api.listAlertChannels.mockResolvedValue([]);
  api.upsertAlertRule.mockResolvedValue(rule());
  api.setAlertRuleEnabled.mockResolvedValue(rule());
  api.createAlertChannel.mockResolvedValue(channel());
  api.deleteAlertChannel.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)));
const input = (label: string) => screen.getByLabelText(label) as HTMLInputElement;
const type = (label: string, value: string) => fireEvent.change(input(label), { target: { value } });

describe("the reads", () => {
  it("say a failed read failed, rather than showing an unset rule and no destinations", async () => {
    api.listAlertRules.mockRejectedValue(new Error("rules unavailable"));
    api.listAlertChannels.mockRejectedValue(new Error("channels unavailable"));
    renderRoute(<Notifications />);

    expect(await screen.findByText("rules unavailable")).toBeTruthy();
    expect(await screen.findByText("channels unavailable")).toBeTruthy();
    expect(screen.queryByText(/no case-opened rule yet/)).toBeNull();
    expect(screen.queryByLabelText("Webhook URL")).toBeNull();
  });

  it("shows each failed read's code and detail once", async () => {
    api.listAlertRules.mockRejectedValue(new ApiError(503, { code: "UNAVAILABLE", message: "rules unavailable" }));
    api.listAlertChannels.mockRejectedValue(new ApiError(503, { code: "UNAVAILABLE", message: "channels unavailable" }));
    renderRoute(<Notifications />);

    await waitFor(() => expect(screen.getAllByRole("alert")).toHaveLength(2));
    expect(screen.getAllByRole("alert").map((a) => a.textContent)).toEqual([
      "UNAVAILABLE: rules unavailable",
      "UNAVAILABLE: channels unavailable",
    ]);
  });

  it("says when the project has no case-opened rule", async () => {
    api.listAlertRules.mockResolvedValue([rule({ rule_type: "digest" })]);
    renderRoute(<Notifications />);

    expect(await screen.findByText(/no case-opened rule yet/)).toBeTruthy();
    expect(screen.queryByRole("switch")).toBeNull();
  });
});

describe("the case-opened rule", () => {
  it("mirrors the saved policy, and names the window that crosses midnight", async () => {
    renderRoute(<Notifications />);

    await screen.findByRole("switch", { name: "Notify when a case opens" });
    expect((screen.getByLabelText("Cadence") as HTMLSelectElement).value).toBe("3600");
    expect(input("From").value).toBe("22:00");
    expect(input("To").value).toBe("06:00");
    expect(input("Time zone").value).toBe("Europe/London");
    expect(screen.getByText(/crosses midnight/)).toBeTruthy();

    type("From", "01:00");
    expect(screen.queryByText(/crosses midnight/)).toBeNull();
  });

  it("starts the zone at the browser's own when the rule has no policy yet", async () => {
    api.listAlertRules.mockResolvedValue([rule({ policy: null })]);
    renderRoute(<Notifications />);

    await screen.findByRole("switch");
    expect(input("Time zone").value).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC");
  });

  it("turns the rule off by its own id", async () => {
    renderRoute(<Notifications />);

    fireEvent.click(await screen.findByRole("switch", { name: "Notify when a case opens" }));
    await waitFor(() => expect(api.setAlertRuleEnabled).toHaveBeenCalledWith("r-1", false));
  });

  it("saves the policy as edited", async () => {
    renderRoute(<Notifications />);
    await screen.findByRole("switch");

    fireEvent.change(screen.getByLabelText("Cadence"), { target: { value: "900" } });
    type("Time zone", "America/New_York");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() =>
      expect(api.upsertAlertRule).toHaveBeenCalledWith({
        rule_type: "case_opened",
        name: "A case opens",
        policy: { cadence_seconds: 900, quiet_from: "22:00", quiet_to: "06:00", quiet_zone: "America/New_York" },
      }),
    );
    expect(await screen.findByText("Notification policy saved")).toBeTruthy();
  });

  it("saves a half-set quiet window as no window at all", async () => {
    renderRoute(<Notifications />);
    await screen.findByRole("switch");

    type("To", "");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(api.upsertAlertRule).toHaveBeenCalledTimes(1));

    type("To", "06:00");
    type("From", "");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(api.upsertAlertRule).toHaveBeenCalledTimes(2));

    for (const [body] of api.upsertAlertRule.mock.calls) {
      expect(body.policy).toMatchObject({ quiet_from: null, quiet_to: null });
    }
  });

  it("says when a save or a toggle fails", async () => {
    api.upsertAlertRule.mockRejectedValue(new Error("policy rejected"));
    api.setAlertRuleEnabled.mockRejectedValue(new Error("toggle rejected"));
    renderRoute(<Notifications />);
    await screen.findByRole("switch");

    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText("policy rejected")).toBeTruthy();
    fireEvent.click(screen.getByRole("switch"));
    expect(await screen.findByText("toggle rejected")).toBeTruthy();
  });
});

describe("the delivery channels", () => {
  it("adds a webhook with its URL trimmed, then clears the field", async () => {
    renderRoute(<Notifications />);
    const add = await screen.findByRole("button", { name: "Add webhook" });

    type("Webhook URL", "   ");
    expect((add as HTMLButtonElement).disabled).toBe(true);
    type("Webhook URL", "  https://example.com/hook  ");
    fireEvent.click(add);

    await waitFor(() =>
      expect(api.createAlertChannel).toHaveBeenCalledWith({
        kind: "webhook",
        name: "Webhook",
        enabled: true,
        config: { url: "https://example.com/hook" },
      }),
    );
    expect(await screen.findByText("Webhook added")).toBeTruthy();
    expect(input("Webhook URL").value).toBe("");
  });

  it("lists each channel and removes the one asked for", async () => {
    api.listAlertChannels.mockResolvedValue([channel(), channel({ id: "ch-2", name: "Ops", enabled: false })]);
    renderRoute(<Notifications />);

    expect(await screen.findByText("Ops")).toBeTruthy();
    expect(screen.getByText("Disabled")).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "Remove" })[1]);

    await waitFor(() => expect(api.deleteAlertChannel).toHaveBeenCalledWith("ch-2"));
    expect(await screen.findByText("Channel removed")).toBeTruthy();
  });

  it("says when adding or removing a channel fails, and keeps the typed URL", async () => {
    api.listAlertChannels.mockResolvedValue([channel()]);
    api.createAlertChannel.mockRejectedValue(new Error("bad url"));
    api.deleteAlertChannel.mockRejectedValue(new Error("in use"));
    renderRoute(<Notifications />);
    await screen.findByText("Webhook", { selector: "span" });

    type("Webhook URL", "https://example.com/hook");
    fireEvent.click(screen.getByRole("button", { name: "Add webhook" }));
    expect(await screen.findByText("bad url")).toBeTruthy();
    expect(input("Webhook URL").value).toBe("https://example.com/hook");

    fireEvent.click(screen.getByRole("button", { name: "Remove" }));
    expect(await screen.findByText("in use")).toBeTruthy();
  });

  it("never mentions Slack to an org without it", async () => {
    renderRoute(<Notifications />);
    await screen.findByRole("button", { name: "Add webhook" });
    await settle();

    expect(screen.queryByText(/Slack/)).toBeNull();
  });

  it("adds a Slack channel for an org that has it", async () => {
    caps.add("slack_enabled");
    api.createAlertChannel.mockRejectedValueOnce(new Error("not a slack url"));
    renderRoute(<Notifications />);

    await screen.findByLabelText("Slack incoming webhook");
    type("Slack incoming webhook", " https://hooks.slack.com/x ");
    fireEvent.click(screen.getByRole("button", { name: "Add Slack channel" }));
    expect(await screen.findByText("not a slack url")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Add Slack channel" }));
    await waitFor(() =>
      expect(api.createAlertChannel).toHaveBeenLastCalledWith({
        kind: "slack",
        name: "Slack",
        enabled: true,
        config: { url: "https://hooks.slack.com/x" },
      }),
    );
    expect(await screen.findByText("Slack channel added")).toBeTruthy();
    expect(input("Slack incoming webhook").value).toBe("");
  });
});
