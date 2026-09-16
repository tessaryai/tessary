// SPDX-License-Identifier: Apache-2.0
/*
 * DetectionRow's stamp: decision 8b adds occurred_at (when the span ran) beside the always-present
 * detected_at (when the sweep checked it), and the row must show the OCCURRED time, falling back to
 * detected_at only when a row predates the migration (occurred_at null).
 */
import { describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach } from "vitest";
import { MemoryRouter } from "react-router-dom";
import type { ClassifierEvent } from "../../api/types";
import { DetectionRow } from "./DetectorsPage";
import { ago } from "./shared";

afterEach(cleanup);

const OLD = new Date(Date.now() - 40 * 24 * 60 * 60 * 1000).toISOString(); // > 30d: an absolute date
const RECENT = new Date().toISOString(); // "just now"

function event(overrides: Partial<ClassifierEvent>): ClassifierEvent {
  return {
    id: "d1",
    classifier_id: "c1",
    classifier_version: 1,
    subject_kind: "span",
    subject_id: "s1",
    trace_id: null,
    project_version_id: null,
    severity: "warn",
    evidence_json: null,
    confidence: "high",
    detected_at: RECENT,
    occurred_at: null,
    ...overrides,
  };
}

describe("DetectionRow", () => {
  it("stamps the row with occurred_at, not detected_at, when both are present", () => {
    render(
      <MemoryRouter>
        <DetectionRow event={event({ occurred_at: OLD, detected_at: RECENT })} />
      </MemoryRouter>,
    );
    expect(screen.queryByText(ago(OLD))).not.toBeNull();
    expect(screen.queryByText("just now")).toBeNull();
  });

  it("falls back to detected_at when occurred_at is null (a row from before migration 0012)", () => {
    render(
      <MemoryRouter>
        <DetectionRow event={event({ occurred_at: null, detected_at: OLD })} />
      </MemoryRouter>,
    );
    expect(screen.queryByText(ago(OLD))).not.toBeNull();
  });
});
