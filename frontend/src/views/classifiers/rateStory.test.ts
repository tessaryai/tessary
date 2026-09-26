// SPDX-License-Identifier: Apache-2.0
/*
 * A rate read as calls in every hundred. The bug worth catching: a rate under one in a hundred rounding
 * to "0 in every hundred", which reads as nothing failing at all.
 */
import { describe, expect, it } from "vitest";
import { perHundred } from "./rateStory";

describe("perHundred", () => {
  it("says fewer than one for a rate under one in a hundred, in the noun given", () => {
    expect(perHundred(0.004)).toBe("fewer than one call in every hundred");
    expect(perHundred(0.004, "output")).toBe("fewer than one output in every hundred");
  });
});
