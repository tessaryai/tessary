// SPDX-License-Identifier: Apache-2.0
// vitest setupFiles entry. Stubs browser globals jsdom lacks that views call on mount: IntersectionObserver
// (TracesIndex), ResizeObserver (detail-chat), and Element.scrollIntoView (CommandPalette, under every tenant route).
// matchMedia has no users, so it is not stubbed. Node also defines its own globalThis localStorage, which without
// --localstorage-file silently returns undefined, and jsdom's assignment loses to it. DensityProvider reads
// localStorage on first render, so both storages get an in-memory polyfill.

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

/** A minimal in-memory Storage, enough for getItem, setItem, and removeItem. */
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

// jsdom has <dialog> but not its modal methods; defined only where missing.
HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
  this.setAttribute("open", "");
};
HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
  this.removeAttribute("open");
};
