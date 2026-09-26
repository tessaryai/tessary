// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → Data retention. A retention value decides what the hourly sweep deletes, so the bugs worth
 * catching are the wrong number (or an untrimmed or non-numeric one) reaching the server, a cleared
 * override sent as a number instead of null, a no-op change that still offers Save, and a reader who
 * cannot manage retention being offered the controls.
 */
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { RetentionClassView, RetentionView } from "../../api/types";
import { renderRoute } from "../../test/render";
import { Retention } from "./Retention";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  getRetention: vi.fn(),
  updateRetention: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({ useProjectApi: () => api }));

const cls = (data_class: string, over: Partial<RetentionClassView> = {}): RetentionClassView => ({
  data_class,
  ttl_days: 30,
  from_policy: false,
  platform_default_days: 30,
  max_ttl_days: 0,
  ...over,
});
/** Traces follow the install default; detections carry an override that keeps them forever. */
const VIEW: RetentionView = {
  can_manage: true,
  classes: [cls("traces"), cls("detections", { ttl_days: 0, from_policy: true, platform_default_days: 1 })],
};

beforeEach(() => {
  api.getRetention.mockResolvedValue(VIEW);
  api.updateRetention.mockResolvedValue(VIEW);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const section = (title: string) => screen.getByText(title).closest<HTMLElement>("div.px-4")!;
const toggle = (title: string) => within(section(title)).getByRole("switch", { name: "Override for this project" });
const days = (title: string) => within(section(title)).queryByLabelText("Days to keep") as HTMLInputElement | null;
const save = () => screen.getByRole("button", { name: "Save" }) as HTMLButtonElement;
const ready = async () => {
  renderRoute(<Retention />);
  await screen.findByText("Traces");
};

describe("reading", () => {
  it("shows each class's install default and what applies now", async () => {
    await ready();

    expect(section("Traces").textContent).toContain("Install default: 30 days · currently 30 days");
    expect(section("Detections").textContent).toContain("Install default: 1 day · currently kept forever");
    expect(toggle("Traces").getAttribute("aria-checked")).toBe("false");
    expect(days("Traces")).toBeNull();
    expect(days("Detections")!.value).toBe("0");
    expect(save().disabled).toBe(true);
  });

  it("says when retention could not load", async () => {
    api.getRetention.mockRejectedValue(new Error("retention unavailable"));
    renderRoute(<Retention />);

    expect(await screen.findByText("retention unavailable")).toBeTruthy();
  });

  it("gives a reader who cannot manage retention no working control", async () => {
    api.getRetention.mockResolvedValue({ ...VIEW, can_manage: false });
    await ready();

    expect((toggle("Traces") as HTMLButtonElement).disabled).toBe(true);
    expect(days("Detections")!.disabled).toBe(true);
    fireEvent.change(days("Detections")!, { target: { value: "7" } });
    expect(save().disabled).toBe(true);
  });
});

describe("saving", () => {
  it("sends a new override as a trimmed number and keeps the other class as it was", async () => {
    await ready();

    fireEvent.click(toggle("Traces"));
    expect(days("Traces")!.value).toBe("30");
    fireEvent.change(days("Traces")!, { target: { value: " 7 " } });
    fireEvent.click(save());

    await waitFor(() => expect(api.updateRetention).toHaveBeenCalledWith({ traces: 7, detections: 0 }));
    expect(await screen.findByText("Retention saved")).toBeTruthy();
  });

  it("shows what the server stored after a save, not what was typed", async () => {
    const stored = { ...VIEW, classes: [cls("traces", { ttl_days: 5, from_policy: true }), VIEW.classes[1]] };
    await ready();
    api.getRetention.mockResolvedValue(stored);

    fireEvent.click(toggle("Traces"));
    fireEvent.change(days("Traces")!, { target: { value: "7" } });
    fireEvent.click(save());

    await waitFor(() => expect(days("Traces")!.value).toBe("5"));
    expect(save().disabled).toBe(true);
  });

  it("sends a cleared override as null, so the install default applies again", async () => {
    await ready();

    fireEvent.click(toggle("Detections"));
    expect(days("Detections")).toBeNull();
    fireEvent.click(save());

    await waitFor(() => expect(api.updateRetention).toHaveBeenCalledWith({ traces: null, detections: null }));
  });

  it("offers Save only when the effective value would change", async () => {
    await ready();

    fireEvent.click(toggle("Detections"));
    expect(save().disabled).toBe(false);
    fireEvent.click(toggle("Detections"));
    expect(days("Detections")!.value).toBe("0");
    expect(save().disabled).toBe(true);

    fireEvent.change(days("Detections")!, { target: { value: "00" } });
    expect(save().disabled).toBe(true);
    fireEvent.change(days("Detections")!, { target: { value: "90" } });
    expect(save().disabled).toBe(false);
  });

  it("refuses a value that is not a whole number of days up to a hundred years", async () => {
    await ready();

    for (const bad of ["", "abc", "-1", "1.5", "36501"]) {
      fireEvent.change(days("Detections")!, { target: { value: bad } });
      expect(save().disabled, bad).toBe(true);
    }
    fireEvent.change(days("Detections")!, { target: { value: "36500" } });
    expect(save().disabled).toBe(false);
  });

  it("says why a save was refused", async () => {
    api.updateRetention.mockRejectedValue(new Error("above the ceiling"));
    await ready();

    fireEvent.change(days("Detections")!, { target: { value: "90" } });
    fireEvent.click(save());

    expect(await screen.findByText("Could not save retention")).toBeTruthy();
    expect(screen.getByText("above the ceiling")).toBeTruthy();
  });
});
