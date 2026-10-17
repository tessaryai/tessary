// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { auth } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";
import type { ApiError } from "../../api/types";
import { Spinner } from "../../ui";

/** What Login and Signup share: the return path, the auth mode, the two fields, and a submit that lands on the return path. */
export function useCredentials(send: (email: string, password: string) => Promise<unknown>) {
  const [params] = useSearchParams();
  const returnTo = params.get("returnTo") ?? undefined;
  const nav = useNavigate();
  const { isAuthenticated, refetch } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const mode = useQuery({ queryKey: ["auth-mode"], queryFn: auth.mode });
  const submit = useMutation({
    mutationFn: () => send(email, password),
    onSuccess: async () => {
      await refetch();
      nav(returnTo || "/", { replace: true });
    },
  });
  const error = submit.isError ? (submit.error as ApiError).message : null;
  return { params, returnTo, isAuthenticated, mode, email, setEmail, password, setPassword, submit, error };
}

export function ScreenSpinner() {
  return (
    <div className="min-h-screen bg-bg flex items-center justify-center">
      <Spinner />
    </div>
  );
}
