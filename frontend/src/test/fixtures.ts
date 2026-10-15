// SPDX-License-Identifier: Apache-2.0
/*
 * Builders for the wire shapes several page tests render. Each returns a fully populated value with
 * neutral defaults, so a test states only the fields its assertion is about.
 */
import type { Classifier, GroundednessStatus, TraceDetailView, TraceListItemView } from "../api/types";

export type SpanView = TraceDetailView["spans"][number];

export function traceItem(over: Partial<TraceListItemView> = {}): TraceListItemView {
  return {
    id: "tr-1", name: "checkout-agent", started_at: "2026-09-25T10:00:00Z", ended_at: "2026-09-25T10:00:02Z",
    status: "ok", is_settled: true, latency_ms: 2_000, input_preview: null, output_preview: null, total_cost: null,
    input_cost: null, output_cost: null, span_count: null, error_count: 0, input_tokens: null, output_tokens: null,
    cache_read_tokens: null, cache_write_tokens: null, reasoning_tokens: null, total_tokens: null, call_site_id: null,
    session: null, thread_id: null, user_id: null, unpriced_spans: 0, detected_by: [], ...over,
  };
}

export function span(over: Partial<SpanView> & { id: string }): SpanView {
  return {
    attributes: {}, cache_read_tokens: null, cache_write_tokens: null, call_site_id: null, cost_source: "priced",
    depth: null, duration_ms: 100, ended_at: null, error_type: null, input: null, input_cost: null, input_tokens: null,
    is_logical_root: false, kind: "llm", level: null, model: null, model_id: null, name: null, output: null,
    output_cost: null, output_tokens: null, parent_span_id: null, path: null, payload_available: true,
    price_book_version: null, reasoning_tokens: null, retrieval_documents: [], started_at: "2026-09-25T10:00:00.000Z",
    status: "ok", tool_calls: [], total_cost: null, total_tokens: null, trace_id: "tr-1", ttft_ms: null, ...over,
  };
}

export function classifier(over: Partial<Classifier> = {}): Classifier {
  return {
    id: "clf-1", classifier_key: "frustration", name: "Frustration", description: "Frustration the agent caused.",
    detector: "frustration", config_json: null, built_in: true, version: 9, enabled: true, mode: "tracking",
    created_at: "2026-09-01T00:00:00Z", updated_at: "2026-09-01T00:00:00Z", readiness: null, shown_in_trace_views: true,
    call_site_ids: null, ...over,
  };
}

export function groundednessStatus(over: Partial<GroundednessStatus> = {}): GroundednessStatus {
  return {
    state: "off", mode: "dev", configured: false, available: false, reason: "no encoder URL configured",
    checked_at: null, ever_swept: false, last_scored_at: null, last_caught_up_at: null, setup_ref: "v1.3.0", ...over,
  };
}
