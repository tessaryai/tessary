// SPDX-License-Identifier: Apache-2.0
// One OpenTelemetry span, sent to a running Tessary with nothing installed (epic 7 clause 5, #1193).
//
// Runs on the Node the sandbox-runner image already carries, so a reader with only Docker and the
// running stack can emit their first span without an SDK, a collector or a package manager:
//
//   docker compose exec -T sandbox-runner node - <token> < scripts/emit-span.js
//
// The token is the value after `Bearer` in the connect gate's Header field. The span is a chat call
// tagged with `tessary.call_site.id`, which is what opens the gate; the OTLP/HTTP protobuf body is
// encoded by hand below because the ingest endpoint speaks protobuf only and this file may depend
// on nothing outside Node's standard library.
//
//   argv: <token> [endpoint] [call-site id]
//   endpoint defaults to the frontend's plain-HTTP site on the compose network, the same path a
//   browser reaches; call-site id defaults to "first-span".
"use strict";
const http = require("node:http");
const https = require("node:https");
const crypto = require("node:crypto");

const [token, endpoint = "http://frontend:8000/v1/traces", callSite = "first-span"] = process.argv.slice(2);
if (!token) {
  console.error("usage: node - <token> [endpoint] [call-site id]  (stdin: this file)");
  process.exit(2);
}

// Protobuf wire encoding: field number and wire type in a varint tag, then the value.
function varint(n) {
  const out = [];
  let v = BigInt(n);
  if (v < 0n) throw new RangeError(`varint cannot encode a negative value (${n})`);
  while (v >= 0x80n) { out.push(Number((v & 0x7fn) | 0x80n)); v >>= 7n; }
  out.push(Number(v));
  return Buffer.from(out);
}
const tag = (field, wire) => varint((field << 3) | wire);
const bytesField = (field, buf) => Buffer.concat([tag(field, 2), varint(buf.length), buf]);
const stringField = (field, s) => bytesField(field, Buffer.from(s, "utf8"));
const varintField = (field, n) => Buffer.concat([tag(field, 0), varint(n)]);
function fixed64Field(field, n) {
  const b = Buffer.alloc(8);
  b.writeBigUInt64LE(BigInt(n));
  return Buffer.concat([tag(field, 1), b]);
}
// KeyValue { key = 1; AnyValue value = 2 }; AnyValue { string_value = 1; int_value = 3 }
const kv = (key, value) => Buffer.concat([
  stringField(1, key),
  bytesField(2, typeof value === "number" ? varintField(3, value) : stringField(1, String(value))),
]);

const nowNs = BigInt(Date.now()) * 1000000n;
const span = Buffer.concat([
  bytesField(1, crypto.randomBytes(16)),          // trace_id
  bytesField(2, crypto.randomBytes(8)),           // span_id
  stringField(5, "chat gpt-4o-mini"),             // name
  varintField(6, 3),                              // kind = CLIENT
  fixed64Field(7, nowNs - 1200000000n),           // start_time_unix_nano
  fixed64Field(8, nowNs),                         // end_time_unix_nano
  bytesField(9, kv("tessary.call_site.id", callSite)),
  bytesField(9, kv("gen_ai.operation.name", "chat")),
  bytesField(9, kv("gen_ai.system", "openai")),
  bytesField(9, kv("gen_ai.request.model", "gpt-4o-mini")),
  bytesField(9, kv("gen_ai.usage.input_tokens", 12)),
  bytesField(9, kv("gen_ai.usage.output_tokens", 7)),
]);
const request = bytesField(1, Buffer.concat([                       // ExportTraceServiceRequest.resource_spans
  bytesField(1, bytesField(1, kv("service.name", "first-span"))),   // resource.attributes
  bytesField(2, Buffer.concat([                                     // scope_spans
    bytesField(1, stringField(1, "tessary-emit-span")),             // scope.name
    bytesField(2, span),                                            // spans
  ])),
]));

const url = new URL(endpoint);
const client = url.protocol === "https:" ? https : http;
const req = client.request(url, {
  method: "POST",
  headers: {
    "Content-Type": "application/x-protobuf",
    "Authorization": `Bearer ${token}`,
    "Content-Length": request.length,
  },
}, (res) => {
  let body = "";
  res.on("data", (c) => { body += c; });
  res.on("end", () => {
    if (res.statusCode === 200) {
      console.log(`sent one span tagged tessary.call_site.id=${callSite} to ${endpoint} (HTTP 200)`);
      process.exit(0);
    }
    console.error(`Tessary answered HTTP ${res.statusCode} from ${endpoint}${body ? `: ${body.slice(0, 300)}` : ""}`);
    process.exit(1);
  });
});
req.on("error", (err) => { console.error(`could not reach ${endpoint}: ${err.message}`); process.exit(1); });
req.end(request);
