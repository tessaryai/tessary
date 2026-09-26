// SPDX-License-Identifier: Apache-2.0
/*
 * The gate on a capability's pages. The bugs worth catching: a gated page shown to an org without the
 * capability (the server refuses its calls, so it would only ever fail), and one bounced while the
 * capabilities are still being read.
 */
import { cleanup, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { currentLocation, renderRoute } from "../test/render";
import { CapabilityGate } from "./CapabilityGate";

const caps = vi.hoisted(() => ({ loading: false, on: new Set<string>() }));
vi.mock("./useCapabilities", () => ({
  useCapabilities: () => ({ isLoading: caps.loading, isEnabled: (c: string) => caps.on.has(c) }),
}));
vi.mock("../tenant/TenantContext", () => ({ useTenant: () => ({ orgSlug: "acme", projectSlug: "default" }) }));

afterEach(() => {
  cleanup();
  caps.loading = false;
  caps.on.clear();
});

const gate = () =>
  renderRoute(
    <CapabilityGate capability="alerts_enabled">
      <p>gated page</p>
    </CapabilityGate>,
    { route: "/orgs/acme/projects/default/settings/notifications" },
  );

describe("CapabilityGate", () => {
  it("shows the page to an org with the capability", () => {
    caps.on.add("alerts_enabled");
    gate();

    expect(screen.getByText("gated page")).toBeTruthy();
  });

  it("sends an org without it to Triage", () => {
    gate();

    expect(currentLocation()).toBe("/orgs/acme/projects/default/triage");
  });

  it("waits, rather than bouncing, while the capabilities are read", () => {
    caps.loading = true;
    gate();

    expect(screen.getByText("Loading…")).toBeTruthy();
    expect(currentLocation()).toBe("/orgs/acme/projects/default/settings/notifications");
  });
});
