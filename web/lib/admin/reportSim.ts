/**
 * 보고서 화면의 sim 예시(관리자 설계 4-7). 보고 서비스(deploy/reporting)가 만드는 보고의 세 가지 모양을
 * 지어낸 값으로 둔다. 일일 정상, 일일 주의(백업 확인 필요), 사건 보고(AI 자동 조치의 자동 되돌림)다.
 * 문구는 보고 서비스의 공통 표시 모델(presentation.py)과 개입 판정(intervention.py)의 문구를 그대로 쓴다.
 * 화면이 늘 '시뮬레이션'을 표시한다. 시각은 부르는 순간을 기준으로 가장 가까운 지난 09:00과 02:00대다.
 */

import { isoNow } from "../format";
import { kst } from "./timeText";
import type { OpsReport, ReportDesk } from "./types";

const SEC = 1000;
const MIN = 60 * SEC;
const HOUR = 60 * MIN;

/** 이 시각 이전의 가장 가까운 로컬 시:분 */
function lastLocal(now: number, hour: number, minute: number): number {
  const d = new Date(now);
  d.setHours(hour, minute, 0, 0);
  if (d.getTime() > now) d.setDate(d.getDate() - 1);
  return d.getTime();
}

function dayKey(t: number): string {
  const d = new Date(t);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

const at = (t: number) => isoNow(t);

const DEFAULT_IMPACT = "고객 영향 미확인 · 요청 실패 범위·영향 사용자 수 자료 없음";
const GEMINI = "gemini-3.1-flash-lite";

function dailyNormal(t: number): OpsReport {
  return {
    id: `daily:${dayKey(t)}`,
    kind: "daily",
    createdAt: at(t),
    sentAt: at(t + 4 * SEC),
    status: "sent",
    title: "일일 운영 보고",
    transition: "unknown",
    intervention: { code: "monitor", priority: "P4", label: "관찰 유지", reason: "최신 필수 근거·백업 확인 완료, 추가 개입 조건 없음" },
    customer: "exchange",
    environment: "AWS",
    collectedAt: at(t + 2 * SEC),
    windowStart: at(t - 24 * HOUR),
    windowEnd: at(t),
    lead: "일일 관측 현황 · 수집 시점 기준 개입 판정",
    impact: DEFAULT_IMPACT,
    currentState: "수집 시점 진행 경보 0건",
    events: [],
    actions: [
      { priority: "P4", owner: "운영 관측 담당", timing: "다음 정기 수집", task: "경보·지표·예약 백업 상태 관찰", doneWhen: "다음 수집에서 새 경보·누락 여부 확인" },
    ],
    metrics: [
      { label: "지표 수집 응답", value: "7/7개 응답", detail: "수집 연결 응답 · 업무 요청 성공 여부는 별도", status: "observed" },
      { label: "서비스 요청", value: "41.37건/초", detail: "조회 구간의 마지막 표본 합계", status: "observed" },
      { label: "응답 시간 P95", value: "186 ms", detail: "수집 P95의 최대값 · 표본·구간에 따른 변동", status: "observed" },
      { label: "5xx 응답", value: "0.00건/초", detail: "조회 구간의 마지막 표본 합계", status: "observed" },
      { label: "디스크 여유", value: "58.20% 여유", detail: "/ · 구간 마지막 값 중 최소 / 7개 수집 표본 · 수집 구간 최저 57.91%", status: "observed" },
      { label: "최근 백업 성공", value: kst(at(lastLocal(t, 3, 14))), detail: "5.8시간 전 · 백업 성공 기록 · 복원 검증은 별도", status: "observed" },
    ],
    analysis: {
      mode: "gemini",
      note: "Gemini 참고 분석 · 개입 등급은 수집 근거와 운영 기준으로 판정",
      summary:
        "지난 24시간 동안 지표 수집 응답과 업무 요청이 끊기지 않았고 5xx 응답은 관측되지 않았습니다. 응답 시간 P95는 186ms로 목표 안에 있고, 03:14 백업 성공과 복원 확인 기록이 있어 추가 개입 조건은 없습니다.",
      model: GEMINI,
    },
    gaps: [],
    autoAction: "자동 조치 없음",
    links: { pdf: null, html: null },
    attempts: [{ attempt: 1, at: at(t + 4 * SEC), status: "sent", error: null }],
  };
}

function dailyBackupCheck(t: number): OpsReport {
  return {
    id: `daily:${dayKey(t)}`,
    kind: "daily",
    createdAt: at(t),
    sentAt: at(t + 41 * SEC),
    status: "sent",
    title: "일일 운영 보고",
    transition: "unknown",
    intervention: { code: "verify", priority: "P3", label: "담당자 확인 필요", reason: "백업 다음 예약 실행·격리 복원 결과 미확인" },
    customer: "exchange",
    environment: "AWS",
    collectedAt: at(t + 2 * SEC),
    windowStart: at(t - 24 * HOUR),
    windowEnd: at(t),
    lead: "일일 관측 현황 · 수집 시점 기준 개입 판정",
    impact: DEFAULT_IMPACT,
    currentState: "수집 시점 진행 경보 0건",
    events: [],
    actions: [
      {
        priority: "P3",
        owner: "DB 운영 담당",
        timing: "금일 확인 권고",
        task: "백업 파일·다음 예약 실행·격리 복원 결과 대조",
        doneWhen: "예약 활성·다음 실행 시각과 백업 ID에 연결된 복원 성공 기록 확보",
      },
      {
        priority: "P3",
        owner: "관측·서비스 운영 담당",
        timing: "다음 판정 전 확인 권고",
        task: "누락·오래된 항목의 대상·수집 시각·명시된 누락 사유 확인",
        doneWhen: "필수 근거의 최신 표본과 실제 요청 결과 확보",
      },
    ],
    metrics: [
      { label: "지표 수집 응답", value: "7/7개 응답", detail: "수집 연결 응답 · 업무 요청 성공 여부는 별도", status: "observed" },
      { label: "서비스 요청", value: "39.02건/초", detail: "조회 구간의 마지막 표본 합계", status: "observed" },
      { label: "응답 시간 P95", value: "201 ms", detail: "수집 P95의 최대값 · 표본·구간에 따른 변동", status: "observed" },
      { label: "5xx 응답", value: "0.00건/초", detail: "조회 구간의 마지막 표본 합계", status: "observed" },
      { label: "디스크 여유", value: "58.44% 여유", detail: "/ · 구간 마지막 값 중 최소 / 7개 수집 표본 · 수집 구간 최저 58.02%", status: "observed" },
      { label: "최근 백업 성공", value: kst(at(lastLocal(t, 3, 12))), detail: "5.8시간 전 · 백업 성공 기록 · 복원 검증은 별도", status: "observed" },
    ],
    analysis: { mode: "facts-only", note: "수집 근거로 판정 · AI 분석 없음", summary: "", model: null },
    gaps: ["백업 예약 확인: 자료 없음", "백업 복원 확인: 자료 없음"],
    autoAction: "자동 조치 없음",
    links: { pdf: null, html: null },
    attempts: [
      { attempt: 1, at: at(t + 3 * SEC), status: "queued", error: "http_429" },
      { attempt: 2, at: at(t + 41 * SEC), status: "sent", error: null },
    ],
  };
}

/** 야간 축소 뒤 조회가 다시 몰려 응답 지연 경보가 났고, AI 자동 모드가 축소를 되돌린 사건의 해제 보고 */
function incidentAutoRevert(t: number): OpsReport {
  const fired = t + 7 * MIN + 20 * SEC;
  const resolved = t + 14 * MIN + 10 * SEC;
  const created = resolved + 30 * SEC;
  return {
    id: "incident:7c2e91b04a5d",
    kind: "incident",
    createdAt: at(created),
    sentAt: at(created + 3 * SEC),
    status: "sent",
    title: "응답 지연 증가 해제",
    transition: "resolved",
    intervention: { code: "verify", priority: "P3", label: "담당자 확인 필요", reason: "자동 되돌림 뒤 종결 확인 필요" },
    customer: "exchange",
    environment: "AWS",
    collectedAt: at(created - 2 * SEC),
    windowStart: at(created - 15 * MIN),
    windowEnd: at(created),
    lead: `${kst(at(resolved), false)} 경보 해제 · 복구 후속 점검 대상`,
    impact: DEFAULT_IMPACT,
    currentState: "수집 시점 진행 경보 0건",
    events: [{ name: "응답 지연 증가", statusLabel: "해제", target: "exchange-api · 조회 ASG", startedAt: at(fired), endedAt: at(resolved) }],
    actions: [
      {
        priority: "P3",
        owner: "서비스 운영 담당",
        timing: "복구 직후 확인 권고",
        task: "조회 노드 3대 복귀와 응답 P95·5xx 정상화 확인",
        doneWhen: "P95 목표 안 유지와 자동 되돌림 원인·조치 기록",
      },
      {
        priority: "P3",
        owner: "관측·서비스 운영 담당",
        timing: "금일 확인 권고",
        task: "야간 축소 판단 근거(조회 수요 추이)와 조회 축소 임계 30% 검토",
        doneWhen: "축소 임계 유지 또는 변경 결정과 사유 기록",
      },
    ],
    metrics: [
      { label: "지표 수집 응답", value: "7/7개 응답", detail: "수집 연결 응답 · 업무 요청 성공 여부는 별도", status: "observed" },
      { label: "서비스 요청", value: "58.40건/초", detail: "조회 구간의 마지막 표본 합계", status: "observed" },
      { label: "응답 시간 P95", value: "214 ms", detail: "수집 P95의 최대값 · 표본·구간에 따른 변동", status: "observed" },
      { label: "5xx 응답", value: "0.00건/초", detail: "조회 구간의 마지막 표본 합계", status: "observed" },
      { label: "디스크 여유", value: "58.10% 여유", detail: "/ · 구간 마지막 값 중 최소 / 7개 수집 표본", status: "observed" },
    ],
    analysis: {
      mode: "gemini",
      note: "Gemini 참고 분석 · 개입 등급은 수집 근거와 운영 기준으로 판정",
      summary:
        "야간 축소로 조회 노드가 2대가 된 뒤 조회 요청이 다시 늘어 응답 P95가 목표 400ms를 넘었습니다. AI 자동 모드가 관찰 창 안에서 축소를 되돌려 3대로 복귀했고, 해제 뒤 P95는 214ms로 내려왔습니다.",
      model: GEMINI,
    },
    gaps: ["상세 로그: 자료 없음"],
    autoAction: "AI 자동 조치 2건 · 축소 뒤 자동 되돌림",
    links: { pdf: null, html: null },
    attempts: [{ attempt: 1, at: at(created + 3 * SEC), status: "sent", error: null }],
  };
}

export function simReportDesk(now: number): ReportDesk {
  const today = lastLocal(now, 9, 0);
  const night = lastLocal(now, 2, 5);
  const reports = [dailyNormal(today), dailyBackupCheck(today - 24 * HOUR), incidentAutoRevert(night)].sort(
    (a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt),
  );
  const next = new Date(today);
  next.setDate(next.getDate() + 1);
  return {
    reports,
    delivery: { lastDailySentAt: reports.find((r) => r.kind === "daily")?.sentAt ?? null, pending: 0, dead: 0, nextDailyAt: isoNow(next.getTime()) },
    tests: [],
  };
}
