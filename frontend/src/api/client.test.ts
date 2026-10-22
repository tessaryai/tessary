// SPDX-License-Identifier: Apache-2.0
/*
 * The flagged-answers read sends its page and cause under the names FindingController reads
 * (`limit`, `cursor`, `rcaReport`, `cause`): a renamed parameter is ignored there, and the server answers
 * with every flagged answer instead of the cause's.
 */
import { afterEach, expect, it, vi } from "vitest";
import { projectApi } from "./client";

const fetchMock = vi.fn(
  async (_url: string, _init?: RequestInit) =>
    new Response(JSON.stringify({ meta: { success: true }, data: { rows: [], total: 0, nextCursor: null } }), {
      status: 200,
    }),
);

afterEach(() => {
  fetchMock.mockClear();
});

it("projectApi.getFlaggedAnswers: names the page and the cause as the controller reads them", async () => {
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

it("the other paged finding reads: name their filters and cursors as FindingController reads them", async () => {
  vi.stubGlobal("fetch", fetchMock);
  const api = projectApi("acme", "default");

  await api.getBehaviorFindingEvidence("fnd-1", { role: "exemplar", limit: 0, cursor: "c9" });
  await api.getBehaviorFindingEvidence("fnd-1");
  await api.getFrustratedSessions("fnd-1", { limit: 25, cursor: "25", cause: { rcaReport: "rca-2", index: 0 } });
  await api.getMalformedOutputs("fnd-1", "items[].sku", { limit: 10, cursor: "c2" });

  expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
    "/api/orgs/acme/projects/default/findings/fnd-1/evidence?role=exemplar&limit=0&cursor=c9",
    "/api/orgs/acme/projects/default/findings/fnd-1/evidence",
    "/api/orgs/acme/projects/default/findings/fnd-1/frustrated-sessions?limit=25&cursor=25&rcaReport=rca-2&cause=0",
    "/api/orgs/acme/projects/default/findings/fnd-1/malformed-outputs?field=items%5B%5D.sku&limit=10&cursor=c2",
  ]);
});


// Bug: a steps read that drops `after`, or names it otherwise, makes the server send every step on each poll, and an
// open steps list grows the same steps again every four seconds.
it("the run steps reads: send the step to read after as the controllers read it", async () => {
  vi.stubGlobal("fetch", fetchMock);
  const api = projectApi("acme", "default");

  await api.getTriageSteps("fnd-1", 0);
  await api.getRcaSteps("rca-2", 7);

  expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
    "/api/orgs/acme/projects/default/findings/fnd-1/triage-steps?after=0",
    "/api/orgs/acme/projects/default/rca/rca-2/steps?after=7",
  ]);
});

// Bug: a scope or range sent under another name than ClassifierChartController reads (`callSiteId`, `tool`,
// `days`) answers 422 CLASSIFIER.CHART_SCOPE, or the default range, for every card on the page.
it("the classifier chart reads: name the scope and the range as the controller reads them", async () => {
  vi.stubGlobal("fetch", fetchMock);
  const api = projectApi("acme", "default");

  await api.getClassifierChartScopes(28);
  await api.getClassifierCharts({ callSiteId: "support/agent" }, 7);
  await api.getClassifierCharts({ tool: "tool:search docs" }, 28);

  expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
    "/api/orgs/acme/projects/default/classifiers/chart-scopes?days=28",
    "/api/orgs/acme/projects/default/classifiers/charts?callSiteId=support%2Fagent&days=7",
    "/api/orgs/acme/projects/default/classifiers/charts?tool=tool%3Asearch+docs&days=28",
  ]);
});

// Bug: a superseded metrics read keeps running on the server because the fetch never sees the query's abort signal.
it("projectApi.queryMetrics: posts the request and hands fetch the abort signal", async () => {
  vi.stubGlobal("fetch", fetchMock);
  const api = projectApi("acme", "default");
  const signal = new AbortController().signal;
  const req = {
    view: "traces", measures: ["traces"], dimension: null, filters: null, from: "2026-10-11T00:00:00Z",
    to: "2026-10-18T00:00:00Z", granularity: null, top_n: null, compare: null,
  };

  await api.queryMetrics(req, signal);

  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe("/api/orgs/acme/projects/default/metrics/query");
  expect(init).toMatchObject({ method: "POST", body: JSON.stringify(req), signal });
});
