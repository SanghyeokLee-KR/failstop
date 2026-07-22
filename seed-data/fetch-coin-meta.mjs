// CoinGecko Demo API로 코인 이름·심볼·로고 URL을 1회 수집하고, 로고 이미지는
// 내려받아 out/logos/ 에 self-host 한다. 런타임에 coin-images.coingecko.com을
// 참조하면 외부 의존이 생기므로 금지. 근거: docs/외부API_전략.md 2절.
//
// 키는 환경변수 COINGECKO_DEMO_KEY로 받는다. 없으면 안내만 하고 스킵한다.

import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { RateGate, fetchJson, fetchBuffer, ensureDir, writeJson } from "./lib/util.mjs";
import { writeFile } from "node:fs/promises";

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = join(__dirname, "out");
const LOGOS_DIR = join(OUT_DIR, "logos");

const BASE = "https://api.coingecko.com/api/v3";
// Demo 한도는 100 calls/min. 마켓 호출은 소수라 여유가 크지만 이미지 다운로드까지
// 감안해 간격을 둔다.
const MIN_INTERVAL_MS = 200;
const VS = (process.env.SEED_COIN_VS || "krw").toLowerCase();
const PER_PAGE = Number(process.env.SEED_COIN_PER_PAGE || 100);
const PAGES = Number(process.env.SEED_COIN_PAGES || 1);

const KEY = process.env.COINGECKO_DEMO_KEY;

function extFromContentType(ct, fallbackUrl) {
  if (ct.includes("png")) return "png";
  if (ct.includes("jpeg") || ct.includes("jpg")) return "jpg";
  if (ct.includes("svg")) return "svg";
  if (ct.includes("webp")) return "webp";
  const m = /\.(png|jpg|jpeg|svg|webp)(?:\?|$)/i.exec(fallbackUrl || "");
  return m ? m[1].toLowerCase() : "png";
}

async function main() {
  if (!KEY) {
    console.log("[coin-meta] COINGECKO_DEMO_KEY 미설정. 수집을 건너뛴다.");
    console.log("[coin-meta] 발급: https://www.coingecko.com/en/api  Developer Dashboard의 Add New Key");
    console.log("[coin-meta] 실행: COINGECKO_DEMO_KEY=키값 node fetch-coin-meta.mjs");
    return; // 키가 없을 때 실패로 취급하지 않는다.
  }

  await ensureDir(LOGOS_DIR);
  const gate = new RateGate(MIN_INTERVAL_MS);
  const headers = { "x-cg-demo-api-key": KEY };

  const coins = [];
  for (let page = 1; page <= PAGES; page += 1) {
    const url = `${BASE}/coins/markets?vs_currency=${VS}&order=market_cap_desc&per_page=${PER_PAGE}&page=${page}`;
    let rows;
    try {
      ({ data: rows } = await fetchJson(url, { headers, gate }));
    } catch (err) {
      console.error(`[coin-meta] 마켓 목록 page ${page} 실패: ${err.message}`);
      break;
    }
    if (!Array.isArray(rows) || rows.length === 0) break;
    coins.push(...rows);
    console.log(`[coin-meta] page ${page} 수신 ${rows.length}건`);
  }

  const catalog = [];
  const failures = [];

  for (const c of coins) {
    const entry = {
      id: c.id,
      symbol: (c.symbol || "").toUpperCase(),
      name: c.name,
      source_image_url: c.image || null,
      logo_file: null,
    };

    if (c.image) {
      try {
        const { buf, contentType } = await fetchBuffer(c.image, { gate });
        const ext = extFromContentType(contentType, c.image);
        const file = `${c.id}.${ext}`;
        await writeFile(join(LOGOS_DIR, file), buf);
        entry.logo_file = `logos/${file}`;
      } catch (err) {
        // 로고 하나 실패가 전체 카탈로그를 막지 않도록 격리한다.
        failures.push({ id: c.id, reason: err.message });
        console.warn(`[coin-meta] ${c.id} 로고 실패: ${err.message}`);
      }
    }
    catalog.push(entry);
  }

  const manifest = {
    meta: {
      source: "CoinGecko Demo API",
      endpoint: "/coins/markets",
      vs_currency: VS,
      collected_at: new Date().toISOString(),
      attribution: "Data provided by CoinGecko (https://www.coingecko.com/en/api)",
      note: "로고는 self-host. 런타임은 out/logos만 참조한다.",
      count: catalog.length,
      failed: failures.length,
    },
    coins: catalog,
    failures,
  };
  await writeJson(join(OUT_DIR, "coin-meta.json"), manifest);

  // 로고에 붙일 출처 표기. self-host해도 CoinGecko 어트리뷰션은 유지한다.
  const attrText = [
    "Coin metadata and logos sourced from CoinGecko.",
    "Data provided by CoinGecko: https://www.coingecko.com/en/api",
    "",
    "로고 이미지는 각 코인 프로젝트의 상표일 수 있다. 교육용 시뮬레이션 한정 사용.",
    "",
  ].join("\n");
  await writeFile(join(LOGOS_DIR, "ATTRIBUTION.txt"), attrText, "utf8");

  console.log(`[coin-meta] coin-meta.json 저장. 코인 ${catalog.length}, 로고 실패 ${failures.length}`);
}

main().catch((err) => {
  console.error(`[coin-meta] 예기치 못한 오류: ${err.stack || err.message}`);
  process.exitCode = 1;
});
