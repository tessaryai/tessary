// SPDX-License-Identifier: Apache-2.0
/*
 * Every request the client makes names a route the backend serves. The OpenAPI spec is generated
 * from the controllers, so a path or verb here that the spec lacks is a call that 404s or 405s in
 * production: a renamed controller mapping, a typo in a template, a PUT sent where the server takes
 * a POST. Each call must also carry the CSRF header the auth filter requires on a cookie-authed
 * mutation, and the cookie itself.
 *
 * Beside it: how `http()` and the import upload turn a failed response into an ApiError.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import specJson from "../../../backend/contract/src/main/resources/openapi/tessary-api.json";
import { ApiError } from "./types";
import { auth, link, orgApi, projectApi } from "./client";

const spec = specJson as unknown as { paths: Record<string, Record<string, unknown>> };

const routes = Object.entries(spec.paths).map(([template, item]) => ({
  template,
  pattern: new RegExp(`^${template.replace(/\{[^}]+\}/g, "[^/]+")}$`),
  methods: new Set(Object.keys(item).map((m) => m.toUpperCase())),
}));

function served(method: string, url: string): boolean {
  const path = url.split("?")[0];
  return routes.some((r) => r.pattern.test(path) && r.methods.has(method));
}

interface Call {
  url: string;
  init: RequestInit;
}

function stubFetch(response: () => Response): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit = {}) => {
    calls.push({ url, init });
    return response();
  });
  return calls;
}

const ok = (data: unknown) => () => new Response(JSON.stringify({ meta: { success: true }, data }), { status: 200 });

afterEach(() => {
  vi.unstubAllGlobals();
});

const clients: [string, Record<string, unknown>][] = [
  ["auth", auth],
  ["link", link],
  ["orgApi", orgApi("acme")],
  ["projectApi", projectApi("acme", "default")],
];

const endpoints = clients.flatMap(([client, api]) =>
  Object.entries(api)
    .filter(([name, fn]) => typeof fn === "function" && name !== "loginUrl")
    .map(([name, fn]) => [`${client}.${name}`, fn as (...args: unknown[]) => Promise<unknown>] as const),
);

describe("the client's routes", () => {
  it.each(endpoints)("%s calls a route the backend serves, with the CSRF header and the cookie", async (name, fn) => {
    const calls = stubFetch(ok({}));

    await fn(...(name.endsWith(".importEvalsDirectory") ? [[]] : ["id-1", "id-2", "id-3"]));

    expect(calls).toHaveLength(1);
    const [{ url, init }] = calls;
    const method = init.method ?? "GET";
    expect(served(method, url), `${method} ${url}`).toBe(true);
    expect((init.headers as Record<string, string>)["X-Requested-With"]).toBe("XMLHttpRequest");
    expect(init.credentials).toBe("include");
  });

  it("escapes every path segment, so an id cannot reach a different route", async () => {
    const calls = stubFetch(ok({}));

    await projectApi("a/b", "c?d").getCase("../../members");

    expect(calls[0].url).toBe("/api/orgs/a%2Fb/projects/c%3Fd/cases/..%2F..%2Fmembers");
  });

  it("builds the login URL with the return path escaped", () => {
    expect(auth.loginUrl("/acme/default/cases?x=1")).toBe("/auth/login?returnTo=%2Facme%2Fdefault%2Fcases%3Fx%3D1");
    expect(auth.loginUrl()).toBe("/auth/login");
  });
});

describe("http()", () => {
  const getCase = () => projectApi("acme", "default").getCase("c1");

  it("reads a bare 401 as signed out, not as a malformed envelope", async () => {
    stubFetch(() => new Response(JSON.stringify({ error: "unauthorized" }), { status: 401 }));

    await expect(getCase()).rejects.toMatchObject({ status: 401, code: "auth.unauthorized" });
  });

  it("names a body that is not JSON instead of throwing a SyntaxError", async () => {
    stubFetch(() => new Response("<html>bad gateway</html>", { status: 502, statusText: "Bad Gateway" }));

    await expect(getCase()).rejects.toMatchObject({
      status: 502,
      code: "COMMON.INVALID_BODY",
      detail: "502 Bad Gateway: response was not JSON",
    });
  });

  it("surfaces the envelope's own error, and a generic one when the envelope names none", async () => {
    stubFetch(
      () =>
        new Response(JSON.stringify({ meta: { success: false, error: { code: "CASE.NOT_FOUND", message: "no case c1" } } }), {
          status: 404,
        }),
    );
    await expect(getCase()).rejects.toMatchObject({ status: 404, code: "CASE.NOT_FOUND", detail: "no case c1" });

    stubFetch(() => new Response(JSON.stringify({ meta: { success: false } }), { status: 500, statusText: "Server Error" }));
    await expect(getCase()).rejects.toMatchObject({ status: 500, code: "COMMON.INTERNAL", detail: "500 Server Error" });
  });

  it("reads a project with no git integration as null, which a query can cache", async () => {
    stubFetch(() => new Response(JSON.stringify({ meta: { success: true } }), { status: 200 }));

    await expect(projectApi("acme", "default").getGitIntegration()).resolves.toBeNull();
  });
});

describe("importEvalsDirectory", () => {
  const upload = (files: File[] = []) => projectApi("acme", "default").importEvalsDirectory(files, "replace");

  it("uploads each file under its path inside the picked directory", async () => {
    const calls = stubFetch(ok({ mode: "replace" }));
    const shard = new File(["x"], "call_sites.yaml");
    Object.defineProperty(shard, "webkitRelativePath", { value: ".tessary/pipeline/call_sites.yaml" });

    await expect(upload([shard, new File(["y"], "loose.yaml")])).resolves.toEqual({ mode: "replace" });

    expect(calls[0].url).toBe("/api/orgs/acme/projects/default/import?mode=replace");
    const names = (calls[0].init.body as FormData).getAll("files").map((f) => (f as File).name);
    expect(names).toEqual([".tessary/pipeline/call_sites.yaml", "loose.yaml"]);
  });

  it("turns each kind of failure into an ApiError the import page can show", async () => {
    stubFetch(
      () =>
        new Response(JSON.stringify({ meta: { error: { code: "IMPORT.INVALID_SHARD", message: "bad shard" } } }), {
          status: 422,
        }),
    );
    await expect(upload()).rejects.toMatchObject({ status: 422, code: "IMPORT.INVALID_SHARD" });

    stubFetch(() => new Response(JSON.stringify({ meta: {} }), { status: 400 }));
    await expect(upload()).rejects.toMatchObject({ status: 400, code: "import.failed", detail: '{"meta":{}}' });

    stubFetch(() => new Response("request entity too large", { status: 413 }));
    await expect(upload()).rejects.toMatchObject({ status: 413, code: "import.failed", detail: "request entity too large" });

    stubFetch(() => new Response(JSON.stringify({ meta: { success: true }, data: null }), { status: 200 }));
    const empty = upload();
    await expect(empty).rejects.toBeInstanceOf(ApiError);
    await expect(empty).rejects.toMatchObject({ code: "import.empty_response" });
  });
});
