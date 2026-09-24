// SPDX-License-Identifier: Apache-2.0
/*
 * The flagged-answers read sends its page and cause under the names FindingController reads
 * (`limit`, `cursor`, `rcaReport`, `cause`): a renamed parameter is ignored there, and the server answers
 * with every flagged answer instead of the cause's.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { projectApi } from "./client";

const fetchMock = vi.fn(
  async (_url: string, _init?: RequestInit) =>
    new Response(JSON.stringify({ meta: { success: true }, data: { rows: [], total: 0, nextCursor: null } }), {
      status: 200,
    }),
);

afterEach(() => {
  vi.unstubAllGlobals();
  fetchMock.mockClear();
});

describe("projectApi.getFlaggedAnswers", () => {
  it("names the page and the cause as the controller reads them", async () => {
    vi.stubGlobal("fetch", fetchMock);
    const api = projectApi("acme", "default");

    await api.getFlaggedAnswers("fnd-1", { limit: 50, cursor: "50", cause: { rcaReport: "rca-1", index: 1 } });
    // The first cause is index 0, which must still be sent.
    await api.getFlaggedAnswers("fnd-1", { limit: 50, cursor: null, cause: { rcaReport: "rca-1", index: 0 } });

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      "/api/orgs/acme/projects/default/findings/fnd-1/flagged-answers?limit=50&cursor=50&rcaReport=rca-1&cause=1",
      "/api/orgs/acme/projects/default/findings/fnd-1/flagged-answers?limit=50&rcaReport=rca-1&cause=0",
    ]);
  });
});
