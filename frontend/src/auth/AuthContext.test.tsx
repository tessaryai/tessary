// SPDX-License-Identifier: Apache-2.0
/*
 * The session and the route guard in front of every signed-in page. The bugs worth catching: a failed
 * session read that leaves the app spinning or lets a visitor through, a signed-out visitor sent to
 * sign in without the page they were on, and a component outside the provider failing silently.
 */
import { render, renderHook, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { ApiError } from "../api/types";
import { AuthProvider, useAuth } from "./AuthContext";
import { ProtectedRoute } from "./ProtectedRoute";
import { auth } from "../test/api";

vi.mock("../api/client", () => import("../test/api"));

const ME = { id: "u-1", email: "dana@example.com", orgs: [], platform_staff: false };

beforeEach(() => {
  auth.me.mockResolvedValue(ME);
  auth.loginUrl.mockImplementation((r?: string) => `/auth/login?returnTo=${r}`);
});

const guarded = (route = "/orgs/acme/projects/default/traces?q=refund") =>
  render(
    <MemoryRouter initialEntries={[route]}>
      <AuthProvider>
        <ProtectedRoute>
          <p>the page</p>
        </ProtectedRoute>
      </AuthProvider>
    </MemoryRouter>,
  );

describe("the route guard", () => {
  it("lets a signed-in visitor through once the session is read", async () => {
    guarded();

    expect(screen.getByText("Loading…")).toBeTruthy();
    expect(await screen.findByText("the page")).toBeTruthy();
    expect(auth.loginUrl).not.toHaveBeenCalled();
  });

  it.each([
    ["sends a signed-out visitor to sign in, carrying the page they were on", 401, []],
    [
      "treats any other failed session read as signed out too, and says so in the console",
      503,
      [["auth/me failed:", expect.stringContaining("down")]],
    ],
  ])("%s", async (_, status, warned) => {
    auth.me.mockRejectedValue(new ApiError(status, { code: `HTTP_${status}`, message: "down" }));
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    guarded();

    await waitFor(() => expect(auth.loginUrl).toHaveBeenCalledWith("/orgs/acme/projects/default/traces?q=refund"));
    expect(screen.queryByText("the page")).toBeNull();
    expect(warn.mock.calls).toEqual(warned);
  });
});

it("useAuth: refuses to run outside its provider", () => {
  vi.spyOn(console, "error").mockImplementation(() => {});

  expect(() => renderHook(() => useAuth())).toThrow("useAuth must be used inside <AuthProvider>");
});
