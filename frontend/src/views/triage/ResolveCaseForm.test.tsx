// SPDX-License-Identifier: Apache-2.0
/*
 * The Resolve dialog offers the two dispositions on a frustration or groundedness case, each with its
 * one-line explanation, and sends the chosen one; every other case resolves on the reason alone.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { ResolveCaseForm, dispositionPhrase } from "./ResolveCaseForm";

afterEach(cleanup);

function renderForm(detector: string) {
  const onResolve = vi.fn();
  render(
    <ResolveCaseForm detector={detector} pending={false} error={null} onCancel={() => {}} onResolve={onResolve} />,
  );
  fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "shipped a prompt fix" } });
  return onResolve;
}

describe("ResolveCaseForm", () => {
  it("offers fixed and false alarm on a frustration case, with what each does", () => {
    renderForm("frustration");
    expect(screen.getAllByRole("radio")).toHaveLength(2);
    expect(screen.queryByText(/re-learns its normal rate from here/)).not.toBeNull();
    expect(screen.queryByText(/stop counting as frustrated and become scorable again/)).not.toBeNull();
  });

  it("sends fixed by default and false_alarm once chosen", () => {
    const onResolve = renderForm("frustration");
    fireEvent.click(screen.getByRole("button", { name: "Resolve case" }));
    expect(onResolve).toHaveBeenLastCalledWith("shipped a prompt fix", "fixed");

    fireEvent.click(screen.getByRole("radio", { name: /False alarm/ }));
    fireEvent.click(screen.getByRole("button", { name: "Resolve case" }));
    expect(onResolve).toHaveBeenLastCalledWith("shipped a prompt fix", "false_alarm");
  });

  it("offers false alarm on a groundedness case, says it clears the flags, and sends it", () => {
    const onResolve = renderForm("groundedness");
    expect(screen.getAllByRole("radio")).toHaveLength(2);
    expect(screen.queryByText("The same, and the answers this case cites are no longer flagged.")).not.toBeNull();
    expect(screen.queryByText(/stop counting as frustrated/)).toBeNull();

    fireEvent.click(screen.getByRole("radio", { name: /False alarm/ }));
    fireEvent.click(screen.getByRole("button", { name: "Resolve case" }));
    expect(onResolve).toHaveBeenLastCalledWith("shipped a prompt fix", "false_alarm");
  });

  it("offers no disposition on any other case and sends none", () => {
    const onResolve = renderForm("tool_error");
    expect(screen.queryAllByRole("radio")).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "Resolve case" }));
    expect(onResolve).toHaveBeenLastCalledWith("shipped a prompt fix", undefined);
  });

  it("names the disposition in a resolved case's closing line", () => {
    expect(dispositionPhrase("false_alarm")).toBe(" as a false alarm");
    expect(dispositionPhrase("fixed")).toBe(" as fixed");
    expect(dispositionPhrase(null)).toBe("");
  });
});
