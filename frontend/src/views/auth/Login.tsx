// SPDX-License-Identifier: Apache-2.0
import { Navigate } from "react-router-dom";
import { auth } from "../../api/client";
import { Button, Field, Input } from "../../ui";
import { ScreenSpinner, useCredentials } from "./credentials";

/**
 * Outside ProtectedRoute and TenantProvider: the backend's GET /auth/login bounces unauthenticated
 * visitors here. Signed in redirects past; a redirect-flow provider bounces to `auth.loginUrl()`;
 * `firstRun` goes to /signup; otherwise the email/password form.
 */
export function Login() {
  const { params, returnTo, isAuthenticated, mode, email, setEmail, password, setPassword, submit, error } = useCredentials(
    (e, p) => auth.login(e, p),
  );
  const refused = params.get("error") === "signup_refused";

  if (isAuthenticated) return <Navigate to={returnTo || "/"} replace />;

  if (mode.isLoading) return <ScreenSpinner />;

  if (mode.data?.redirectFlow && refused) {
    // Under a redirect-flow provider this would bounce back into the provider and the same callback, a loop
    // the visitor could never read.
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
    window.location.replace(auth.loginUrl(returnTo));
    return <ScreenSpinner />;
  }

  if (mode.data?.firstRun) {
    // Covers a typed or bookmarked /login; the backend already sends the app root to /signup.
    return <Navigate to={`/signup${returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : ""}`} replace />;
  }

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
