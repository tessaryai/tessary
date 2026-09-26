// SPDX-License-Identifier: Apache-2.0
/*
 * The copy affordance's contract, pinned where it is now single-sourced: a click has to confirm on
 * the control itself ("Copied"), the confirmation has to expire, and a copy that never reached the
 * clipboard must not claim it did.
 *
 * The fallback case is the one worth a test rather than a comment: `navigator.clipboard` is absent
 * on any non-secure origin, which is exactly how a self-hosted Tessary is first reached, and the
 * previous per-view code called `navigator.clipboard.writeText(...)` unguarded — a TypeError into a
 * floating promise, a button that did nothing, and no way to tell from the UI.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { CopyButton } from "./CopyButton";

function setClipboard(value: unknown) {
  Object.defineProperty(navigator, "clipboard", { value, configurable: true });
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  setClipboard(undefined);
});

describe("CopyButton", () => {
  it("restarts the Copied window on a second copy, rather than ending it on the first one's schedule", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    setClipboard({ writeText: vi.fn().mockResolvedValue(undefined) });

    render(<CopyButton value="sk-live-123" />);
    fireEvent.click(screen.getByRole("button"));
    await waitFor(() => expect(screen.getByRole("button").textContent).toContain("Copied"));
    act(() => void vi.advanceTimersByTime(1000));
    fireEvent.click(screen.getByRole("button"));
    await act(async () => {});

    act(() => void vi.advanceTimersByTime(1000));
    expect(screen.getByRole("button").textContent).toContain("Copied");
    act(() => void vi.advanceTimersByTime(700));
    expect(screen.getByRole("button").textContent).toBe("Copy");
  });

  it("resolves a thunk value at click time, not at render time", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });
    let token: string | null = null;

    const { rerender } = render(<CopyButton value={() => token} />);
    fireEvent.click(screen.getByRole("button"));
    expect(writeText).not.toHaveBeenCalled();
    expect(screen.getByRole("button").textContent).toBe("Copy");

    token = "minted-later";
    rerender(<CopyButton value={() => token} />);
    fireEvent.click(screen.getByRole("button"));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith("minted-later"));
  });

  it("falls back to execCommand when navigator.clipboard is missing (plain-http self-host)", async () => {
    setClipboard(undefined);
    const exec = vi.fn().mockReturnValue(true);
    document.execCommand = exec as unknown as typeof document.execCommand;
    const onCopied = vi.fn();

    render(<CopyButton value="http://host/v1/traces" onCopied={onCopied} />);
    fireEvent.click(screen.getByRole("button"));

    await waitFor(() => expect(screen.getByRole("button").textContent).toContain("Copied"));
    expect(exec).toHaveBeenCalledWith("copy");
    expect(onCopied).toHaveBeenCalledTimes(1);
  });

  it("does not claim success when nothing reached the clipboard", async () => {
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error("denied")) });
    document.execCommand = vi.fn().mockReturnValue(false) as unknown as typeof document.execCommand;
    const onCopied = vi.fn();
    const onCopyFailed = vi.fn();

    render(<CopyButton value="x" onCopied={onCopied} onCopyFailed={onCopyFailed} />);
    fireEvent.click(screen.getByRole("button"));

    await waitFor(() => expect(onCopyFailed).toHaveBeenCalledTimes(1));
    expect(onCopied).not.toHaveBeenCalled();
    expect(screen.getByRole("button").textContent).toBe("Copy");
  });
});
