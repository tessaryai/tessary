// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { Navigate, useNavigate, useSearchParams } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { auth } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";
import { ApiError } from "../../api/types";
import { Button, Field, Input, Spinner } from "../../ui";

/**
 * The frontend's own {@code /signup} screen — mirrors {@link Login}'s auth plumbing exactly
 * (see that file's header for the shared reasoning on why this sits outside ProtectedRoute/
 * TenantProvider and polls {@code GET /auth/mode}). On success, {@code TenantService#ensureDefaultOrg}
 * (backend, runs on every signup) has already minted the org and its default project, so {@code
 * RootRedirect} lands the fresh account straight on it — there is no org-creation step here any more.
 *
 * <p>Chrome is Screen 1 of the two-step first-run flow: a wordmark, "Step 1
 * of 2", and "Create your account". Step 2 is the connect
 * gate ({@code ConnectGate.tsx}), which the newly-created default project renders in place of the
 * shell until a tagged span arrives.
 */
export function Signup() {
  const [params] = useSearchParams();
  const returnTo = params.get("returnTo") ?? undefined;
  const nav = useNavigate();
  const { isAuthenticated, refetch } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  const mode = useQuery({ queryKey: ["auth-mode"], queryFn: auth.mode });

  const submit = useMutation({
    mutationFn: () => auth.signup(email, password),
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

  if (mode.data?.redirectFlow) {
    // Same reasoning as Login: a redirect-flow provider (WorkOS) has no local form to render.
    window.location.replace(auth.loginUrl(returnTo));
    return (
      <div className="min-h-screen bg-bg flex items-center justify-center">
        <Spinner />
      </div>
    );
  }

  const error = submit.isError
    ? ((submit.error as ApiError).message ?? "Could not create account")
    : null;

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
