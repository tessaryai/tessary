// SPDX-License-Identifier: Apache-2.0
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { FALLBACK_W, useWidth } from "./useWidth";

function Probe() {
  const [ref, width] = useWidth();
  return (
    <div ref={ref}>
      <output aria-label="width">{width}</output>
    </div>
  );
}

describe("useWidth", () => {
  // Catches a zero-width SVG in jsdom or before layout, where the box reports a clientWidth of 0.
  it("reportsTheFallbackWhileTheBoxHasNoWidth", () => {
    render(<Probe />);

    expect(FALLBACK_W).toBe(520);
    expect(screen.getByLabelText("width").textContent).toBe("520");
  });

  // Catches a chart that keeps the fallback width after the box has been laid out.
  it("followsTheBoxOnceItHasAWidth", () => {
    vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(640);

    render(<Probe />);

    expect(screen.getByLabelText("width").textContent).toBe("640");
  });
});
