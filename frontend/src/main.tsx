// SPDX-License-Identifier: Apache-2.0
import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import "@fontsource-variable/geist";
import "@fontsource-variable/geist-mono";
import "@fontsource-variable/space-grotesk";
import App from "./App";
import { ThemeProvider } from "./ui/ThemeContext";
import { DensityProvider } from "./ui/density";
import { ChartPaletteProvider } from "./ui/chartPalette";
import { prefetchProjectShell } from "./shell/shellQueries";
import { preloadRouteChunk } from "./lib/routePreload";
import "./index.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, refetchOnWindowFocus: false },
  },
});

/*
 * Before the first render, so neither waits on the sign-in check: `ProtectedRoute` renders nothing until
 * `/auth/me` answers, and neither the current route's chunk nor the shell's reads depend on who is asking.
 * The server authorises each read on its own; a signed-out visitor gets 401s and is sent to sign in.
 */
preloadRouteChunk(window.location.pathname);
prefetchProjectShell(queryClient, window.location.pathname);

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <DensityProvider className="contents">
          <ChartPaletteProvider>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </ChartPaletteProvider>
        </DensityProvider>
      </ThemeProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
