// SPDX-License-Identifier: Apache-2.0
/*
 * A tiny imperative registry for shell-level affordances that the command palette wants to
 * trigger but does not own — namely the project switcher, which lives in the Sidebar. The
 * Sidebar registers its opener on mount; the palette calls it. Both render inside ShellChrome's
 * provider.
 */
import { createContext, useContext, useMemo, useRef } from "react";
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

/** Read shell actions. Only called under ShellChrome, which mounts the provider. */
export function useShellActions(): ShellActionsApi {
  return useContext(ShellActionsCtx)!;
}
