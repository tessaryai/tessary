// SPDX-License-Identifier: Apache-2.0
/*
 * Which paths the org can reach, for the palette's recents. The bugs worth catching: a recent to a gated
 * surface offered to an org without it, and a path with no surface (the project root) refused.
 */
import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useNavigation } from "./useNavigation";

const caps = vi.hoisted(() => ({ map: {} as Record<string, boolean> }));
vi.mock("../capabilities/useCapabilities", () => ({ useCapabilities: () => ({ capabilities: caps.map }) }));

describe("isPathReachable", () => {
  it("reaches the project root and ungated pages, and refuses a gated settings section the org lacks", () => {
    const { result } = renderHook(() => useNavigation());
    const reachable = result.current.isPathReachable;

    expect(reachable("/orgs/acme/projects/default")).toBe(true);
    expect(reachable("/orgs/acme/projects/default/triage")).toBe(true);
    expect(reachable("/orgs/acme/projects/default/settings")).toBe(true);
    expect(reachable("/orgs/acme/projects/default/settings/providers")).toBe(false);
  });
});
