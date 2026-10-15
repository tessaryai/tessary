// SPDX-License-Identifier: Apache-2.0
/*
 * The one API mock page tests share. `api`, `orgApi` and `auth` hand out a vi.fn() for whatever method a test or
 * the page under test reaches for, so a test names only the calls it stubs or asserts. A test file opts in with
 *   vi.mock("../../tenant/TenantContext", () => import("../../test/api"));
 *   vi.mock("../../api/client", () => import("../../test/api"));
 * and imports `api` (or `orgApi`, `auth`) from here to stub it. Vitest gives every test file its own module graph,
 * so these mocks are per file, and every one is reset after each test: a stub lives only in the test that set it.
 */
import { afterEach, vi, type Mock } from "vitest";

type MockApi = Record<string, Mock> & { base: string };

const created: Mock[] = [];

/** `then` stays undefined: a thenable api would be awaited as a promise wherever it is returned from async code. */
function autoMock(base: string): MockApi {
  return new Proxy({ base } as unknown as MockApi, {
    get(target, key, receiver) {
      if (typeof key === "symbol" || key === "then" || key in target) return Reflect.get(target, key, receiver);
      const fn = vi.fn();
      created.push(fn);
      Reflect.set(target, key, fn);
      return fn;
    },
  });
}

export const api = autoMock("/api/orgs/acme/projects/default");
export const orgApi = autoMock("/api/orgs/acme");
export const auth = autoMock("/api/auth");
export const tenant = { orgSlug: "acme", projectSlug: "default", api, orgApi };

export const useTenant = () => tenant;
export const useProjectApi = () => api;
export const useOrgApi = () => orgApi;

afterEach(() => {
  for (const fn of created) fn.mockReset();
});
