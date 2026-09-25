/**
 * AI 상담 규칙(관리자 설계 4-6). 상담은 조회만 한다. 실행 부탁은 실행하지 않고 절차가 있는 화면으로 안내하며,
 * 답마다 근거와 수집 시각, 모델을 싣는다. AI가 정지 중이면 답하지 않고, http는 서버 계약 전이라 요청을 보내지 않는다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { OpsSim, type OpsActorRef } from "../lib/admin/opsSim";
import {
  answerFromCenter,
  CHAT_MAX_CHARS,
  intentOf,
  linksOf,
  requestedGuide,
  SIM_CHAT_MODEL,
  SUGGESTED_QUESTIONS,
} from "../lib/admin/aiChat";
import { SimAdminApi } from "../lib/admin/simAdmin";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { AdminError, type AdminApi } from "../lib/admin/api";
import type { ReadTier } from "../lib/admin/types";

const OP: OpsActorRef = { id: "op-01", name: "김수퍼" };
const T0 = new Date(2026, 8, 24, 12, 0, 0).getTime();
const at = (sec: number) => T0 + sec * 1000;

async function rejectsWith(run: () => Promise<unknown>, code: string): Promise<void> {
  await assert.rejects(async () => run(), (e: unknown) => e instanceof AdminError && e.code === code);
}

test("실행을 부탁하는 말만 안내로 읽고, 묻는 말은 실행 동사가 있어도 조회로 읽는다", () => {
  assert.equal(requestedGuide("축소해 주세요"), "READ_NODES");
  assert.equal(requestedGuide("조회 노드 하나 늘려줘"), "READ_NODES");
  assert.equal(requestedGuide("app-3 켜 줘"), "READ_NODES");
  assert.equal(requestedGuide("app-3 꺼 주세요"), "READ_NODES");
  assert.equal(requestedGuide("증설 임계를 55로 바꿔 주세요"), "THRESHOLD");
  assert.equal(requestedGuide("자동 모드로 바꿔 주세요"), "MODE");
  assert.equal(requestedGuide("DB 승격해 주세요"), "FORBIDDEN");
  assert.equal(requestedGuide("그 경보 보류해 주세요"), "SILENCE");
  assert.equal(requestedGuide("백업 지금 실행해 주세요"), "BACKUP");
  assert.equal(requestedGuide("왜 축소했나요?"), null);
  assert.equal(requestedGuide("지금 상태를 요약해 주세요"), null);
  assert.equal(requestedGuide("최근 1시간 사건을 알려 주세요"), null);
});

test("추천 질문은 저마다 맞는 뜻으로 읽힌다", () => {
  assert.deepEqual(
    SUGGESTED_QUESTIONS.map((q) => intentOf(q)),
    ["SUMMARY", "READ_CPU", "INCIDENTS", "PENDING", "AI1"],
  );
});

test("추천 질문의 답은 합니다체이고 수집 시각과 모델을 싣는다", () => {
  const sim = new OpsSim(T0);
  const c = sim.snapshot(at(6));
  for (const q of SUGGESTED_QUESTIONS) {
    const r = answerFromCenter(q, c, at(6));
    assert.equal(r.state, "OK", q);
    assert.equal(r.declined, false, q);
    // 끝 문장 뒤에 괄호로 값을 덧붙일 수 있다('…않습니다(app-1 32%).')
    assert.match(r.answer, /니다(\([^)]*\))?\.$/, `${q}: ${r.answer}`);
    assert.equal(r.collectedAt, c.collectedAt);
    assert.equal(r.model, SIM_CHAT_MODEL);
  }
  const s = answerFromCenter("지금 상태를 요약해 주세요", c, at(6));
  assert.ok(s.evidence.length > 0, "요약은 근거를 싣는다");
  for (const e of s.evidence) assert.ok(e.id.length > 0 && e.label.length > 0 && e.value.length > 0);
});

test("조회 급증 중의 CPU 질문은 경보와 조치안을 근거로 들고 관련 화면을 잇는다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const c = sim.snapshot(at(20));
  const r = answerFromCenter("왜 조회 CPU가 높나요?", c, at(20));
  assert.equal(r.state, "OK");
  assert.ok(r.evidence.some((e) => e.kind === "ALERT"), "울린 경보가 근거다");
  assert.ok(linksOf(r).length > 0 && linksOf(r).length <= 4);
});

test("실행 부탁은 실행하지 않고 절차 화면으로 안내하며 대수를 바꾸지 않는다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const c = sim.snapshot(at(20));
  const r = answerFromCenter("조회 노드 하나 늘려 주세요", c, at(20));
  assert.equal(r.declined, true);
  assert.equal(r.guide, "READ_NODES");
  assert.match(r.answer, /^상담은 조회만 하고 조치를 실행하지 않습니다\./);
  assert.ok(r.evidence.some((e) => e.kind === "PROPOSAL"), "승인을 기다리는 조치안을 근거로 보인다");
  assert.deepEqual(linksOf(r).map((l) => l.href).slice(0, 2), ["/admin", "/admin/capacity"], "절차 화면 링크가 앞이다");
  const tier = sim.snapshot(at(21)).readTier as { state: "READY"; data: ReadTier };
  assert.equal(tier.data.desired, 2, "상담은 대수를 바꾸지 않는다");
});

test("AI가 정지 중이면 답하지 않고 정지 상태만 돌려준다", () => {
  const sim = new OpsSim(T0);
  sim.stopAi(OP, at(1));
  const r = answerFromCenter("지금 상태를 요약해 주세요", sim.snapshot(at(2)), at(2));
  assert.equal(r.state, "STOPPED");
  assert.equal(r.answer, "");
  assert.equal(r.evidence.length, 0);
});

test("sim 상담은 조회 권한으로 묻고 빈 질문과 길이 초과는 거절한다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  assert.equal(api.aiChat, "SIM");
  const r = await api.askAi("지금 상태를 요약해 주세요", []);
  assert.equal(r.state, "OK");
  await rejectsWith(() => api.askAi("   ", []), "invalid_parameter");
  await rejectsWith(() => api.askAi("가".repeat(CHAT_MAX_CHARS + 1), []), "invalid_parameter");
});

test("http 상담은 서버 상담 경로에 질문과 최근 대화를 싣고, 서버가 쓴 머리말을 답보다 먼저 싣는다", async () => {
  const prev = globalThis.fetch;
  const sent: { url: string; body: unknown }[] = [];
  globalThis.fetch = (async (url: string, init: RequestInit = {}) => {
    sent.push({ url, body: JSON.parse(String(init.body)) });
    return new Response(
      JSON.stringify({ state: "ANSWERED", reason: null, preface: "근거 일부가 60초보다 오래되었습니다.", answer: "지금 경보는 한 건입니다.", citations: [{ id: "metric.http_5xx_ratio", label: "5xx 비율", value: "0.0004" }], evidence: { collected_at: "2026-09-25T00:00:00.000Z" }, model: "test-model" }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    );
  }) as typeof fetch;
  try {
    const api: AdminApi = new HttpAdminApi("http://admin.test");
    (api as unknown as { session: unknown }).session = { operatorId: "op-1", displayName: "op", roles: ["VIEWER"], token: "t", issuedAt: "", expiresAt: "2999-01-01T00:00:00.000Z" };
    assert.equal(api.aiChat, "LIVE");
    const r = await api.askAi("지금 상태를 요약해 주세요", [{ role: "operator", text: "앞 질문" }, { role: "ai", text: "앞 답" }]);
    assert.deepEqual(sent, [{
      url: "http://admin.test/admin/v1/msp/ai/chat",
      body: { operator_id: "op-1", question: "지금 상태를 요약해 주세요", history: [{ role: "user", content: "앞 질문" }, { role: "assistant", content: "앞 답" }] },
    }]);
    assert.equal(r.state, "OK");
    assert.equal(r.note, "근거 일부가 60초보다 오래되었습니다.");
    assert.deepEqual(r.evidence.map((e) => e.id), ["metric:http_5xx_ratio"]);
    assert.equal(r.declined, false, "요약을 부탁하는 말은 실행 부탁이 아니다");
  } finally {
    globalThis.fetch = prev;
  }
});
