// SPDX-License-Identifier: Apache-2.0
/*
 * The one render wrapper page tests share: a router at `route`, a fresh QueryClient with retries off
 * (a failed read is the failure, not the first of three attempts), the real toast viewport, and a
 * probe that exposes the current path and query string, so a test can assert what a control wrote.
 */
import type { ReactElement } from "react";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { ToastProvider } from "../ui";

function LocationProbe() {
  const loc = useLocation();
  return <output aria-label="location">{loc.pathname + loc.search}</output>;
}

/**
 * `parent` nests the page's route under another, as the app nests project pages under
 * `/orgs/:orgSlug/projects/:projectSlug`: a relative link such as `../cases/x` resolves against the
 * route tree, so a page that uses one has to be mounted where the app mounts it.
 */
export function renderRoute(
  ui: ReactElement,
  { route = "/", path = "*", parent }: { route?: string; path?: string; parent?: string } = {},
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const utils = render(
    <MemoryRouter initialEntries={[route]}>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
        <Routes>
          {parent ? (
            <Route path={parent}>
              <Route path={path} element={ui} />
            </Route>
          ) : (
            <Route path={path} element={ui} />
          )}
        </Routes>
        {/* Outside the routes, so it still reads the location after the page navigates off its own. */}
        <LocationProbe />
        </ToastProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
  return { ...utils, queryClient };
}

/** The current path and query string, as the router holds it. */
export function currentLocation(): string {
  return screen.getByLabelText("location").textContent ?? "";
}

/** The query string of the current location, parsed. */
export function currentParams(): URLSearchParams {
  const loc = currentLocation();
  return new URLSearchParams(loc.includes("?") ? loc.slice(loc.indexOf("?")) : "");
}

/** A promise that never settles: a read the test leaves loading on purpose. */
export const pending = <T,>(): Promise<T> => new Promise<T>(() => {});
