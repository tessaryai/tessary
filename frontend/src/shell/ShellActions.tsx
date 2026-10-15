// SPDX-License-Identifier: Apache-2.0
// Lets the palette trigger shell affordances it does not own, such as the Sidebar's project switcher.
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

export function useShellActions(): ShellActionsApi {
  return useContext(ShellActionsCtx)!;
}
