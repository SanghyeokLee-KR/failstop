import { judgmentAt } from "./opsDetail";
import type { AiJudgment, Incident, IncidentEvent } from "./types";

/** 참조가 있으면 최근 판단 목록 밖의 기록도 상세 API에서 읽을 수 있다. */
export function incidentJudgmentId(event: IncidentEvent, history: AiJudgment[]): string | null {
  if (event.decisionId) return event.decisionId;
  // 서버 기록은 참조가 없으면 연결을 추측하지 않는다. 기존 시뮬레이터만 시각으로 연결한다.
  if (event.decisionId === null) return null;
  return judgmentAt(history, event.at)?.id ?? null;
}

/** 기록을 모아 검토를 돕는다. 경보 해제나 승인만으로 조치 효과를 판정하지 않는다. */
export function incidentReview(inc: Incident, events: IncidentEvent[]) {
  const own = events.filter((e) => e.incidentId === inc.id)
    .sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  const judgments = own.filter((e) => e.kind === "AI_JUDGED");
  const actions = own.filter((e) => ["APPROVED", "ACTED", "AUTO_ACTED", "REVERTED", "AUTO_REVERTED"].includes(e.kind));
  const observations = own.filter((e) => e.kind === "OBSERVED");
  const clearRecordedAt = own.find((e) => e.kind === "RESOLVED")?.at ?? null;
  const followUp = inc.title.startsWith("ReadTierCpuHigh") ? [
    "동일한 조회량에서 조치 전후 CPU·응답 p95·429·5xx를 비교합니다.",
    "노드 정상 합류와 요청 분산을 확인하고, 부하 종료로 줄어든 수치와 구분합니다.",
  ] : [
    "경보를 일으킨 지표와 조치 뒤 관측값을 같은 조건에서 비교합니다.",
    "서비스 영향·재발 여부와 추가 조치 필요성을 확인합니다.",
  ];
  if (actions.some((e) => e.kind === "REVERTED" || e.kind === "AUTO_REVERTED")) {
    followUp.push("되돌림 기록이 있습니다. 되돌린 이유와 이후 상태를 확인합니다.");
  }
  return { judgments, actions, observations, clearRecordedAt, followUp };
}
