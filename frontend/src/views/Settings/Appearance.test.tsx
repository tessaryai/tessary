// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { DensityProvider } from "../../ui/density";
import { AppearanceControls } from "./Appearance";

// setup.ts does not clear storage, so a pick would leak into the next test.
afterEach(() => window.localStorage.clear());

describe("AppearanceControls", () => {
  // Catches a density pick that does not reach storage, or a chart palette left behind in Settings.
  it("offersDensityAndNoChartPalette", () => {
    render(
      <DensityProvider>
        <AppearanceControls />
      </DensityProvider>,
    );

    const density = screen.getByRole("radiogroup", { name: "Density" });
    expect(within(density).getAllByRole("radio").map((r) => r.textContent)).toEqual(["Comfortable", "Compact"]);
    expect(screen.getByText("Row spacing across every data view.")).toBeTruthy();
    expect(screen.queryByRole("radiogroup", { name: "Chart palette" })).toBeNull();

    fireEvent.click(within(density).getByRole("radio", { name: "Compact" }));

    expect(within(density).getByRole("radio", { name: "Compact" }).getAttribute("aria-checked")).toBe("true");
    expect(window.localStorage.getItem("tessary.prefs.density")).toBe("compact");
  });
});
