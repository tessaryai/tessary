// SPDX-License-Identifier: Apache-2.0
/*
 * The public sign-in and sign-up screens. The bugs worth catching: a signed-in visitor shown a form, a
 * redirect-flow deployment rendering a form nobody can submit (or looping a refused sign-up back into
 * the provider), a first-run deployment offering sign-in to nobody, the credentials sent wrong, and
 * the return path lost on the way through.
 */
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentLocation, pending, renderRoute } from "../../test/render";
import { Login } from "./Login";
import { Signup } from "./Signup";

const { auth, session } = vi.hoisted(() => ({
  auth: {
    mode: vi.fn(),
    login: vi.fn(),
    signup: vi.fn(),
    loginUrl: vi.fn((returnTo?: string) => `/auth/login${returnTo ? `?returnTo=${returnTo}` : ""}`),
  },
  session: { isAuthenticated: false, refetch: vi.fn() },
}));

vi.mock("../../api/client", () => ({ auth }));
vi.mock("../../auth/AuthContext", () => ({ useAuth: () => session }));

const FORM_MODE = { redirectFlow: false, firstRun: false, signupPolicy: "open" };

beforeEach(() => {
  session.isAuthenticated = false;
  session.refetch.mockResolvedValue(undefined);
  auth.mode.mockResolvedValue(FORM_MODE);
  auth.login.mockResolvedValue({});
  auth.signup.mockResolvedValue({});
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const fill = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label, { exact: false }), { target: { value } });
const button = (name: RegExp) => screen.getByRole("button", { name }) as HTMLButtonElement;

describe.each([
  ["Login", <Login key="l" />, "/login"],
  ["Signup", <Signup key="s" />, "/signup"],
])("%s, before the form", (_name, screenEl, path) => {
  it("sends a signed-in visitor straight to where they were going", () => {
    session.isAuthenticated = true;
    renderRoute(screenEl, { route: `${path}?returnTo=/orgs/acme`, path });

    expect(currentLocation()).toBe("/orgs/acme");
    expect(screen.queryByRole("textbox")).toBeNull();
  });

  it("sends a signed-in visitor with nowhere in particular to go to the root", () => {
    session.isAuthenticated = true;
    renderRoute(screenEl, { route: path, path });

    expect(currentLocation()).toBe("/");
  });

  it("waits on the auth mode, then hands a redirect-flow deployment to its provider with the return path", async () => {
    auth.mode.mockReturnValueOnce(pending());
    renderRoute(screenEl, { route: path });
    expect(screen.queryByRole("textbox")).toBeNull();
    cleanup();

    auth.mode.mockResolvedValue({ ...FORM_MODE, redirectFlow: true });
    renderRoute(screenEl, { route: `${path}?returnTo=/orgs/acme` });

    await waitFor(() => expect(auth.loginUrl).toHaveBeenCalledWith("/orgs/acme"));
    expect(screen.queryByRole("textbox")).toBeNull();
  });
});

describe("Login", () => {
  it("signs in with the typed credentials, then goes where the visitor was going", async () => {
    renderRoute(<Login />, { route: "/login?returnTo=/orgs/acme/projects/default" });
    await screen.findByRole("heading", { name: "Sign in" });

    expect(button(/Sign in/).disabled).toBe(true);
    fill("Email", "dana@example.com");
    expect(button(/Sign in/).disabled).toBe(true);
    fill("Password", "hunter22");
    fireEvent.click(button(/Sign in/));

    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default"));
    expect(auth.login).toHaveBeenCalledWith("dana@example.com", "hunter22");
    expect(session.refetch).toHaveBeenCalled();
  });

  it("names a refused sign-in on the form, and keeps the visitor there", async () => {
    auth.login.mockRejectedValue(new Error("Wrong email or password"));
    renderRoute(<Login />, { route: "/login" });
    await screen.findByRole("heading", { name: "Sign in" });

    fill("Email", "dana@example.com");
    fill("Password", "nope");
    fireEvent.submit(button(/Sign in/).closest("form")!);

    expect(await screen.findByText("Wrong email or password")).toBeTruthy();
    expect(currentLocation()).toBe("/login");
  });

  it("carries the return path to sign-up, and says when sign-ups were refused", async () => {
    renderRoute(<Login />, { route: "/login?returnTo=/orgs/a b&error=signup_refused" });

    const link = await screen.findByRole("link", { name: "Create an account" });
    expect(link.getAttribute("href")).toBe("/signup?returnTo=%2Forgs%2Fa%20b");
    expect(screen.getByRole("alert").textContent).toMatch(/not accepting sign-ups/);
  });

  it("links a bare sign-up when there is no return path, and says nothing of an unrelated error", async () => {
    renderRoute(<Login />, { route: "/login?error=state_mismatch" });

    expect((await screen.findByRole("link", { name: "Create an account" })).getAttribute("href")).toBe("/signup");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("stops a refused redirect-flow sign-up from looping back into the provider", async () => {
    auth.mode.mockResolvedValue({ ...FORM_MODE, redirectFlow: true });
    renderRoute(<Login />, { route: "/login?returnTo=/orgs/acme&error=signup_refused" });

    expect(await screen.findByRole("heading", { name: "Sign-up refused" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Sign in again" }).getAttribute("href")).toBe(
      "/auth/login?returnTo=/orgs/acme",
    );
    expect(auth.loginUrl).toHaveBeenCalledTimes(1);
  });

  it("sends a first-run deployment to sign-up, keeping the return path", async () => {
    auth.mode.mockResolvedValue({ ...FORM_MODE, firstRun: true });
    renderRoute(<Login />, { route: "/login?returnTo=/orgs/acme" });

    await waitFor(() => expect(currentLocation()).toBe("/signup?returnTo=%2Forgs%2Facme"));
    cleanup();

    renderRoute(<Login />, { route: "/login" });
    await waitFor(() => expect(currentLocation()).toBe("/signup"));
  });
});

describe("Signup", () => {
  it("creates the account once the password is long enough, then goes to the root", async () => {
    renderRoute(<Signup />, { route: "/signup" });
    await screen.findByRole("heading", { name: "Create your account" });

    fill("Email", "dana@example.com");
    fill("Password", "1234567");
    expect(button(/Create account/).disabled).toBe(true);
    fill("Password", " 1234567 ");
    fireEvent.click(button(/Create account/));

    await waitFor(() => expect(currentLocation()).toBe("/"));
    // A password is sent exactly as typed: its spaces are part of it.
    expect(auth.signup).toHaveBeenCalledWith("dana@example.com", " 1234567 ");
  });

  it("names a refused sign-up on the form", async () => {
    auth.signup.mockRejectedValue(new Error("That email is already registered"));
    renderRoute(<Signup />, { route: "/signup" });
    await screen.findByRole("heading", { name: "Create your account" });

    fill("Email", "dana@example.com");
    fill("Password", "12345678");
    fireEvent.submit(button(/Create account/).closest("form")!);

    expect(await screen.findByText("That email is already registered")).toBeTruthy();
  });

  it.each([
    ["invite", /invitation-only/],
    ["domain", /approved email domains/],
  ])("says what a %s sign-up policy lets through", async (signupPolicy, text) => {
    auth.mode.mockResolvedValue({ ...FORM_MODE, signupPolicy });
    renderRoute(<Signup />, { route: "/signup" });

    expect(await screen.findByText(text)).toBeTruthy();
  });

  it("says nothing about policy on an open instance", async () => {
    renderRoute(<Signup />, { route: "/signup" });
    await screen.findByRole("heading", { name: "Create your account" });

    expect(screen.queryByText(/invitation-only|approved email domains/)).toBeNull();
  });
});
