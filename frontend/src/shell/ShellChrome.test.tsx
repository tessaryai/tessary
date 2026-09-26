// SPDX-License-Identifier: Apache-2.0
/*
 * The shell around every project page. The bugs worth catching: the sample-data banner missing on the
 * sample project (or shown on a real one), its way out going somewhere other than the org, and the
 * palette's Recent list recording a page that is not a top-level surface, or another project's.
 */
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { currentLocation, renderRoute } from "../test/render";
import { readRecents } from "./recents";
import { ShellChrome } from "./ShellChrome";

vi.mock("../tenant/TenantContext", () => ({ useTenant: () => ({ orgSlug: "acme", projectSlug: "default" }) }));
vi.mock("./Sidebar", () => ({ Sidebar: () => null }));
vi.mock("./CommandPalette", () => ({ CommandPaletteDock: () => null }));

afterEach(() => {
  cleanup();
  window.localStorage.clear();
});

const shell = (route: string, isSample = false) =>
  renderRoute(
    <ShellChrome isSample={isSample}>
      <p>page body</p>
    </ShellChrome>,
    { route },
  );

describe("ShellChrome", () => {
  it("wraps the page with a skip link to its content, and no sample banner on a real project", () => {
    shell("/orgs/acme/projects/default/traces");

    expect(screen.getByText("page body").closest("main")!.id).toBe("main-content");
    expect(screen.getByRole("link", { name: "Skip to content" }).getAttribute("href")).toBe("#main-content");
    expect(screen.queryByText("Sample data")).toBeNull();
  });

  it("marks the sample project, and its way out goes to the org", () => {
    shell("/orgs/acme/projects/default/traces", true);

    expect(screen.getByText("Sample data")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Connect your own traces" }));

    expect(currentLocation()).toBe("/orgs/acme");
  });

  it("records a visit to a top-level surface in this project's recents", () => {
    shell("/orgs/acme/projects/default/traces/tr-1");

    expect(readRecents("acme", "default").map((r) => r.path)).toEqual(["/orgs/acme/projects/default/traces"]);
  });

  it.each([
    ["the project root", "/orgs/acme/projects/default/"],
    ["the bare project path", "/orgs/acme/projects/default"],
    // Seven characters, like "default", so its path lines up with a surface name past this project's base.
    ["another project", "/orgs/acme/projects/staging/traces"],
    ["a page that is not a surface", "/orgs/acme/projects/default/not-a-surface"],
  ])("records nothing for %s", (_what, route) => {
    shell(route);

    expect(readRecents("acme", "default")).toEqual([]);
  });
});
