// SPDX-License-Identifier: Apache-2.0
import { Navigate } from "react-router-dom";
import { auth } from "../../api/client";
import { Button, Field, Input } from "../../ui";
import { ScreenSpinner, useCredentials } from "./credentials";

/**
 * Screen 1 of first run. The backend mints the org and default project on signup, so RootRedirect
 * lands on it; step 2 is ConnectGate.
 */
export function Signup() {
  const { returnTo, isAuthenticated, mode, email, setEmail, password, setPassword, submit, error } = useCredentials(
    (e, p) => auth.signup(e, p),
  );

  if (isAuthenticated) return <Navigate to={returnTo || "/"} replace />;

  if (mode.isLoading) return <ScreenSpinner />;

  if (mode.data?.redirectFlow) {
    window.location.replace(auth.loginUrl(returnTo));
    return <ScreenSpinner />;
  }

  return (
    <div className="min-h-screen bg-bg text-fg flex items-center justify-center p-10">
      <div className="w-full max-w-[380px]">
        <div className="flex items-center gap-2.5 mb-11">
          <img src="/tessary-logo.png" alt="" className="size-[18px] shrink-0 rounded-control" />
          <span className="text-[15px] font-semibold tracking-[-0.01em]">tessary</span>
        </div>

        <div className="text-label uppercase text-muted mb-2.5">Step 1 of 2</div>
        <h1 className="text-[28px] leading-[1.3] font-semibold tracking-[-0.01em]">Create your account</h1>
        {mode.data?.signupPolicy === "invite" && (
          <p className="text-small text-muted mt-3">
            This instance is invitation-only. Sign up with the email address an administrator invited.
          </p>
        )}
        {mode.data?.signupPolicy === "domain" && (
          <p className="text-small text-muted mt-3">
            This instance only accepts sign-ups from approved email domains, or an invited address.
          </p>
        )}

        <form
          className="space-y-4 mt-8"
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
                placeholder="you@company.com"
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
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="At least 8 characters"
                autoComplete="new-password"
              />
            )}
          </Field>

          <Button
            type="submit"
            variant="primary"
            className="w-full mt-1"
            disabled={!email.trim() || password.length < 8}
            loading={submit.isPending}
          >
            {submit.isPending ? "Creating account…" : "Create account"}
          </Button>
        </form>
      </div>
    </div>
  );
}
