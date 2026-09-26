// SPDX-License-Identifier: Apache-2.0
/*
 * The Settings rail. The bugs worth catching: a section opening another project's settings, and the
 * wrong section marked as the one on screen.
 */
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { currentLocation, renderRoute } from "../../test/render";
import { SettingsLayout } from "./SettingsLayout";

vi.mock("../../tenant/TenantContext", () => ({ useTenant: () => ({ orgSlug: "acme", projectSlug: "default" }) }));
vi.mock("../../capabilities/useCapabilities", () => ({ useCapabilities: () => ({ capabilities: {} }) }));

afterEach(cleanup);

describe("SettingsLayout", () => {
  it("marks the section on screen, and opens another in this project", () => {
    renderRoute(<SettingsLayout />, { route: "/orgs/acme/projects/default/settings/sources/s-1" });
    const row = (label: string) => screen.getByText(label).closest("button")!;

    expect(row("Sources").className).toContain("bg-selected");
    expect(row("Members").className).not.toContain("bg-selected");

    fireEvent.click(row("Members"));
    expect(currentLocation()).toBe("/orgs/acme/projects/default/settings/members");
  });
});
