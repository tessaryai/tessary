// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { VERSION } from "../src/http.ts";

test("the version constant matches package.json", () => {
  // Bug: the release stamp writes package.json but not src/http.ts, so the User-Agent names the wrong version.
  const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8")) as { version: string };
  assert.equal(VERSION, pkg.version);
});
