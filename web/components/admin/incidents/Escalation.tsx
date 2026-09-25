"use client";

/**
 * 단계별 호출의 조각(관리자 설계 4-10-2). 경보 행의 호출 칩, 사건 목록의 다음 호출 남은 시간, 사건 판과 드로어의 호출 단계
 * 막대다. 모두 opsDetail.escalationOf 한 함수에서 나와 같은 사건이면 어느 자리에서나 같은 단계를 적는다. 호출 원천이 없으면
 * (http) 칩과 남은 시간을 그리지 않고 막대 자리는 원천 연결 전이다.
 */

import { ESCALATION_LEVEL_WORD, escalationOf, type EscalationView } from "@/lib/admin/opsDetail";
import { mmss } from "@/lib/admin/timeText";
import type { EscalationStep, Incident, IncidentEvent } from "@/lib/admin/types";
import StageBar from "../StageBar";
import { StateChip } from "../ui";
import { NotConnected, useClock } from "../control/parts";

/** 사건의 호출 단계. 사건이나 원천이 없으면 null이다 */
export function escalationFor(inc: Incident | null, policy: EscalationStep[] | null, events: IncidentEvent[]): EscalationView | null {
  return inc ? escalationOf(inc, policy, events) : null;
}

/** 경보 행과 사건 목록의 호출 칩. 보낸 호출이 있을 때만 선다. 확인 전이면 주의, 확인·해제로 멈췄으면 무채다 */
export function EscalationChip({ view }: { view: EscalationView | null }) {
  if (view === null || view.level === 0) return null;
  const word = `${ESCALATION_LEVEL_WORD[view.level]} 호출`;
  const sent = view.steps.find((s) => s.key === `l${view.level}`);
  const title = sent?.at ? `${word} · ${sent.note ?? ""}` : word;
  return (
    <span title={title}>
      <StateChip tone={view.paging ? "warning" : "neutral"} shape={view.paging ? "triangle" : "ring"} label={view.paging ? word : `${word} · 멈춤`} />
    </span>
  );
}

/** 다음 호출까지 남은 시간. 매초 바뀌는 글자라 이 조각만 시계를 탄다. 때가 지났는데 아직 기록이 없으면 '호출 중'이다 */
export function NextPage({ view, prefix = true }: { view: EscalationView | null; prefix?: boolean }) {
  const now = useClock();
  if (view === null || view.next === null) return null;
  const left = view.next.at - now;
  const word = ESCALATION_LEVEL_WORD[view.next.level];
  if (left <= 0) return <>{word} 호출 중</>;
  return (
    <>
      {prefix ? `${word} 호출까지 ` : ""}
      <b className="num">{mmss(left)}</b>
    </>
  );
}

/** 호출 단계 막대. 첫 알림과 정책의 단계(1차 5분, 2차 15분, 책임자 30분)다. 지금 단계의 둘째 줄은 남은 시간이다 */
export function EscalationBar({ view, source }: { view: EscalationView | null; source: string }) {
  if (view === null) return <NotConnected what="호출 기록" source={source} compact />;
  return (
    <StageBar
      items={view.steps}
      label="호출 단계"
      urgent={view.next !== null}
      renderWhen={(m) => (m.state === "current" ? <NextPage view={view} prefix={false} /> : null)}
    />
  );
}
