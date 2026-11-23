// SPDX-License-Identifier: Apache-2.0
/*
 * Config is a JSON object stored with each version. The bugs worth catching are an array, a primitive, null or bad
 * JSON sent as config (the server refuses it after the reader filled every other field), and a blank box refused.
 */
import { expect, it } from "vitest";
import { parseConfig } from "./promptConfig";

it.each([
  ["blank text is an empty object", "  ", { ok: true, value: {} }],
  ["an object passes", '{"temperature": 0.2}', { ok: true, value: { temperature: 0.2 } }],
  ["an array fails", "[1]", { ok: false, error: "Config must be a JSON object." }],
  ["a number fails", "5", { ok: false, error: "Config must be a JSON object." }],
  ["null fails", "null", { ok: false, error: "Config must be a JSON object." }],
  ["bad JSON fails", "{temperature", { ok: false, error: "Config must be a JSON object." }],
])("parseConfig: %s", (_, text, expected) => {
  expect(parseConfig(text)).toEqual(expected);
});
