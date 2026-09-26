// SPDX-License-Identifier: Apache-2.0
/*
 * The empty queue's copy-endpoint action. The bugs worth catching: an endpoint other than this
 * deployment's put on the clipboard, and a refused copy that says nothing (the reader then pastes
 * whatever was on their clipboard before).
 */
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderRoute } from "../../test/render";
import type { EmptyState } from "./emptyState";
import { PipelineEmpty } from "./PipelineEmpty";

const node: EmptyState["nodes"][number] = { label: "Traces", value: "0", sub: "", tone: "satisfied" };
const STATE: EmptyState = {
  key: "no-classifiers",
  title: "No traces yet",
  body: "Point an exporter here.",
  primary: { kind: "link", label: "View traces", to: "/traces" },
  secondary: { kind: "copyEndpoint", label: "Copy endpoint" },
  nodes: [node, node, node],
};
const ENDPOINT = `${window.location.origin}/v1/traces`;

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("PipelineEmpty", () => {
  it("says when the endpoint could not be copied, and shows it to copy by hand", async () => {
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: vi.fn().mockRejectedValue(new Error("denied")) },
      configurable: true,
    });
    renderRoute(<PipelineEmpty state={STATE} />);

    fireEvent.click(screen.getByRole("button", { name: "Copy endpoint" }));

    expect(await screen.findByText("Could not copy")).toBeTruthy();
    expect(screen.getByText(ENDPOINT)).toBeTruthy();
  });
});
