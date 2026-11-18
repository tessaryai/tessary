// SPDX-License-Identifier: Apache-2.0
/*
 * The read-only body. The bugs worth catching are a variable drawn as plain text, a placeholder drawn as an empty
 * message, a message shown without its role, and an empty body drawn as a blank box.
 */
import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { PromptBodyView } from "./PromptBodyView";

it("marks every variable of a text body", () => {
  render(<PromptBodyView type="text" body={"Hi {{name}}, from {{ team }}."} />);

  expect(screen.getByText("{{name}}").getAttribute("data-variable")).toBe("name");
  expect(screen.getByText("{{ team }}").getAttribute("data-variable")).toBe("team");
});

it("draws each chat message under its role and a placeholder as a badge", () => {
  render(
    <PromptBodyView
      type="chat"
      body={[
        { role: "system", content: "You are {{tone}}." },
        { type: "placeholder", name: "history" },
      ]}
    />,
  );

  expect(screen.getByText("system")).toBeTruthy();
  expect(screen.getByText("{{tone}}").getAttribute("data-variable")).toBe("tone");
  expect(screen.getByText("placeholder: history")).toBeTruthy();
});

it("says when a text body is empty", () => {
  render(<PromptBodyView type="text" body="" />);

  expect(screen.getByText("This version has an empty body.")).toBeTruthy();
});
