// SPDX-License-Identifier: Apache-2.0
// A DNS sink: logs every query name it is asked, answers NXDOMAIN to all of them.
const dgram = require("node:dgram");
const s = dgram.createSocket("udp4");
s.on("message", (msg, rinfo) => {
  let i = 12; const labels = [];
  while (i < msg.length && msg[i] !== 0) { const n = msg[i]; labels.push(msg.subarray(i + 1, i + 1 + n).toString()); i += n + 1; }
  const name = labels.join(".");
  const qend = i + 1 + 4;
  console.log(`query ${name} from ${rinfo.address}`);
  const resp = Buffer.alloc(qend);
  msg.copy(resp, 0, 0, qend);
  resp[2] = 0x81; resp[3] = 0x83;            // QR, RD, RA, RCODE=3 NXDOMAIN
  resp.writeUInt16BE(0, 6); resp.writeUInt16BE(0, 8); resp.writeUInt16BE(0, 10);
  s.send(resp, rinfo.port, rinfo.address);
});
s.bind(53, "0.0.0.0", () => console.log("dns sink listening on :53"));
