// SPDX-License-Identifier: Apache-2.0
/*
 * The concept popover: a term's gloss in place, and the way to the full list. The bugs worth catching:
 * a popover that cannot be closed again, and a "See all concepts" link to another project or concept.
 */
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CONCEPTS } from "../concepts";
import { currentLocation, renderRoute } from "../test/render";
import { ConceptPopover } from "./ConceptPopover";

vi.mock("../tenant/TenantContext", () => ({ useTenant: () => ({ orgSlug: "acme", projectSlug: "default" }) }));

afterEach(cleanup);

describe("ConceptPopover", () => {
  it("names the term by default, glosses it on demand, and closes on a second press", () => {
    renderRoute(<ConceptPopover concept="pipeline" />);
    const help = screen.getByRole("button", { name: `What is a ${CONCEPTS.pipeline.label}?` });
    expect(screen.getByText(CONCEPTS.pipeline.label)).toBeTruthy();

    fireEvent.click(help);
    expect(screen.getByRole("dialog").textContent).toContain(CONCEPTS.pipeline.gloss);
    expect(help.getAttribute("aria-expanded")).toBe("true");

    fireEvent.click(help);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("links to this concept on this project's list, and closes as it goes", () => {
    renderRoute(<ConceptPopover concept="pipeline">evals plugin</ConceptPopover>);
    expect(screen.getByText("evals plugin")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /What is a/ }));
    fireEvent.click(screen.getByRole("link", { name: "See all concepts" }));

    expect(currentLocation()).toBe("/orgs/acme/projects/default/concepts");
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
