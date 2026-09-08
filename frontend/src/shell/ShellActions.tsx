// SPDX-License-Identifier: Apache-2.0
/*
 * A tiny imperative registry for shell-level affordances that the command palette wants to
 * trigger but does not own — namely the project switcher, which lives in the Sidebar. The
 * Sidebar registers its opener on mount; the palette calls it. Keeping this as a separate,
 * optional context means the palette degrades gracefully (the action is simply a no-op) if a
 * host ever renders it without a sidebar.
 */
import { createContext, useCallback, useContext, useMemo, useRef } from "react";
import type { ReactNode } from "react";

type Opener = () => void;

type ShellActionsApi = {
  registerProjectSwitcher: (open: Opener) => void;
  openProjectSwitcher: () => void;
};

const ShellActionsCtx = createContext<ShellActionsApi | null>(null);

export function ShellActionsProvider({ children }: { children: ReactNode }) {
  const projectOpener = useRef<Opener | null>(null);

  const api = useMemo<ShellActionsApi>(
    () => ({
      registerProjectSwitcher: (open) => {
        projectOpener.current = open;
      },
      openProjectSwitcher: () => projectOpener.current?.(),
    }),
    [],
  );

  return <ShellActionsCtx.Provider value={api}>{children}</ShellActionsCtx.Provider>;
}

/** Read shell actions; returns no-ops if used outside a provider. */
export function useShellActions(): ShellActionsApi {
  const ctx = useContext(ShellActionsCtx);
  const fallback = useShellActionsFallback();
  return ctx ?? fallback;
}

function useShellActionsFallback(): ShellActionsApi {
  const noop = useCallback(() => {}, []);
  return useMemo<ShellActionsApi>(
    () => ({
      registerProjectSwitcher: noop,
      openProjectSwitcher: noop,
    }),
    [noop],
  );
}
