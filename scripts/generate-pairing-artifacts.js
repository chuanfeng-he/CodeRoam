"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const zlib = require("node:zlib");
const QRCode = require("../node_modules/qrcode-terminal/vendor/QRCode");
const QRErrorCorrectLevel = require("../node_modules/qrcode-terminal/vendor/QRCode/QRErrorCorrectLevel");
const { createPairingPayload } = require("../shared/src/pairing");

const repoRoot = path.resolve(__dirname, "..");
const stateDir = path.join(os.homedir(), ".config", "codex-android-remote");
const config = readJson(path.join(stateDir, "config.json"));
const state = readJson(path.join(stateDir, "device-state.json"));
const relayUrl = process.env.CODEX_REMOTE_RELAY || config.relayUrl;
const relayHostOverrides = process.env.CODEX_REMOTE_RELAY_HOSTS || config.relayHostOverrides || {};

if (!relayUrl) {
  throw new Error("Missing relayUrl in bridge config.");
}
if (!state.relaySessionId || !state.hostDeviceId || !state.hostIdentity?.publicKey) {
  throw new Error("Missing host pairing state.");
}

const payload = createPairingPayload({
  relayUrl,
  sessionId: state.relaySessionId,
  hostDeviceId: state.hostDeviceId,
  hostIdentityPublicKey: state.hostIdentity.publicKey.trim(),
  relayHostOverrides,
  displayName: process.env.HOSTNAME || "linux-codex",
});
const payloadText = JSON.stringify(payload);
const qr = makeQr(payloadText);

writeJsonArtifact(payloadText);
writeSvgArtifact(qr);
writePngArtifact(qr);
writeHtmlArtifact(payload, payloadText);

console.log(`Generated pairing artifacts for ${payload.relay}`);
console.log(path.join(repoRoot, "codex-remote-pairing.html"));

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function makeQr(text) {
  const qr = new QRCode(-1, QRErrorCorrectLevel.L);
  qr.addData(text);
  qr.make();
  return {
    modules: qr.modules,
    size: qr.getModuleCount(),
  };
}

function writeJsonArtifact(payloadText) {
  fs.writeFileSync(path.join(repoRoot, "codex-remote-pairing.json"), `${payloadText}\n`, "utf8");
}

function writeSvgArtifact(qr) {
  const margin = 4;
  const size = qr.size + margin * 2;
  const rects = [];
  for (let row = 0; row < qr.size; row += 1) {
    for (let col = 0; col < qr.size; col += 1) {
      if (qr.modules[row][col]) {
        rects.push(`<rect x="${col + margin}" y="${row + margin}" width="1" height="1"/>`);
      }
    }
  }
  const svg = [
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" shape-rendering="crispEdges">`,
    `<rect width="${size}" height="${size}" fill="#fff"/>`,
    `<g fill="#000">${rects.join("")}</g>`,
    "</svg>",
    "",
  ].join("\n");
  fs.writeFileSync(path.join(repoRoot, "codex-remote-pairing.svg"), svg, "utf8");
}

function writePngArtifact(qr) {
  const margin = 4;
  const scale = 8;
  const moduleSize = qr.size + margin * 2;
  const width = moduleSize * scale;
  const height = width;
  const rows = [];

  for (let y = 0; y < height; y += 1) {
    const line = Buffer.alloc(1 + width, 255);
    line[0] = 0;
    const moduleRow = Math.floor(y / scale) - margin;
    for (let x = 0; x < width; x += 1) {
      const moduleCol = Math.floor(x / scale) - margin;
      const dark = moduleRow >= 0 &&
        moduleRow < qr.size &&
        moduleCol >= 0 &&
        moduleCol < qr.size &&
        qr.modules[moduleRow][moduleCol];
      line[x + 1] = dark ? 0 : 255;
    }
    rows.push(line);
  }

  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk("IHDR", Buffer.concat([
      uint32(width),
      uint32(height),
      Buffer.from([8, 0, 0, 0, 0]),
    ])),
    pngChunk("IDAT", zlib.deflateSync(Buffer.concat(rows))),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
  fs.writeFileSync(path.join(repoRoot, "codex-remote-pairing.png"), png);
}

function writeHtmlArtifact(payload, payloadText) {
  const generatedAt = new Date().toLocaleString("zh-CN", { timeZone: "Asia/Shanghai", hour12: false });
  const html = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>CodeRoam Pairing</title>
  <style>
    body { margin: 0; font-family: sans-serif; background: #f7f7f4; color: #20201d; }
    main { min-height: 100vh; display: grid; place-items: center; padding: 24px; box-sizing: border-box; }
    section { width: min(760px, 100%); text-align: center; }
    img { width: min(520px, 92vw); height: auto; image-rendering: pixelated; background: white; padding: 18px; border: 1px solid #d8d8cf; }
    pre { text-align: left; white-space: pre-wrap; overflow-wrap: anywhere; background: white; padding: 14px; border: 1px solid #d8d8cf; border-radius: 8px; }
    .meta { color: #63635c; }
  </style>
</head>
<body>
  <main>
    <section>
      <h1>CodeRoam 配对二维码</h1>
      <p>手机 App 点“扫描二维码”后扫下面这个码，或粘贴 Pairing JSON。</p>
      <p class="meta">生成时间：${escapeHtml(generatedAt)}，Relay：${escapeHtml(payload.relay)}</p>
      <img src="codex-remote-pairing.png" alt="CodeRoam pairing QR code">
      <pre>${escapeHtml(payloadText)}</pre>
    </section>
  </main>
</body>
</html>
`;
  fs.writeFileSync(path.join(repoRoot, "codex-remote-pairing.html"), html, "utf8");
}

function pngChunk(type, data) {
  const typeBuffer = Buffer.from(type, "ascii");
  return Buffer.concat([
    uint32(data.length),
    typeBuffer,
    data,
    uint32(crc32(Buffer.concat([typeBuffer, data]))),
  ]);
}

function uint32(value) {
  const buffer = Buffer.alloc(4);
  buffer.writeUInt32BE(value >>> 0, 0);
  return buffer;
}

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
