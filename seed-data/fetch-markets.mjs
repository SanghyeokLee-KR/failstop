// Upbit Open API(공개 Quotation, 키 불필요)로 시드 데이터를 1회 수집한다.
// 원본 시세는 저작권이 Upbit에 있어 재배포하지 않는다. 마켓 목록과 일 캔들에서
// 시뮬레이션 초기값(시드가)과 통계 파라미터(변동성·드리프트·유동성)만 추출해 저장한다.
// 근거: docs/외부API_전략.md 1절.

import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  RateGate,
  fetchJson,
  ensureDir,
  writeJson,
  toPlainString,
  mean,
  median,
  sampleStddev,
} from "./lib/util.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = join(__dirname, "out");
const CANDLES_DIR = join(OUT_DIR, "candles");

const BASE = "https://api.upbit.com";
// 100ms 하한을 넘겨 여유를 둔다(초당 10회/IP 상한 대비 약 6.7회/초).
const MIN_INTERVAL_MS = 150;
const CANDLE_COUNT = 200; // 일 캔들 최대치
const TRADING_DAYS = 365; // 24시간 시장이라 연 환산에 365를 쓴다.

// 환경변수로 대상 마켓 통화와 수집 개수를 좁힌다. 기본은 KRW 마켓 전체.
const QUOTE = (process.env.SEED_MARKET_QUOTE || "KRW").toUpperCase();
const LIMIT = Number(process.env.SEED_MARKET_LIMIT || 0); // 0이면 제한 없음

const gate = new RateGate(MIN_INTERVAL_MS);

async function fetchMarketList() {
  const { data } = await fetchJson(`${BASE}/v1/market/all?is_details=false`, { gate });
  return data.filter((m) => m.market.startsWith(`${QUOTE}-`));
}

async function fetchDayCandles(market) {
  const url = `${BASE}/v1/candles/days?market=${encodeURIComponent(market)}&count=${CANDLE_COUNT}`;
  const { data } = await fetchJson(url, { gate });
  return data;
}

// 일 캔들 배열에서 재배포 대상이 아닌 파생 통계만 계산한다.
function deriveParams(market, meta, candles) {
  // Upbit는 최신순으로 준다. 로그수익률 계산을 위해 과거→최신으로 정렬한다.
  const sorted = [...candles].sort((a, b) => a.timestamp - b.timestamp);
  const closes = sorted.map((c) => Number(c.trade_price)).filter((n) => Number.isFinite(n) && n > 0);

  const returns = [];
  for (let i = 1; i < closes.length; i += 1) {
    returns.push(Math.log(closes[i] / closes[i - 1]));
  }

  const tradePrices = sorted.map((c) => Number(c.candle_acc_trade_price)).filter(Number.isFinite);
  const tradeVolumes = sorted.map((c) => Number(c.candle_acc_trade_volume)).filter(Number.isFinite);

  const dailyVol = sampleStddev(returns);
  const seedPrice = closes.length ? closes[closes.length - 1] : 0;

  return {
    market,
    korean_name: meta.korean_name,
    english_name: meta.english_name,
    source: "Upbit Open API (파생 통계, 원본 시세 아님)",
    collected_at: new Date().toISOString(),
    window: {
      candle_count: sorted.length,
      from: sorted.length ? sorted[0].candle_date_time_utc : null,
      to: sorted.length ? sorted[sorted.length - 1].candle_date_time_utc : null,
    },
    seed_price: toPlainString(seedPrice),
    price_stats: {
      min: toPlainString(closes.length ? Math.min(...closes) : 0),
      max: toPlainString(closes.length ? Math.max(...closes) : 0),
      mean: toPlainString(mean(closes)),
      median: toPlainString(median(closes)),
    },
    // 로그수익률 기반. daily는 표본 표준편차, annualized는 sqrt(365) 환산.
    volatility: {
      basis: "daily_log_return",
      sample_size: returns.length,
      daily_stddev: toPlainString(dailyVol),
      annualized: toPlainString(dailyVol * Math.sqrt(TRADING_DAYS)),
    },
    drift: {
      daily_mean_log_return: toPlainString(mean(returns)),
    },
    liquidity: {
      avg_daily_trade_price: toPlainString(mean(tradePrices)),
      avg_daily_trade_volume: toPlainString(mean(tradeVolumes)),
    },
    sufficient: returns.length >= 2,
  };
}

async function main() {
  await ensureDir(CANDLES_DIR);

  let list;
  try {
    list = await fetchMarketList();
  } catch (err) {
    console.error(`[markets] 마켓 목록 수집 실패: ${err.message}`);
    console.error("[markets] 네트워크·rate limit 문제일 수 있다. 스크립트는 그대로 두고 재시도한다.");
    process.exitCode = 1;
    return;
  }

  if (LIMIT > 0) list = list.slice(0, LIMIT);
  console.log(`[markets] 대상 ${QUOTE} 마켓 ${list.length}개, 간격 ${MIN_INTERVAL_MS}ms`);

  const index = [];
  const failures = [];

  for (const meta of list) {
    try {
      const candles = await fetchDayCandles(meta.market);
      const params = deriveParams(meta.market, meta, candles);
      await writeJson(join(CANDLES_DIR, `${meta.market}.json`), params);
      index.push({
        market: params.market,
        korean_name: params.korean_name,
        english_name: params.english_name,
        seed_price: params.seed_price,
        annualized_volatility: params.volatility.annualized,
        sufficient: params.sufficient,
      });
      console.log(`[markets] ${meta.market} 완료 (캔들 ${candles.length})`);
    } catch (err) {
      // 한 종목 실패가 전체를 막지 않도록 격리하고 다음으로 진행한다.
      failures.push({ market: meta.market, reason: err.message });
      console.warn(`[markets] ${meta.market} 건너뜀: ${err.message}`);
    }
  }

  const manifest = {
    meta: {
      source: "Upbit Open API",
      endpoints: ["/v1/market/all", "/v1/candles/days"],
      note: "원본 시세 미저장. 시드가·변동성·드리프트·유동성 파생 통계만 저장. 비상업 교육용.",
      quote: QUOTE,
      collected_at: new Date().toISOString(),
      throttle_ms: MIN_INTERVAL_MS,
      candle_count: CANDLE_COUNT,
      count: index.length,
      failed: failures.length,
    },
    markets: index,
    failures,
  };
  await writeJson(join(OUT_DIR, "markets.json"), manifest);
  console.log(`[markets] markets.json 저장. 성공 ${index.length}, 실패 ${failures.length}`);
}

main().catch((err) => {
  console.error(`[markets] 예기치 못한 오류: ${err.stack || err.message}`);
  process.exitCode = 1;
});
