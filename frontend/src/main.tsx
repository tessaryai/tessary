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
import "./index.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, refetchOnWindowFocus: false },
  },
});

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <DensityProvider persist className="contents">
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </DensityProvider>
      </ThemeProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
