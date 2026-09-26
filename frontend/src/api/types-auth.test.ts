// SPDX-License-Identifier: Apache-2.0
/*
 * Which project is the sample. The bug worth catching: a malformed settings blob throwing, or reading as
 * the sample, where it should read as an ordinary project.
 */
import { describe, expect, it } from "vitest";
import { isSampleProject } from "./types-auth";

describe("isSampleProject", () => {
  it.each([
    ['{"sample": true}', true],
    ['{"sample": "yes"}', false],
    ["{}", false],
    [null, false],
    ["{not json", false],
  ])("reads settings %s as sample: %s", (settings, sample) => {
    expect(isSampleProject({ settings })).toBe(sample);
  });
});
