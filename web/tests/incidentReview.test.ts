import { test } from "node:test";
import assert from "node:assert/strict";
import { eventOf, judgmentOf } from "../lib/admin/httpOps";
import { incidentJudgmentId, incidentReview } from "../lib/admin/incidentReview";
import type { Incident, IncidentEvent } from "../lib/admin/types";

const inc: Incident = { id: "incident-1", title: "ReadTierCpuHigh · capacity", severity: "warning", openedAt: "2026-10-09T16:07:57Z", resolvedAt: "2026-10-09T16:10:42Z", runbookId: null };
const event = (kind: IncidentEvent["kind"], at: string): IncidentEvent => ({ id: kind, kind, at, actor: "SYSTEM", actorName: "system", text: kind, incidentId: inc.id });

test("사건의 명시적 판단 참조는 최근 목록에 없어도 보존하며 시각으로 다른 판단을 고르지 않는다", () => {
  const e = eventOf({ id: 1, kind: "AI_JUDGED", at: "2026-10-09T16:08:39Z", ref: { decision_id: 176, action_id: 10 } }, inc.id);
  assert.ok(e);
  assert.equal(e.actionId, "10");
  assert.equal(incidentJudgmentId(e, []), "176");
  const unrelated = judgmentOf({ id: 177, decided_at: e.at, evidence: {} }, { desired: null, alertNames: new Map() });
  assert.equal(incidentJudgmentId({ ...e, decisionId: null }, [unrelated]), null);
});

test("입력 JSON은 반올림한 표시용 칩과 별개로 당시 수집값·모델·사건을 보존한다", () => {
  const evidence = { collected_at: "2026-10-09T16:08:30Z", read_tier: { avg_cpu: "0.6124" }, metrics: { http_5xx_ratio: { value: "0", collect: "FRESH" } } };
  const j = judgmentOf({ id: 176, model: "recorded-model", incident_id: 12, evidence }, { desired: 2, alertNames: new Map() });
  assert.deepEqual(j.evidenceSnapshot, evidence);
  assert.equal(j.model, "recorded-model");
  assert.equal(j.incidentId, "12");
});

test("해제와 승인은 효과 검증을 대신하지 않고 다른 사건의 관측을 섞지 않는다", () => {
  const events = [event("APPROVED", "2026-10-09T16:09:40Z"), event("RESOLVED", "2026-10-09T16:13:12Z"), { ...event("OBSERVED", "2026-10-09T16:14:00Z"), incidentId: "another" }];
  const review = incidentReview(inc, events);
  assert.equal(review.actions.length, 1);
  assert.equal(review.observations.length, 0);
  assert.equal(review.clearRecordedAt, "2026-10-09T16:13:12Z");
  assert.ok(review.followUp.some((s) => s.includes("부하 종료")));
  assert.equal(inc.postmortem, undefined);
});

test("되돌림과 진행 중 사건도 별도로 확인하며 DB 사건에는 증설 확인 문구를 붙이지 않는다", () => {
  const review = incidentReview({ ...inc, title: "PostgresReplicationLagHigh", resolvedAt: null }, [event("REVERTED", "2026-10-09T16:09:40Z")]);
  assert.equal(review.clearRecordedAt, null);
  assert.ok(review.followUp.some((s) => s.includes("되돌림")));
  assert.ok(review.followUp.every((s) => !s.includes("노드 정상 합류")));
});
