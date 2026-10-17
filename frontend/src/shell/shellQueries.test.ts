// SPDX-License-Identifier: Apache-2.0
import { QueryClient } from "@tanstack/react-query";
import { expect, it, vi } from "vitest";
import { prefetchProjectShell } from "./shellQueries";

// Bug: a project page's shell reads start under the raw, still-encoded slugs, or a page that is not a project's
// starts project reads.
it("starts the shell's four reads for a project page, under its decoded slugs, and none for any other page", () => {
  const queryClient = new QueryClient();
  const prefetch = vi.spyOn(queryClient, "prefetchQuery").mockResolvedValue();

  for (const path of ["/", "/orgs/acme", "/orgs/acme/projects", "/orgs//projects/bot", "/login/orgs/acme/projects/bot"]) {
    prefetchProjectShell(queryClient, path);
  }
  prefetchProjectShell(queryClient, "/orgs/acme%20labs/projects/my%2Fbot/traces");

  const base = "/api/orgs/acme%20labs/projects/my%2Fbot";
  expect(prefetch.mock.calls.map(([options]) => [options.queryKey, options.retry])).toEqual([
    [["projects", "acme labs"], false],
    [["capabilities", "acme labs"], false],
    [["substrate-status", base], false],
    [["cases", base], false],
  ]);
});
