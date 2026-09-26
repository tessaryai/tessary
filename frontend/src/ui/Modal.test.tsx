// SPDX-License-Identifier: Apache-2.0
/*
 * The modal. The bug worth catching: a click inside the dialog's content closing it, when only a click
 * on the backdrop around it should.
 */
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Modal } from "./Modal";

afterEach(cleanup);

describe("Modal", () => {
  it("closes on a backdrop click, and not on a click inside", () => {
    const onClose = vi.fn();
    render(
      <Modal open onClose={onClose} title="Confirm">
        <p>body text</p>
      </Modal>,
    );

    fireEvent.click(screen.getByText("body text"));
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("dialog"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("closes through its own handler on Escape, rather than the browser closing it behind React's back", () => {
    const onClose = vi.fn();
    render(
      <Modal open onClose={onClose} title="Confirm">
        body
      </Modal>,
    );
    const cancel = new Event("cancel", { cancelable: true });

    screen.getByRole("dialog").dispatchEvent(cancel);

    expect(cancel.defaultPrevented).toBe(true);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("closes the native dialog when the prop turns false", () => {
    const { rerender } = render(
      <Modal open onClose={() => {}} title="Confirm">
        body
      </Modal>,
    );
    rerender(
      <Modal open={false} onClose={() => {}} title="Confirm">
        body
      </Modal>,
    );

    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
