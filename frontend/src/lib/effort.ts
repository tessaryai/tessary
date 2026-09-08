// SPDX-License-Identifier: Apache-2.0
/**
 * Human labels for reasoning-effort levels.
 *
 * The levels themselves always come from the server — a model declares its own `effort_levels`, and
 * the sets differ (the OpenAI line takes three, GPT-5.6 on bedrock-mantle takes six). This maps the
 * wire values to something readable and falls back to the raw value, so a level added server-side
 * shows up immediately rather than being silently dropped by a client that has not been redeployed.
 *
 * Only `xhigh` genuinely needs the table — naive capitalisation renders it "Xhigh". The rest are
 * listed so the map is total over today's vocabulary and reads as the source of the copy.
 */
const LABELS: Record<string, string> = {
  none: "None",
  low: "Low",
  medium: "Medium",
  high: "High",
  xhigh: "Extra high",
  max: "Max",
};

export function effortLabel(level: string): string {
  return LABELS[level] ?? level;
}
