// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from "vitest";
import { brief, clock, didSteps, elapsed, stepMeta, stopFix, stopReason } from "./runWords";

describe("run words", () => {
  // Bug: the time since a run was queued, or a step's start, reads 0:6 or 6s where the bar shows 0:06.
  it.each([
    [0, "0:00"],
    [6_000, "0:06"],
    [160_999, "2:40"],
    [3_723_000, "1:02:03"],
    [-5_000, "0:00"],
  ])("clock(%i) is %s", (ms, words) => {
    expect(clock(ms)).toBe(words);
  });

  // Bug: the elapsed time drops the padded seconds the design shows (3m 2s), or a clock skew prints a negative time.
  it.each([
    [14_000, "14s"],
    [134_000, "2m 14s"],
    [182_000, "3m 02s"],
    [400_000, "6m 40s"],
    [3_900_000, "1h 05m"],
    [-1_000, "0s"],
  ])("elapsed(%i) is %s", (ms, words) => {
    expect(elapsed(ms)).toBe(words);
  });

  // Bug: the fold says "1 steps", or a short run reads "in 0m".
  it.each([
    [12, 250_000, "Did 12 steps in 4m"],
    [23, 540_000, "Did 23 steps in 9m"],
    [1, 40_000, "Did 1 step in 40s"],
    [30, 3_900_000, "Did 30 steps in 1h 5m"],
  ])("didSteps(%i, %i) is %s", (n, ms, words) => {
    expect(didSteps(n, ms)).toBe(words);
  });

  // Bug: the case mark's hover shows seconds-precise times where a glance needs minutes.
  it.each([
    [40_000, "40s"],
    [190_000, "3m"],
    [7_500_000, "2h"],
  ])("brief(%i) is %s", (ms, words) => {
    expect(brief(ms)).toBe(words);
  });

  // Bug: the running bar's meta loses the step number or the elapsed time.
  it("stepMeta puts the step before the elapsed time", () => {
    expect(stepMeta(7, 134_000)).toBe("step 7 · 2m 14s");
  });

  const providers = { label: "Open Providers", to: "/orgs/acme/projects/default/settings/providers" };
  const models = { label: "Open Models", to: "/orgs/acme/projects/default/settings/models" };

  // Bug: a stopped run links to a settings page that does not fix its cause, or to Providers for a person who
  // cannot open it.
  it.each([
    ["CLASSIFIER.TRIAGE_PROVIDER_REJECTED", true, providers],
    ["RCA.PROVIDER_REJECTED", true, providers],
    ["MODEL_CONFIG.MISSING_CREDENTIALS", true, providers],
    ["MODEL_CONFIG.AGENTIC_IAM_ROLE_UNSUPPORTED", true, providers],
    ["CLASSIFIER.TRIAGE_PROVIDER_REJECTED", false, undefined],
    ["MODEL_CONFIG.MISSING_CREDENTIALS", false, undefined],
    ["CLASSIFIER.TRIAGE_REQUEST_REFUSED", false, models],
    ["RCA.REQUEST_REFUSED", true, models],
    ["RCA.TIMED_OUT", true, undefined],
    ["CLASSIFIER.TRIAGE_LAUNCHER_MISCONFIGURED", true, undefined],
    [null, true, undefined],
  ])("stopFix(%s, canProviders %s)", (code, canProviders, fix) => {
    expect(stopFix(code, "/orgs/acme/projects/default", canProviders)).toEqual(fix);
  });

  // Bug: a stopped run's bar names the wrong cause, or tells an RCA reader to run triage again.
  it.each([
    ["CLASSIFIER.TRIAGE_PROVIDER_REJECTED", "triage", "the provider rejected the stored key. Fix the key, then run triage again."],
    ["RCA.PROVIDER_REJECTED", "RCA", "the provider rejected the stored key. Fix the key, then run RCA again."],
    [
      "CLASSIFIER.TRIAGE_REQUEST_REFUSED",
      "triage",
      "the provider refused the request. Usually the model is not one the provider serves. Check the model, then run triage again.",
    ],
    ["MODEL_CONFIG.MISSING_CREDENTIALS", "RCA", "there is no key for the provider RCA runs on. Add one, then run RCA again."],
    [
      "MODEL_CONFIG.AGENTIC_IAM_ROLE_UNSUPPORTED",
      "triage",
      "triage cannot use a key set to an IAM role. Set it to an access key, then run triage again.",
    ],
    [
      "RCA.LAUNCHER_MISCONFIGURED",
      "RCA",
      "the sandbox launcher refused the run. Its key or its version does not match this deployment. Fix the launcher, then run RCA again.",
    ],
    ["RCA.LAUNCHER_UNREACHABLE", "RCA", "the sandbox launcher did not answer. Check that it runs, then run RCA again."],
    ["RCA.PROVIDER_UNAVAILABLE", "RCA", "the provider did not answer. Run RCA again once it is back."],
    ["RCA.TIMED_OUT", "RCA", "the run did not finish in its time limit. Run RCA again."],
    ["RCA.UPSTREAM_FAILED", "RCA", "the agent ended without an answer. Run RCA again."],
    ["RCA.SOMETHING_NEW", "RCA", "the run hit a setting it cannot use. Fix it, then run RCA again."],
    [null, "triage", "the run hit a setting it cannot use. Fix it, then run triage again."],
  ] as const)("stopReason(%s, %s)", (code, lane, words) => {
    expect(stopReason(code, lane)).toBe(words);
  });
});
