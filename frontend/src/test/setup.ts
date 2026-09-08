// SPDX-License-Identifier: Apache-2.0
// vitest setupFiles entry (wired from vite.config.ts's `test` block). Runs once per test file,
// before any test in it, and stubs the browser globals jsdom does not implement that real views
// under the smoke test's routes reach for unconditionally on mount:
//
//   - window.IntersectionObserver — TracesIndex.tsx (infinite-scroll paging)
//   - window.ResizeObserver       — detail-chat.tsx (mounted under CasePage/TraceDetail's chat panel)
//   - Element.prototype.scrollIntoView — CommandPalette.tsx, mounted under every tenant route via
//     ShellChrome, so this one is hit by nearly the whole route-manifest smoke test, not just one view.
//
// window.matchMedia is deliberately NOT stubbed here — verified zero uses anywhere in frontend/src
// (see #890's plan). Adding an unused stub would just be one more thing to keep in sync with a
// property nothing reads.
//
// Without these three, any view that calls them during its initial render throws
// "<X> is not a function" in jsdom, which is a false failure of THIS harness, not of the view —
// exactly the noise #890's console.error allowlist (in routeManifest.smoke.test.tsx) exists to
// keep separate from a real regression.
//
// ALSO A GOTCHA, discovered running this suite: Node (stable as of the Node 25 on this machine)
// ships its OWN global `localStorage`/`sessionStorage` — Web Storage backed by an on-disk file via
// `--localstorage-file`, which nothing here passes. jsdom's environment sets `window.localStorage`
// to ITS OWN in-memory implementation, but Node's version is defined on `globalThis` first with a
// getter, and jsdom's assignment loses that race — `window.localStorage.getItem` then silently
// resolves to `undefined` (not a thrown error) because Node's storage getter refuses to work
// without a file. `DensityProvider`'s `persist` prop (density.tsx, mounted for nearly every route)
// reads `localStorage` on its very first render, so every route smoke-tested here hit this.
// Overriding both with a plain in-memory polyfill sidesteps the collision entirely.

class StubObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
  takeRecords(): unknown[] {
    return [];
  }
}

// @ts-expect-error — jsdom's lib.dom.d.ts still declares the real IntersectionObserver signature;
// this stub only needs to satisfy call sites, not the constructor's full type.
window.IntersectionObserver = StubObserver;
// No @ts-expect-error needed here: lib.dom.d.ts's ResizeObserver constructor type is already
// loose enough for StubObserver to satisfy structurally (unlike IntersectionObserver above).
window.ResizeObserver = StubObserver;

Element.prototype.scrollIntoView = () => {};

/** A minimal in-memory Storage — enough for every current caller (`getItem`/`setItem`/`removeItem`),
 * not a full Storage polyfill. See the header note on why Node's own global loses to this. */
function inMemoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => {
      data.set(key, value);
    },
    removeItem: (key: string) => {
      data.delete(key);
    },
    clear: () => {
      data.clear();
    },
    key: (index: number) => Array.from(data.keys())[index] ?? null,
    get length() {
      return data.size;
    },
  } as Storage;
}

Object.defineProperty(window, "localStorage", { value: inMemoryStorage(), configurable: true });
Object.defineProperty(window, "sessionStorage", { value: inMemoryStorage(), configurable: true });
