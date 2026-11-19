// SPDX-License-Identifier: Apache-2.0
/*
 * The version column. The bugs worth catching are the wrong version marked selected, a click that selects another,
 * a count that claims every version when only the first page is loaded, and no way to the older versions.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { promptDetail } from "../../test/fixtures";
import { VersionList } from "./VersionList";

const VERSIONS = promptDetail().versions;

it("marks the selected version, selects on click, and shows each version's labels and commit", () => {
  const onSelect = vi.fn();
  render(<VersionList versions={VERSIONS} selected={2} onSelect={onSelect} hasOlder={false} onShowOlder={vi.fn()} loadingOlder={false} />);

  expect(screen.getByRole("button", { name: "Version 2" }).getAttribute("aria-pressed")).toBe("true");
  expect(screen.getByRole("button", { name: "Version 1" }).getAttribute("aria-pressed")).toBe("false");
  expect(screen.getByText("production")).toBeTruthy();
  expect(screen.getByText("Tighten tone")).toBeTruthy();
  expect(screen.getByText("No commit message")).toBeTruthy();
  // Bug: each row named its author by raw principal id.
  expect(screen.getByText(/^Ana Lima · /)).toBeTruthy();
  expect(screen.getByText(/^ci@example\.com · /)).toBeTruthy();
  expect(screen.queryByText(/01M57WWYFWWDDFP8ZKS2MZKW7/)).toBeNull();
  expect(screen.getByText("2 versions")).toBeTruthy();
  expect(screen.getByText("Newest first")).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Show older versions" })).toBeNull();

  fireEvent.click(screen.getByRole("button", { name: "Version 1" }));
  expect(onSelect).toHaveBeenCalledWith(1);
});

it("does not claim a total while older versions are unread, and asks for them", () => {
  const onShowOlder = vi.fn();
  render(<VersionList versions={VERSIONS} selected={2} onSelect={vi.fn()} hasOlder onShowOlder={onShowOlder} loadingOlder={false} />);

  expect(screen.getByText("Newest 2 versions")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Show older versions" }));
  expect(onShowOlder).toHaveBeenCalled();
});
