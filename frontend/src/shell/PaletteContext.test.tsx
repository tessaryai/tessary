// SPDX-License-Identifier: Apache-2.0
/*
 * The palette's open state. The bugs worth catching: "/" opening the palette while someone is typing,
 * a programmatic open that does nothing, and a component outside the provider failing silently.
 */
import { act, cleanup, fireEvent, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { PaletteProvider, usePalette } from "./PaletteContext";

afterEach(cleanup);

const wrapper = ({ children }: { children: ReactNode }) => <PaletteProvider>{children}</PaletteProvider>;

describe("PaletteProvider", () => {
  it("opens and closes on request", () => {
    const { result } = renderHook(() => usePalette(), { wrapper });

    act(() => result.current.open());
    expect(result.current.isOpen).toBe(true);
    act(() => result.current.close());
    expect(result.current.isOpen).toBe(false);
  });

  it("opens on / from the page, but not from a field being typed in", () => {
    const { result } = renderHook(() => usePalette(), { wrapper });
    const field = document.createElement("textarea");
    document.body.appendChild(field);

    fireEvent.keyDown(field, { key: "/" });
    expect(result.current.isOpen).toBe(false);
    fireEvent.keyDown(document.body, { key: "/" });
    expect(result.current.isOpen).toBe(true);
    field.remove();
  });

  it("refuses to run outside its provider", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});

    expect(() => renderHook(() => usePalette())).toThrow("usePalette must be used within <PaletteProvider>");
  });
});
