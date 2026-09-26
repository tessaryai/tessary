// SPDX-License-Identifier: Apache-2.0
/*
 * Warming a route's code before it renders. The bugs worth catching: the wrong route's chunk loaded, and
 * a failed warm-up surfacing as an error when it is only ever a head start.
 */
import { describe, expect, it, vi } from "vitest";
import { preloadRouteChunk, registerRouteChunk } from "./routePreload";

describe("preloadRouteChunk", () => {
  it("loads the chunk registered for the page's segment, and nothing for a page with none", async () => {
    const traces = vi.fn(async () => ({}));
    const vitals = vi.fn(async () => ({}));
    registerRouteChunk("preload-traces", traces);
    registerRouteChunk("preload-vitals", vitals);

    preloadRouteChunk("/orgs/acme/projects/default/preload-traces/tr-1");
    preloadRouteChunk("/orgs/acme/projects/default");
    preloadRouteChunk("/orgs/acme/projects/default/unregistered");

    expect(traces).toHaveBeenCalledTimes(1);
    expect(vitals).not.toHaveBeenCalled();
  });

  it("swallows a chunk that fails to load", async () => {
    const failing = vi.fn(() => Promise.reject(new Error("offline")));
    registerRouteChunk("preload-failing", failing);

    expect(() => preloadRouteChunk("/orgs/acme/projects/default/preload-failing")).not.toThrow();
    await Promise.resolve();
    expect(failing).toHaveBeenCalled();
  });
});
