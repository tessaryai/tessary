// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

/** A group key of a view. {@code sql} is the full expression, collated "C" so groups sort bytewise. */
public record MetricsDimension(String wire, String label, String sql, boolean highCardinality) {}
