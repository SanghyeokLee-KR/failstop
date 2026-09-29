/**
 * 원인 분석과 부하 판정, 오류 로그 칸(API 설계 §6-F v2.65)을 관제 판으로 옮기는 규칙과 AI 패널의 표시 글자를 본다.
 * 입력은 계약 절의 JSON 예시다. 서버가 실제로 낸 응답(스냅숏)이 아니며, 한 판의 다른 칸은 서버 스냅숏(overview-full)을 쓴다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { absentBoard, analysisOf, boardOf, emptyExtras, logsOf, type WireAnalysis, type WireLogSection, type WireOverview } from "../lib/admin/httpOps";
import { citationParts, lackingReason, loadFacts, seenWord, unitValue } from "../lib/admin/causeText";
import { OpsSim, SIM_LOG_SOURCE } from "../lib/admin/opsSim";
import type { AiDesk, CauseAnalysis, CauseCitation, LogDesk, OpsSection } from "../lib/admin/types";

const FIX = path.resolve(__dirname, "..", "..", "tests", "fixtures", "msp");
const overview = () => (JSON.parse(readFileSync(path.join(FIX, "overview-full.json"), "utf8")) as { body: WireOverview }).body;

function ready<T>(s: OpsSection<T>, what: string): T {
  assert.equal(s.state, "READY", what);
  return (s as { data: T }).data;
}

/** 계약 '원인 분석과 부하 판정'의 GET .../ai/analysis 예시에서 analysis. 한 판의 ai.analysis와 같은 모양이다 */
const ANALYSIS = {
  state: "READY", reason: null, analyzed_at: "2026-09-28T03:15:07.000Z", model: "gpt-oss:20b", latency_ms: 6120,
  prompt_version: "msp-analysis/1", evidence_sha256: "2c1d...",
  window: { start: "2026-09-28T02:55:00.000Z", anchor: "2026-09-28T03:10:00.000Z", end: "2026-09-28T03:15:00.000Z", step_seconds: 15 },
  trigger: ["AppTierCpuHigh", "read.scale_out"],
  target: { id: "signal.app_cpu", label: "앱 계층 CPU(1분 평균)" },
  load: {
    pattern: "SUSTAINED", ai_pattern: "SUSTAINED",
    ai_reason: "앱 계층 CPU가 기준 0.5 이상으로 3분 45초 이어졌고 요청률과 함께 올랐습니다.",
    value: "0.72", threshold: "0.5", above_for_seconds: 225, sustain_seconds: 180,
    above_total_seconds: 240, excursions: 1, window_seconds: 900, slope_per_minute: "0.031",
    request_correlation: "0.93", coverage: "1", sampled_at: "2026-09-28T03:15:00.000Z",
  },
  hypotheses: [
    {
      rank: 1, cause: "GET /api/v1/candles 요청이 크게 늘어 앱 계층 CPU를 끌어올린 것으로 보입니다.",
      citations: [
        { id: "route.1", kind: "route", label: "GET /api/v1/candles 요청률", unit: "per_second", value: "182.4", before: "41.3", change: "141.1", now: "190.2", at: "2026-09-28T03:15:00.000Z" },
        { id: "signal.app_cpu", kind: "signal", label: "앱 계층 CPU(1분 평균)", unit: "ratio", value: "0.68", before: "0.31", change: "0.37", now: "0.72", at: "2026-09-28T03:15:00.000Z" },
      ],
    },
  ],
  insufficient: null,
  missing: [{ id: "signal.http_p95", label: "응답 p95", reason: "no_series" }],
  dropped: 0,
};

/** 계약 '오류 로그'의 한 판 logs(READY) 예시 */
const LOGS = {
  state: "READY", polled_at: "2026-09-28T03:15:02.000Z", window_minutes: 15,
  sources: [
    { node: "admin", state: "READY", reason: null },
    { node: "chaos-api-1", state: "READY", reason: null },
    { node: "chaos-api-2", state: "READY", reason: null },
    { node: "chaos-api-3", state: "OFF", reason: "unreachable" },
  ],
  data: {
    errors: 12, warnings: 3,
    groups: [{
      id: "3f9a0c21b7e4d5a6", level: "ERROR", logger: "OrderGateway", exception: "PSQLException",
      exception_message: "Connection to [주소] refused.", frame: "OrderGateway.submit",
      message: "주문 접수 실패: 저널 제출 예외", count: 12, total: 40,
      first_seen: "2026-09-28T03:05:11.000Z", last_seen: "2026-09-28T03:14:59.000Z",
      nodes: ["chaos-api-1", "chaos-api-2"],
    }],
    more: 0,
    analysis: {
      state: "READY", reason: null, analyzed_at: "2026-09-28T03:15:40.000Z", model: "gpt-oss:20b",
      latency_ms: 4100, prompt_version: "msp-logs/1",
      summary: "두 앱 노드에서 DB 연결 거부 오류가 10분 동안 이어졌습니다.",
      findings: [{
        group_id: "3f9a0c21b7e4d5a6", summary: "주문 접수가 DB에 닿지 못해 실패했습니다.",
        cause: "주 DB 전환 중 연결 거부로 보입니다. 같은 창의 DB 역할 변경 기록과 대조해야 합니다.",
      }],
    },
  },
} as unknown as WireLogSection;

/** 한 판(서버 스냅숏)에 원인 분석과 오류 로그 칸을 얹어 관제 판으로 옮긴다. logs가 null이면 그 칸을 싣지 않는 서버다 */
function board(analysis: unknown, logs: WireLogSection | null = LOGS) {
  const w = overview();
  const c = boardOf({ ...w, ai: { ...w.ai, analysis: analysis as WireAnalysis }, logs: logs ?? undefined }, emptyExtras(), Date.parse(w.generated_at ?? ""));
  return { c, desk: ready<AiDesk>(c.ai, "ai") };
}

function withAnalysis(patch: Record<string, unknown>): CauseAnalysis {
  return analysisOf({ ...ANALYSIS, ...patch } as WireAnalysis);
}

const cite = (c: Partial<CauseCitation> & Pick<CauseCitation, "kind">): CauseCitation => ({
  id: "x", label: "지표", unit: null, value: null, text: null, before: null, change: null, at: null, ...c,
});

test("원인 분석: 한 판의 ai.analysis에서 부하 판정(규칙과 모델), 순위 있는 가설과 서버가 붙인 인용, 수집 필요를 옮긴다", () => {
  const { desk } = board(ANALYSIS);
  const a = desk.analysis;
  assert.ok(a);
  // 모르는 필드(지연, 근거 해시, 창, 버린 가설 수)는 옮기지 않는다
  assert.deepEqual(Object.keys(a).sort(), ["analyzedAt", "hypotheses", "insufficient", "load", "missing", "model", "reason", "state"]);
  assert.equal(a.state, "READY");
  assert.equal(a.reason, null);
  assert.equal(a.analyzedAt, "2026-09-28T03:15:07.000Z");
  assert.equal(a.model, "gpt-oss:20b");

  // 부하 판정: 비율은 백분율, 기준은 임계값 표와 같은 단위다. 모델의 판정은 설명이라 따로 싣는다
  assert.deepEqual(a.load, {
    pattern: "SUSTAINED", aiPattern: "SUSTAINED", aiReason: "앱 계층 CPU가 기준 0.5 이상으로 3분 45초 이어졌고 요청률과 함께 올랐습니다.",
    cpuPct: 72, thresholdPct: 50, aboveForSec: 225, sustainSec: 180, aboveTotalSec: 240, excursions: 1, windowSec: 900,
    requestCorrelation: 0.93, coveragePct: 100,
  });

  // 가설: 배열 순서가 순위이고, 인용은 십진 문자열을 서버 단위의 수로 읽는다
  assert.equal(a.hypotheses.length, 1);
  assert.equal(a.hypotheses[0].cause, "GET /api/v1/candles 요청이 크게 늘어 앱 계층 CPU를 끌어올린 것으로 보입니다.");
  assert.deepEqual(a.hypotheses[0].citations, [
    { id: "route.1", kind: "route", label: "GET /api/v1/candles 요청률", unit: "per_second", value: 182.4, text: null, before: 41.3, change: 141.1, at: "2026-09-28T03:15:00.000Z" },
    { id: "signal.app_cpu", kind: "signal", label: "앱 계층 CPU(1분 평균)", unit: "ratio", value: 0.68, text: null, before: 0.31, change: 0.37, at: "2026-09-28T03:15:00.000Z" },
  ]);
  assert.equal(a.insufficient, null);
  assert.deepEqual(a.missing, [{ id: "signal.http_p95", label: "응답 p95", reason: "결과 없음" }]);
  assert.equal(lackingReason(a), null, "가설이 있고 부족 사유가 없으면 근거 부족 줄을 두지 않는다");

  // 패널 글자: 부하 특징 한 줄과 인용 칩
  assert.deepEqual(loadFacts(a.load as NonNullable<CauseAnalysis["load"]>), [
    "앱 계층 CPU 72%", "기준 50%", "3분 45초 연속(지속 기준 3분)", "15분 안 초과 1회", "요청률 상관 0.93",
  ]);
  const [route, cpu] = a.hypotheses[0].citations.map(citationParts);
  assert.deepEqual(route, { severity: null, value: "182.4건/초", before: "41.3", change: "▲141.1", atWord: null });
  assert.deepEqual(cpu, { severity: null, value: "68%", before: "31%", change: "▲37%p", atWord: null });
});

test("원인 분석이 없으면 null이고, 모델이 실패한 분석은 가설 없이 사유를 적되 부하 판정과 수집 필요는 남긴다", () => {
  assert.equal(board(null).desk.analysis, null);
  const w = overview();
  assert.equal(ready<AiDesk>(boardOf(w, emptyExtras(), Date.now()).ai, "ai").analysis, null, "이 항목을 싣지 않는 서버");

  const failed = withAnalysis({ state: "MODEL_ERROR", reason: "timeout" });
  assert.equal(failed.state, "MODEL_ERROR");
  assert.equal(failed.reason, "시간 초과");
  assert.deepEqual(failed.hypotheses, [], "실패한 모델의 가설은 싣지 않는다");
  assert.equal(failed.load?.pattern, "SUSTAINED", "규칙 판정은 코드가 계산한 값이다");
  assert.equal(failed.load?.aiPattern, null, "모델 판정은 모델이 출력을 냈을 때만 싣는다");
  assert.equal(failed.load?.aiReason, null);
  assert.equal(failed.missing.length, 1);
  assert.equal(lackingReason(failed), null, "실패는 근거 부족이 아니라 실패 줄로 적는다");

  assert.equal(withAnalysis({ state: "INVALID_OUTPUT", reason: "link" }).reason, "링크 표기 포함");
  assert.equal(withAnalysis({ state: "INVALID_OUTPUT", reason: "shape" }).reason, "출력 형식 불일치");
  assert.equal(withAnalysis({ state: "INVALID_OUTPUT", reason: "pattern" }).reason, "부하 판정 값 오류");
  assert.equal(withAnalysis({ state: "INVALID_OUTPUT", reason: "new_code" }).reason, "new_code", "모르는 코드는 그대로 적는다");
  const odd = withAnalysis({ state: "LATER", reason: null });
  assert.equal(odd.state, "MODEL_ERROR", "모르는 상태는 모델 출력을 믿지 않는 쪽이다");
  assert.deepEqual(odd.hypotheses, []);
});

test("근거 부족: 모델이 부족한 것을 적었거나 남은 가설이 없으면 그 사실을 적는다", () => {
  const noted = withAnalysis({ insufficient: "응답 p95가 수집되지 않아 지연 원인을 가를 수 없습니다." });
  assert.equal(noted.hypotheses.length, 1, "부족 사유가 있어도 남은 가설은 그대로다");
  assert.equal(lackingReason(noted), "응답 p95가 수집되지 않아 지연 원인을 가를 수 없습니다.");
  const empty = withAnalysis({ hypotheses: [] });
  assert.equal(lackingReason(empty), "근거를 인용한 원인 가설이 없습니다");
  const blank = withAnalysis({ insufficient: "  " });
  assert.equal(blank.insufficient, null, "빈 칸은 부족 사유가 아니다");
  assert.equal(lackingReason(blank), null);
});

test("인용: 모르면 null이고 0으로 채우지 않는다. 경보·이벤트·부하 판정은 글자이고 모르는 종류는 버린다", () => {
  const a = withAnalysis({
    hypotheses: [{
      cause: "가설",
      citations: [
        { id: "signal.gc", kind: "signal", label: "GC 정지 비율", unit: "ratio", value: null, before: null, change: null, at: null },
        { id: "signal.bad", kind: "signal", label: "형식 밖 값", unit: "ratio", value: "abc", before: "", change: "0.1", at: null },
        { id: "alert.5f3c", kind: "alert", label: "AppTierCpuHigh", unit: null, value: "critical", at: "2026-09-28T03:10:00.000Z" },
        { id: "event.1", kind: "event", label: "앱 계층 대수", unit: null, value: "2→3", at: "2026-09-28T03:12:00.000Z" },
        { id: "load", kind: "load", label: "부하 판정", unit: null, value: "TRANSIENT", at: "2026-09-28T03:15:00.000Z" },
        { id: "log.3f9a", kind: "log", label: "PSQLException · OrderGateway", unit: "count", value: "12", at: "2026-09-28T03:14:59.000Z" },
        { id: "db.1", kind: "database", label: "모르는 종류", unit: "count", value: "1" },
        { id: "signal.x", kind: "signal", label: "모르는 단위", unit: "percent", value: "7" },
      ],
    }],
  });
  const cs = a.hypotheses[0].citations;
  assert.deepEqual(cs.map((c) => c.id), ["signal.gc", "signal.bad", "alert.5f3c", "event.1", "load", "log.3f9a", "signal.x"], "모르는 종류(database)는 버린다");
  assert.deepEqual([cs[0].value, cs[0].before, cs[0].change], [null, null, null]);
  assert.deepEqual([cs[1].value, cs[1].before, cs[1].change], [null, null, 0.1]);
  assert.deepEqual([cs[2].value, cs[2].text], [null, "critical"]);
  assert.equal(cs[3].text, "2→3");
  assert.equal(cs[6].unit, null, "모르는 단위는 단위 없음이다");

  const parts = cs.map(citationParts);
  assert.equal(parts[0].value, "값 없음");
  assert.equal(parts[1].before, null, "이전 값이 없으면 변화도 적지 않는다");
  assert.deepEqual(parts[2], { severity: "critical", value: "장애", before: null, change: null, atWord: "시작" });
  assert.equal(parts[3].value, "2→3");
  assert.deepEqual([parts[4].value, parts[4].atWord], ["일시적 부하", "판정"]);
  assert.deepEqual([parts[5].value, parts[5].atWord], ["12건", "마지막"]);
  assert.equal(parts[6].value, "7");
  assert.deepEqual(citationParts(cite({ kind: "alert", text: "other" })), { severity: "critical", value: "등급 모름", before: null, change: null, atWord: "시작" });
});

test("인용 값의 단위: 초는 ms나 초, 바이트는 크기, 비율의 변화는 %p이고 줄어든 변화는 ▼, 변화 없음은 ±0이다", () => {
  assert.deepEqual(citationParts(cite({ kind: "signal", unit: "seconds", value: 0.184, before: 0.12, change: 0.064 })), {
    severity: null, value: "184ms", before: "120ms", change: "▲64ms", atWord: null,
  });
  assert.equal(unitValue(2.4, "seconds"), "2.4초");
  assert.equal(unitValue(1_048_576, "bytes"), "1MB");
  assert.equal(unitValue(0.0004, "ratio"), "0.04%");
  assert.equal(unitValue(0.012, "ratio"), "1.2%");
  assert.equal(unitValue(0.05, "per_second"), "0.05건/초");
  assert.equal(citationParts(cite({ kind: "node", unit: "ratio", value: 0.4, before: 0.45, change: -0.05 })).change, "▼5%p");
  assert.equal(citationParts(cite({ kind: "bot", unit: "per_second", value: 30, before: 30, change: 0 })).change, "±0");
  assert.equal(citationParts(cite({ kind: "signal", unit: "bytes", value: 2048, before: 4096, change: -2048 })).change, "▼2KB");
});

test("부하 판정 특징: 일시적 부하는 지난 초과의 합을, 부하 아님은 초과 없음을, 판단 불가는 모르는 것을 적는다", () => {
  const base = ANALYSIS.load;
  const transient = withAnalysis({ load: { ...base, pattern: "TRANSIENT", ai_pattern: null, value: "0.42", above_for_seconds: 0, above_total_seconds: 90, excursions: 2, request_correlation: "0.41" } });
  assert.deepEqual(loadFacts(transient.load as NonNullable<CauseAnalysis["load"]>), ["앱 계층 CPU 42%", "기준 50%", "15분 안 초과 2회", "초과 합 1분 30초", "요청률 상관 0.41"]);
  assert.equal(transient.load?.aiPattern, null);

  const none = withAnalysis({ load: { ...base, pattern: "NONE", value: "0.31", above_for_seconds: 0, above_total_seconds: 0, excursions: 0, request_correlation: null } });
  assert.deepEqual(loadFacts(none.load as NonNullable<CauseAnalysis["load"]>), ["앱 계층 CPU 31%", "기준 50%", "15분 안 초과 없음"]);

  const unknown = withAnalysis({ load: { pattern: "UNKNOWN", value: null, threshold: null, above_for_seconds: 0, excursions: null, window_seconds: 900, request_correlation: null, coverage: "0.4" } });
  assert.deepEqual(loadFacts(unknown.load as NonNullable<CauseAnalysis["load"]>), ["앱 계층 CPU 미수집", "기준 미설정", "수집 표본 40%"]);

  const odd = withAnalysis({ load: { ...base, pattern: "SPIKY", ai_pattern: "SPIKY" } });
  assert.equal(odd.load?.pattern, "UNKNOWN", "모르는 규칙 판정은 판단 불가다");
  assert.equal(odd.load?.aiPattern, null);
});

test("오류 로그 READY: 건수, 묶음, 수집 실패 노드를 옮기고 AI 발견은 group_id가 같은 묶음에 붙인다", () => {
  const { c } = board(null);
  const d = ready<LogDesk>(c.logs, "logs");
  assert.equal(d.polledAt, "2026-09-28T03:15:02.000Z");
  assert.equal(d.windowMinutes, 15);
  assert.deepEqual([d.errors, d.warnings, d.more], [12, 3, 0]);
  assert.deepEqual(d.failed, [], "붙은 원천과 꺼진 조회 전용 노드(OFF)는 실패로 적지 않는다");
  const slow = ready<LogDesk>(logsOf({ ...LOGS, sources: [...(LOGS.sources ?? []), { node: "chaos-api-4", state: "UNAVAILABLE", reason: "timeout" }] }), "logs");
  assert.deepEqual(slow.failed, [{ node: "chaos-api-4", reason: "시간 초과" }], "수집에 실패한 노드만 적는다");
  // 모르는 필드(frame, total)는 옮기지 않는다
  assert.deepEqual(d.groups, [{
    id: "3f9a0c21b7e4d5a6", level: "ERROR", logger: "OrderGateway", exception: "PSQLException",
    exceptionMessage: "Connection to [주소] refused.", message: "주문 접수 실패: 저널 제출 예외", count: 12,
    firstSeen: "2026-09-28T03:05:11.000Z", lastSeen: "2026-09-28T03:14:59.000Z", nodes: ["chaos-api-1", "chaos-api-2"],
    finding: { summary: "주문 접수가 DB에 닿지 못해 실패했습니다.", cause: "주 DB 전환 중 연결 거부로 보입니다. 같은 창의 DB 역할 변경 기록과 대조해야 합니다." },
  }]);
  assert.deepEqual(d.analysis, {
    state: "READY", reason: null, analyzedAt: "2026-09-28T03:15:40.000Z", model: "gpt-oss:20b",
    summary: "두 앱 노드에서 DB 연결 거부 오류가 10분 동안 이어졌습니다.",
  });
  // 처음 시각은 앱이 뜬 뒤 처음이라 다른 날이면 날짜를 붙인다(수집 시각의 KST 날짜로 가른다)
  assert.equal(seenWord(d.groups[0].firstSeen, d.polledAt), "12:05:11");
  assert.equal(seenWord("2026-09-26T01:00:00.000Z", d.polledAt), "9.26 10:00");
  assert.equal(seenWord(null, d.polledAt), "미확인");
});

test("오류 로그 AI 발견: 묶음이 없는 발견은 버리고, 발견이 없는 묶음과 모델이 실패한 분석은 발견을 싣지 않는다", () => {
  const data = LOGS.data as NonNullable<WireLogSection["data"]>;
  const warn = { id: "8c4f1d20a93be571", level: "WARN", logger: "LeaderLeaseService", exception: null, message: "리더십 상실", count: 1, first_seen: null, last_seen: "2026-09-28T03:14:38.306Z", nodes: ["chaos-api-1"] };
  const findings = [...(data.analysis?.findings ?? []), { group_id: "ffffffffffffffff", summary: "없는 묶음", cause: "없음" }];
  const two = ready<LogDesk>(logsOf({ ...LOGS, data: { ...data, groups: [...(data.groups ?? []), warn], analysis: { ...data.analysis, findings } } }), "logs");
  assert.deepEqual(two.groups.map((g) => [g.level, g.finding === null]), [["ERROR", false], ["WARN", true]]);
  assert.equal(two.groups[1].exception, null);
  assert.equal(two.groups[1].firstSeen, null);

  const failed = ready<LogDesk>(logsOf({ ...LOGS, data: { ...data, analysis: { ...data.analysis, state: "MODEL_ERROR", reason: "timeout" } } }), "logs");
  assert.deepEqual(failed.analysis, { state: "MODEL_ERROR", reason: "시간 초과", analyzedAt: "2026-09-28T03:15:40.000Z", model: "gpt-oss:20b", summary: null });
  assert.equal(failed.groups[0].finding, null, "실패한 분석의 발견은 싣지 않는다");

  const off = ready<LogDesk>(logsOf({ ...LOGS, data: { ...data, analysis: null } }), "logs");
  assert.equal(off.analysis, null, "LLM 설정이 없거나 AI 정지 중이면 묶음만 싣는다");
  assert.equal(off.groups[0].finding, null);
});

test("오류 로그 칸 상태: 묶음 0개는 READY이고, 첫 수집 전과 모든 원천 실패는 불러오기 실패, 설정 없음과 응답에 없음은 원천 연결 전이다", () => {
  // 계약 한 판 예시(학교 AWS, 로그 없음)
  const quiet = ready<LogDesk>(logsOf({
    state: "READY", polled_at: "2026-09-24T08:59:40.000Z", window_minutes: 15,
    sources: [{ node: "admin", state: "READY", reason: null }],
    data: { errors: 0, warnings: 0, groups: [], more: 0, analysis: null },
  }), "logs");
  assert.deepEqual([quiet.errors, quiet.warnings, quiet.groups.length, quiet.failed.length], [0, 0, 0, 0]);

  const first = logsOf({ state: "UNAVAILABLE", reason: "not_polled", polled_at: null, sources: [] });
  assert.equal(first.state, "UNAVAILABLE");
  assert.ok(first.state === "UNAVAILABLE" && first.reason === "아직 읽지 않음" && /errorlog/.test(first.source));
  const down = logsOf({ state: "UNAVAILABLE", reason: "timeout", polled_at: "2026-09-28T03:15:02.000Z", sources: [{ node: "chaos-api-1", state: "UNAVAILABLE", reason: "timeout" }] });
  assert.ok(down.state === "UNAVAILABLE" && down.reason === "시간 초과");

  const unset = logsOf({ state: "NOT_CONNECTED", source: "error_log" });
  assert.ok(unset.state === "NOT_CONNECTED" && unset.source === "앱 노드 오류 로그(exchange.capacity.nodes의 /actuator/errorlog) 설정 없음");
  const other = logsOf({ state: "NOT_CONNECTED", source: "log_source" });
  assert.ok(other.state === "NOT_CONNECTED" && other.source === "log_source 설정 없음", "모르는 원천 이름은 그대로 적는다");
  const old = board(null, null).c.logs;
  assert.ok(old.state === "NOT_CONNECTED" && /서버 응답에 없음/.test(old.source), "이 칸을 싣지 않는 서버");
  const absent = absentBoard().logs;
  assert.ok(absent.state === "NOT_CONNECTED" && /overview 404/.test(absent.source), "관제 모듈이 없는 배포");
});

test("sim: 원인 분석과 오류 로그를 지어내지 않는다. 원인 가설 한 줄은 그대로 두고 오류 로그는 서버 전용 원천 연결 전이다", () => {
  const T0 = new Date(2026, 8, 24, 12, 0, 0).getTime();
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", T0);
  const c = sim.snapshot(T0 + 20_000);
  const desk = ready<AiDesk>(c.ai, "ai");
  assert.equal(desk.analysis, null);
  assert.ok(desk.hypothesis !== null && desk.hypothesis.length > 0, "시뮬레이터의 원인 가설 한 줄은 남는다");
  assert.deepEqual(c.logs, { state: "NOT_CONNECTED", source: SIM_LOG_SOURCE });
  assert.match(SIM_LOG_SOURCE, /관리 서버 전용/);
});
