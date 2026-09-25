#!/usr/bin/env node
/**
 * 웹 거래 화면의 재동기화 관찰(ADR 0034 S6, 마켓분할_리더_S6_설계 7-2 W6·W7). 아직 스택에서 돌린 적이 없다(NOT RUN).
 * W6·W7 은 P3 가 필요해 S7 관문에서 돈다.
 *
 * 실제 서버에 붙은 웹(NEXT_PUBLIC_DATASOURCE=http)의 거래 화면을 브라우저로 열어 두고, 그 페이지가 내는 REST 요청과 받은 STOMP 메시지를
 * 시각과 함께 적는다. 판정은 두 가지를 본다.
 *   W6 한 파티션 소유자 kill 뒤: 티커 합류 요청의 markets 에 오른 파티션의 마켓만 있고 다른 파티션 마켓이 없다. 계좌·주문 REST 가
 *      한 번씩이다. 다른 파티션 마켓의 WS 메시지는 epoch 가 그대로이고 결번이 없다.
 *   W7 정상 창: 첫 적재 뒤 REST 가 0 이다(재동기화 없음). 두 파티션 마켓의 epoch 가 다르다.
 * 이 파일은 관찰만 하고 JSON 을 남긴다. 창 경계(kill 시각)는 실행기가 --mark-file 에 적은 ISO 시각을 읽어 기록에 싣는다.
 * WS 는 LB 를 거치지 않는 주소를 쓰도록 웹을 띄운다(p0 소유자에 붙인다). 그 배선은 실행기 몫이다.
 *
 * 사용: node harness/scripts/s6-web-resync.mjs --url=http://localhost:3000/exchange/KRW-BTC --duration=180 --out=<json>
 *        [--mark-file=<파일>] [--settle=15]
 */

import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

const WEB_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "web");

const argv = process.argv.slice(2);
const arg = (k, d) => {
  const hit = argv.find((a) => a.startsWith(`--${k}=`));
  return hit === undefined ? d : hit.split("=").slice(1).join("=");
};

const URL_ARG = arg("url", "http://localhost:3000/exchange/KRW-BTC");
const DURATION_S = Number(arg("duration", "180"));
const SETTLE_S = Number(arg("settle", "15"));
const OUT = arg("out", "");
const MARK_FILE = arg("mark-file", "");
if (!OUT) {
  console.error("--out 이 필요하다");
  process.exit(2);
}
if (!Number.isFinite(DURATION_S) || DURATION_S <= 0 || !Number.isFinite(SETTLE_S) || SETTLE_S < 0) {
  console.error("--duration·--settle 형식 위반");
  process.exit(2);
}

/* playwright 는 web 의 devDependency 다. 루트 scripts/check-browser.mjs 와 같은 방식으로 web/node_modules 에서 끌어온다 */
const { chromium } = createRequire(path.join(WEB_DIR, "package.json"))("playwright");

const DEST = /^\/topic\/(orderbook|trade|ticker)\.(.+)$/;
const NULL = String.fromCharCode(0);

/** STOMP 프레임 문자열에서 MESSAGE 의 목적지와 본문을 꺼낸다 */
function parseStomp(data) {
  const out = [];
  for (const raw of String(data).split(NULL)) {
    const text = raw.replace(/^\n+/, "");
    if (!text.startsWith("MESSAGE")) continue;
    const sep = text.indexOf("\n\n");
    if (sep < 0) continue;
    const headers = {};
    for (const line of text.slice(0, sep).split("\n").slice(1)) {
      const i = line.indexOf(":");
      if (i > 0) headers[line.slice(0, i)] = line.slice(i + 1);
    }
    out.push({ destination: headers.destination || "", body: text.slice(sep + 2) });
  }
  return out;
}

const t0 = Date.now();
const requests = [];
const ws = { frames: 0, markets: {} };

function noteMessage(destination, body) {
  const m = DEST.exec(destination);
  if (!m) return;
  let msg;
  try {
    msg = JSON.parse(body);
  } catch {
    return;
  }
  const key = `${m[2]}|${m[1]}`;
  const cur = ws.markets[key] || (ws.markets[key] = { count: 0, epochs: {}, gaps: 0, lastSeq: null, lastEpoch: null });
  cur.count += 1;
  const at = Date.now();
  if (typeof msg.epoch === "number" && !(String(msg.epoch) in cur.epochs)) cur.epochs[String(msg.epoch)] = at;
  // 조밀 채널(호가·티커)만 결번을 센다. seq 0 은 기준점을 비운다(API§5-2 v2.58)
  if (m[1] !== "trade" && typeof msg.seq === "number") {
    if (msg.seq === 0) {
      cur.lastSeq = null;
    } else {
      if (cur.lastSeq !== null && cur.lastEpoch === msg.epoch && msg.seq > cur.lastSeq + 1) cur.gaps += 1;
      if (cur.lastSeq === null || cur.lastEpoch !== msg.epoch || msg.seq > cur.lastSeq) {
        cur.lastSeq = msg.seq;
        cur.lastEpoch = msg.epoch;
      }
    }
  }
}

const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  page.on("request", (req) => {
    let u;
    try {
      u = new URL(req.url());
    } catch {
      return;
    }
    if (!u.pathname.startsWith("/api/v1/")) return;
    requests.push({ at: Date.now(), method: req.method(), path: u.pathname, query: Object.fromEntries(u.searchParams) });
  });
  page.on("websocket", (socket) => {
    socket.on("framereceived", (frame) => {
      ws.frames += 1;
      for (const m of parseStomp(frame.payload)) noteMessage(m.destination, m.body);
    });
  });
  await page.goto(URL_ARG, { waitUntil: "domcontentloaded" });
  await page.waitForTimeout(DURATION_S * 1000);
} finally {
  await browser.close();
}

const mark = MARK_FILE && existsSync(MARK_FILE) ? readFileSync(MARK_FILE, "utf8").trim() : null;
const settleAt = t0 + SETTLE_S * 1000;
const after = requests.filter((r) => r.at >= settleAt);
const tickerMarkets = after
  .filter((r) => r.path === "/api/v1/ticker")
  .map((r) => ({ at: r.at, markets: String(r.query.markets || "").split(",").filter(Boolean) }));
const count = (p) => after.filter((r) => r.path === p).length;
const result = {
  url: URL_ARG,
  startedAt: new Date(t0).toISOString(),
  durationSec: DURATION_S,
  settleSec: SETTLE_S,
  mark,
  restAfterSettle: after.length,
  tickerRequestsAfterSettle: tickerMarkets,
  accountsAfterSettle: count("/api/v1/accounts"),
  openOrdersAfterSettle: count("/api/v1/orders/open"),
  closedOrdersAfterSettle: count("/api/v1/orders/closed"),
  orderbookAfterSettle: after.filter((r) => r.path === "/api/v1/orderbook").map((r) => ({ at: r.at, market: r.query.market })),
  ws,
  requests,
};
writeFileSync(OUT, JSON.stringify(result, null, 2));
console.log(JSON.stringify({ out: OUT, restAfterSettle: after.length, wsFrames: ws.frames }));
