// SPDX-License-Identifier: Apache-2.0
/*
 * Toasts. The bugs worth catching: a toast that never leaves, a dismiss that takes the wrong one with
 * it, and a component rendered outside the provider failing silently rather than loudly.
 */
import { act, cleanup, fireEvent, render, renderHook, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider, useToast } from "./Toast";

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

function Pusher() {
  const toast = useToast();
  return (
    <>
      <button type="button" onClick={() => toast.success("Saved", "All good")}>
        ok
      </button>
      <button type="button" onClick={() => toast.error("Failed")}>
        fail
      </button>
    </>
  );
}

describe("toasts", () => {
  it("shows each toast, dismisses the one closed, and lets the rest leave on their own", () => {
    render(
      <ToastProvider>
        <Pusher />
      </ToastProvider>,
    );
    act(() => vi.advanceTimersByTime(0));

    fireEvent.click(screen.getByRole("button", { name: "ok" }));
    act(() => vi.advanceTimersByTime(1000));
    fireEvent.click(screen.getByRole("button", { name: "fail" }));
    expect(screen.getAllByRole("status").map((t) => t.textContent)).toEqual(["SavedAll good", "Failed"]);

    fireEvent.click(screen.getAllByRole("button", { name: "Dismiss" })[0]);
    expect(screen.getAllByRole("status").map((t) => t.textContent)).toEqual(["Failed"]);

    act(() => vi.advanceTimersByTime(4999));
    expect(screen.getByText("Failed")).toBeTruthy();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("refuses to run outside its provider", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});

    expect(() => renderHook(() => useToast())).toThrow("useToast must be used inside <ToastProvider>");
  });
});
