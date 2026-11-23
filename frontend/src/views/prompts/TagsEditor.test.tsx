// SPDX-License-Identifier: Apache-2.0
/*
 * The tags editor. The bug worth catching is a second Save while the first is still in flight, which sent two writes
 * and showed two "Tags saved" toasts.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { TagsEditor } from "./TagsEditor";

it("sends one save while a save is in flight", () => {
  const onSave = vi.fn(() => new Promise<unknown>(() => {}));
  render(<TagsEditor tags={["a"]} onSave={onSave} />);
  fireEvent.click(screen.getByRole("button", { name: "Edit tags" }));

  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  fireEvent.click(screen.getByRole("button", { name: "Save" }));

  expect(onSave).toHaveBeenCalledTimes(1);
});
