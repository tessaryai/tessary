// SPDX-License-Identifier: Apache-2.0
/*
 * The version column. The bugs worth catching are the wrong version marked selected, a click that selects another,
 * and a Compare that opens with nothing to compare against no visible reason.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { promptDetail } from "../../test/fixtures";
import { VersionList } from "./VersionList";

const VERSIONS = promptDetail().versions;

it("marks the selected version, selects on click, and shows each version's labels and commit", () => {
  const onSelect = vi.fn();
  const onCompare = vi.fn();
  render(<VersionList versions={VERSIONS} selected={2} onSelect={onSelect} onCompare={onCompare} />);

  expect(screen.getByRole("button", { name: "Version 2" }).getAttribute("aria-pressed")).toBe("true");
  expect(screen.getByRole("button", { name: "Version 1" }).getAttribute("aria-pressed")).toBe("false");
  expect(screen.getByText("production")).toBeTruthy();
  expect(screen.getByText("Tighten tone")).toBeTruthy();
  expect(screen.getByText("No commit message")).toBeTruthy();
  // Bug: each row named its author by raw principal id.
  expect(screen.getByText(/^Ana Lima · /)).toBeTruthy();
  expect(screen.getByText(/^ci@example\.com · /)).toBeTruthy();
  expect(screen.queryByText(/01M57WWYFWWDDFP8ZKS2MZKW7/)).toBeNull();

  fireEvent.click(screen.getByRole("button", { name: "Version 1" }));
  expect(onSelect).toHaveBeenCalledWith(1);
  fireEvent.click(screen.getByRole("button", { name: "Compare" }));
  expect(onCompare).toHaveBeenCalled();
  expect(screen.queryByText("Add a second version to compare.")).toBeNull();
});

it("disables Compare with a visible reason when there is one version", () => {
  render(<VersionList versions={VERSIONS.slice(1)} selected={1} onSelect={vi.fn()} onCompare={vi.fn()} />);

  expect((screen.getByRole("button", { name: "Compare" }) as HTMLButtonElement).disabled).toBe(true);
  expect(screen.getByText("Add a second version to compare.")).toBeTruthy();
});
