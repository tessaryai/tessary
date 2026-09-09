// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { Navigate, useNavigate, useSearchParams } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { auth } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";
import { ApiError } from "../../api/types";
import { Button, Field, Input, Spinner } from "../../ui";

/**
 * The frontend's own {@code /login} screen — the backend's {@code GET /auth/login} degrade
 * branch now bounces here instead of the app root, which is what breaks the redirect loop
 * ProtectedRoute used to walk an unauthenticated visitor into (app root → ProtectedRoute →
 * /auth/login → app root → …). This view itself is deliberately mounted OUTSIDE ProtectedRoute and
 * TenantProvider, in the same public tier as {@code /pricing} (see App.tsx) — it has to be reachable
 * by exactly the visitors who have nothing yet.
 *
 * Renders one of four things depending on {@link useAuth} and {@code GET /auth/mode}:
 *   1. already signed in → redirect straight past this screen (returnTo, or "/").
 *   2. the active provider drives a redirect flow (WorkOS) → bounce to {@code auth.loginUrl()}
 *      immediately, same as the old app-root behavior, rather than render a form nobody can submit.
 *   3. the deployment has no account yet ({@code firstRun}) → hand the visitor to
 *      {@code /signup}, which is the only thing they can do here.
 *   4. otherwise → the email/password form, against the endpoints already shipped.
 */
export function Login() {
  const [params] = useSearchParams();
  const returnTo = params.get("returnTo") ?? undefined;
  // GET /auth/callback lands here with the reason when the sign-up policy refused a new account.
  const refused = params.get("error") === "signup_refused";
  const nav = useNavigate();
  const { isAuthenticated, refetch } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  const mode = useQuery({ queryKey: ["auth-mode"], queryFn: auth.mode });

  const submit = useMutation({
    mutationFn: () => auth.login(email, password),
    onSuccess: async () => {
      await refetch();
      nav(returnTo || "/", { replace: true });
    },
  });

  if (isAuthenticated) {
    return <Navigate to={returnTo || "/"} replace />;
  }

  if (mode.isLoading) {
    return (
      <div className="min-h-screen bg-bg flex items-center justify-center">
        <Spinner />
      </div>
    );
  }

  if (mode.data?.redirectFlow && refused) {
    // GET /auth/callback sent the visitor here because the sign-up policy refused a new account.
    // Under a redirect-flow provider this screen would otherwise bounce straight back into
    // the provider, which returns to the same callback: a loop the visitor could never read. Show
    // the reason and let them choose to try again, after an administrator has invited them.
    return (
      <div className="min-h-screen bg-bg text-fg flex items-center justify-center px-6">
        <div className="w-full max-w-md">
          <h1 className="text-h1 text-fg">Sign-up refused</h1>
          <p className="text-body text-muted mt-2" role="alert">
            This instance is not accepting sign-ups. Ask an administrator for an invitation, then sign in again.
          </p>
          <p className="text-body mt-6">
            <a href={auth.loginUrl(returnTo)} className="text-link hover:text-link-hover">
              Sign in again
            </a>
          </p>
        </div>
      </div>
    );
  }

  if (mode.data?.redirectFlow) {
    // The active provider (WorkOS) drives its own OAuth dance -- there is no local form to render.
    // isLoading is false, so this branch always eventually redirects: it's not a race with the
    // spinner above.
    window.location.replace(auth.loginUrl(returnTo));
    return (
      <div className="min-h-screen bg-bg flex items-center justify-center">
        <Spinner />
      </div>
    );
  }

  if (mode.data?.firstRun) {
    // Nobody has an account on this deployment yet, so a sign-in form is a dead end. The
    // backend sends the common path -- an unauthenticated hit on the app root, via GET /auth/login
    // -- straight to /signup, so this covers the rest: a typed or bookmarked /login. It runs before
    // the form has ever rendered, so there is nothing for the visitor to see flicker.
    return <Navigate to={`/signup${returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : ""}`} replace />;
  }

  const error = submit.isError ? ((submit.error as ApiError).message ?? "Could not sign in") : null;

  return (
    <div className="min-h-screen bg-bg text-fg flex items-center justify-center px-6">
      <div className="w-full max-w-md">
        <header className="mb-8">
          <h1 className="text-h1 text-fg">Sign in</h1>
          <p className="text-body text-muted mt-2">
            New here? <a href={`/signup${returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : ""}`} className="text-link hover:text-link-hover">Create an account</a>.
          </p>
          {refused && (
            <p className="text-small text-error mt-3" role="alert">
              This instance is not accepting sign-ups. Ask an administrator for an invitation, then sign in again.
            </p>
          )}
        </header>

        <form
          className="space-y-5"
          onSubmit={(e) => {
            e.preventDefault();
            submit.mutate();
          }}
        >
          <Field label="Email" error={error} required>
            {(p) => (
              <Input
                {...p}
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@example.com"
                autoFocus
                autoComplete="email"
              />
            )}
          </Field>

          <Field label="Password" required>
            {(p) => (
              <Input
                {...p}
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
              />
            )}
          </Field>

          <Button
            type="submit"
            variant="primary"
            className="w-full"
            disabled={!email.trim() || !password}
            loading={submit.isPending}
          >
            {submit.isPending ? "Signing in…" : "Sign in"}
          </Button>
        </form>
      </div>
    </div>
  );
}
