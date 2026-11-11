// SPDX-License-Identifier: Apache-2.0
/*
 * One invalidation covers every prompt write. The bugs worth catching are a write that leaves the list or the open
 * version stale, and one that names the prompt in the wrong key position, so the detail it just changed stays cached.
 */
import { QueryClient } from "@tanstack/react-query";
import { expect, it } from "vitest";
import { promptReadsStale, seedPromptReads } from "../../test/promptReads";
import { invalidatePromptReads } from "./promptReads";

const BASE = "/api/orgs/acme/projects/default";

it("marks the list, the prompt and its versions stale after a write to that prompt, and no other prompt", () => {
  const qc = new QueryClient();
  seedPromptReads(qc);
  invalidatePromptReads(qc, BASE, "p-2");
  expect(promptReadsStale(qc)).toEqual([true, false, false]);

  invalidatePromptReads(qc, BASE, "p-1");
  expect(promptReadsStale(qc)).toEqual([true, true, true]);
});

it("marks every prompt's detail stale when no prompt is named", () => {
  const qc = new QueryClient();
  seedPromptReads(qc);

  invalidatePromptReads(qc, BASE);

  expect(promptReadsStale(qc)).toEqual([true, true, true]);
});
