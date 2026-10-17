// SPDX-License-Identifier: Apache-2.0
/*
 * The gate on a capability's pages. The bugs worth catching: a gated page shown to an org without the
 * capability (the server refuses its calls, so it would only ever fail), and one bounced while the
 * capabilities are still being read.
 */
import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { currentLocation, renderRoute } from "../test/render";
import { CapabilityGate } from "./CapabilityGate";

const caps = vi.hoisted(() => ({ loading: false, on: new Set<string>() }));
vi.mock("./useCapabilities", () => ({
  useCapabilities: () => ({ isLoading: caps.loading, isEnabled: (c: string) => caps.on.has(c) }),
}));
vi.mock("../tenant/TenantContext", () => import("../test/api"));

afterEach(() => {
  caps.loading = false;
  caps.on.clear();
});

const PAGE = "/orgs/acme/projects/default/settings/notifications";
const gate = () =>
  renderRoute(
    <CapabilityGate capability="alerts_enabled">
      <p>gated page</p>
    </CapabilityGate>,
    { route: PAGE },
  );

describe("CapabilityGate", () => {
  it.each([
    ["shows the page to an org with the capability", false, true, "gated page", PAGE],
    ["sends an org without it to Triage", false, false, null, "/orgs/acme/projects/default/triage"],
    ["waits, rather than bouncing, while the capabilities are read", true, false, "Loading…", PAGE],
  ])("%s", (_, loading, on, shown, location) => {
    caps.loading = loading;
    if (on) caps.on.add("alerts_enabled");
    gate();

    if (shown) expect(screen.getByText(shown)).toBeTruthy();
    expect(currentLocation()).toBe(location);
  });
});
