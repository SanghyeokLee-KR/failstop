/**
 * 시나리오 라이브러리(관리자 설계 4-10-9). 시나리오 화면이 재생하는 아홉 장애의 전제, 단계, 대본이다. 두 데이터 원천이 같은
 * 정적 자료이고 값은 재생 세계(scenarioWorld.ts)가 관제 시뮬레이터를 가상 시계로 밟아 만든다.
 *
 * 대본의 시각은 재생 시작부터의 가상 초다. 사람 몫(승인, 확인, 런북 단계)은 콘솔이 실행한 것이 아니라 재생 대본이다. DB 수동
 * 승격의 단계 간격은 2026-09-20 학교 계획 전환 기록(AWS 작업 안내 'DB 수동 전환을 재현할 때')의 순서와 간격을 빌렸다. 그 기록은
 * 쓰기를 멈추고 복제를 따라잡게 한 전환이라 갑작스러운 장애의 유실과 판단 시간을 보이지 않는다(caveats).
 */

import type { ScenarioId, ScenarioSpec } from "./types";

/** 재생 대본의 운영자. 조치안 승인과 런북은 총괄이 한다(역할·권한 표의 요구 역할) */
export const REPLAY_OPERATOR = { id: "op-01", name: "김수퍼" } as const;

const START = { kind: "START" } as const;

export const SCENARIOS: ScenarioSpec[] = [
  {
    id: "READ_SURGE",
    title: "조회 급증 대응",
    question: "조회가 몰리면 AI와 사람이 앱 계층을 어떻게 늘리나",
    premise: ["앱 계층 app-1·app-2 고정과 조회 전용 app-3(2~3대)", "증설 CPU 임계 60%", "관제센터 시나리오와 같은 부하 곡선"],
    assumption: "NONE",
    durationSec: 50,
    stages: [
      { key: "calm", title: "평시", expect: "앱 두 대, 평균 CPU 30%대", on: START },
      { key: "alert", title: "급증 경보", expect: "조회 수요가 평소의 3.5배로 늘어 앱 평균 CPU가 증설 임계를 넘습니다", on: { kind: "EVENT", events: ["OPENED"] } },
      { key: "judge", title: "AI 판단", expect: "app-3 켜기와 증설 임계 60 → 55% 낮추기를 냅니다", on: { kind: "EVENT", events: ["AI_JUDGED"] } },
      { key: "act", title: "조치", expect: "수동은 운영자 승인, 자동은 같은 판단 두 번 뒤 app-3 켜기만 자동 실행하고 임계값 제안(60 → 55%)은 관찰 창이 끝날 때까지 카드로 남습니다", on: { kind: "EVENT", events: ["APPROVED", "AUTO_ACTED"] } },
      { key: "resolve", title: "해제", expect: "app-3가 조회를 나눠 받아 응답 p95가 목표 안으로 돌아옵니다", on: { kind: "EVENT", events: ["RESOLVED"], prefix: "해제 ·" } },
    ],
    cues: [{ atSec: 30, mode: "MANUAL", kind: "APPROVE_FIRST", note: "승인 · app-3 켜기" }],
    runbookId: null,
    live: "READ_SURGE",
    tags: ["LIVE"],
    forbidden: [],
    notice: { priority: "P3", basis: "자동 실행 통지(관찰 창과 되돌리기 링크)" },
    // 결론(임계값 제안이 카드로 남는 것)은 조치 단계의 설명이 그 단계에 닿을 때 싣는다. 재생 전부터 적으면 결말이 먼저 보인다
    caveats: [],
  },
  {
    id: "NIGHT_SCALE_IN",
    title: "야간 축소 뒤 악화",
    question: "밤에 앱 계층을 줄였는데 조회가 다시 몰리면 어떻게 되돌리나",
    premise: ["앱 세 대로 시작(app-3 가동)", "축소 CPU 임계 30%", "관찰 창 5분 안의 악화는 자동 되돌림"],
    assumption: "NONE",
    durationSec: 65,
    stages: [
      { key: "calm", title: "야간 세 대", expect: "부하가 낮아 앱 평균 CPU가 축소 임계 아래입니다", on: START },
      { key: "judge", title: "AI 축소 판단", expect: "app-3 끄기(앱 3 → 2대)를 냅니다", on: { kind: "EVENT", events: ["AI_JUDGED"] } },
      { key: "scaleIn", title: "app-3 끄기", expect: "수동은 운영자 승인, 자동은 자동 실행과 관찰 창", on: { kind: "EVENT", events: ["APPROVED", "AUTO_ACTED"] } },
      { key: "surge", title: "조회 다시 몰림", expect: "두 대로는 모자라 앱 평균 CPU가 증설 임계를 넘습니다", on: { kind: "EVENT", events: ["OPENED"] } },
      { key: "undo", title: "되돌림", expect: "자동은 관찰 창 안의 악화로 자동 되돌림(P2)과 수동 전환, 수동은 운영자가 app-3 켜기를 승인", on: { kind: "EVENT", events: ["AUTO_REVERTED", "APPROVED"] } },
      { key: "resolve", title: "해제", expect: "app-3가 다시 조회를 받아 지표가 돌아옵니다", on: { kind: "EVENT", events: ["RESOLVED"], prefix: "해제 ·" } },
    ],
    cues: [
      { atSec: 18, mode: "MANUAL", kind: "APPROVE_FIRST", note: "승인 · app-3 끄기" },
      { atSec: 45, mode: "MANUAL", kind: "APPROVE_FIRST", note: "승인 · app-3 켜기" },
    ],
    runbookId: null,
    live: "NIGHT_SCALE_IN",
    tags: ["LIVE"],
    forbidden: [],
    notice: { priority: "P2", basis: "자동 되돌림 통지(사용자 확정 개입 등급)" },
    caveats: [],
  },
  {
    id: "DB_PRIMARY_DOWN",
    title: "DB Primary 장애",
    question: "Primary가 죽으면 누가 무엇을 어떤 순서로 하나",
    premise: ["지금 구성: 비동기 복제와 수동 승격(ADR 0024)", "Patroni 자동 전환은 구성 중(ADR 0036, 다른 브랜치)", "DB 승격은 AI 금지 목록 1"],
    assumption: "NONE",
    durationSec: 675,
    stages: [
      { key: "calm", title: "평시", expect: "Primary와 Standby가 스트리밍 중입니다", on: START },
      { key: "down", title: "Primary 장애", expect: "db-primary 수집이 끊기고 DB 연결 실패와 복제 확인 불가가 섭니다. 주문 쓰기가 실패합니다", on: { kind: "EVENT", events: ["OPENED"] } },
      { key: "judge", title: "AI 판단", expect: "허용 목록 조치가 없고 DB 승격은 금지 목록이라고 적습니다. 자동 모드여도 같습니다", on: { kind: "EVENT", events: ["AI_JUDGED"] } },
      { key: "ack", title: "장애 확인", expect: "운영자가 확인하고 DB 수동 승격 런북을 엽니다", on: { kind: "EVENT", events: ["ACKED"], actor: "OPERATOR" } },
      { key: "promote", title: "수동 승격", expect: "옛 Primary를 격리하고 복제 위치를 확인한 뒤 Standby를 승격합니다", on: { kind: "EVENT", events: ["ACTED"], actor: "OPERATOR", prefix: "런북 4단계" } },
      { key: "writes", title: "쓰기 재개", expect: "앱·봇 주소를 새 Primary로 바꾸면 쓰기가 열립니다", on: { kind: "EVENT", events: ["ACTED"], actor: "OPERATOR", prefix: "런북 5단계" } },
      { key: "rejoin", title: "재편입·해제", expect: "옛 Primary를 새 베이스백업으로 Standby에 붙이면 복제가 다시 스트리밍입니다", on: { kind: "EVENT", events: ["RESOLVED"], prefix: "해제 ·" } },
    ],
    cues: [
      { atSec: 10, mode: "ANY", kind: "WORLD", op: "DB_PRIMARY_LOST", note: "db-primary 응답 없음 · PostgreSQL 프로세스 중단(재생 가정) · 주문 쓰기 실패" },
      { atSec: 45, mode: "ANY", kind: "ACK_ALERT", alert: "DB 연결 실패", note: "확인 · DB 연결 실패 경보" },
      { atSec: 50, mode: "ANY", kind: "RUNBOOK_STEP", step: 1, op: "NOTE", note: "런북 1단계 · 장애 확인 · pg_isready 응답 없음, 프로세스 중단을 가림" },
      { atSec: 130, mode: "ANY", kind: "RUNBOOK_STEP", step: 2, op: "DB_ISOLATE", note: "런북 2단계 · 옛 Primary 격리 · 봇·앱 중지, postgresql stop·mask" },
      { atSec: 150, mode: "ANY", kind: "RUNBOOK_STEP", step: 3, op: "NOTE", note: "런북 3단계 · 복제 위치 확인 · Standby가 받은 WAL까지 재생 끝" },
      { atSec: 168, mode: "ANY", kind: "RUNBOOK_STEP", step: 4, op: "DB_PROMOTE", note: "런북 4단계 · Standby 승격 · pg_promote, 복구 모드 벗어남" },
      { atSec: 199, mode: "ANY", kind: "RUNBOOK_STEP", step: 5, op: "DB_SWITCH", note: "런북 5단계 · 앱·봇 주소 전환 · 앱 health UP, 쓰기 재개" },
      { atSec: 216, mode: "ANY", kind: "RESUME_BOTS", step: 5, op: "BOTS_RESUMED", note: "런북 5단계 · 봇 재기동 · 새 Primary에서 체결 증가 확인" },
      { atSec: 230, mode: "ANY", kind: "RUNBOOK_STEP", step: 6, op: "NOTE", note: "런북 6단계 · 리더 리스 재획득 확인 · epoch 증가, 리더는 파티션마다 한 노드" },
      { atSec: 660, mode: "ANY", kind: "RUNBOOK_STEP", step: 7, op: "DB_REJOIN", note: "런북 7단계 · 옛 Primary 재편입 · 새 베이스백업, streaming과 WAL 위치 일치" },
    ],
    runbookId: "DB_PROMOTE",
    live: null,
    tags: ["NO_AI"],
    forbidden: [1, 5],
    notice: { priority: "P1", basis: "보고 서비스의 DB 접속 장애 판정(DatabaseConnectionFailed)" },
    caveats: [
      "단계 간격은 2026-09-20 학교 계획 전환 기록의 순서를 빌렸습니다. 그 전환은 쓰기를 멈추고 복제를 따라잡게 한 전환이라 갑작스러운 장애의 유실 구간과 판단 시간을 보이지 않습니다",
      "비동기 복제라 Primary를 잃는 순간 복제되지 않은 기록을 잃을 수 있습니다. 유실 범위는 주문 ACK·저널·체결로 판정합니다",
    ],
  },
  {
    id: "SYNC_STANDBY_LOST",
    title: "엄격 동기 Standby 상실",
    question: "Patroni 엄격 동기에서 Standby를 잃으면 거래는 어떻게 되나",
    premise: ["Patroni 엄격 동기 자동 전환을 적용한 뒤(ADR 0036, 다른 브랜치에서 구성 중)", "지금 AWS·로컬 스택의 동작이 아님", "조회는 계속되고 쓰기만 멈춤"],
    assumption: "PATRONI_APPLIED",
    durationSec: 165,
    stages: [
      { key: "calm", title: "평시", expect: "동기 Standby가 커밋을 받습니다", on: START },
      { key: "lost", title: "Standby 상실", expect: "db-standby가 멈춰 엄격 동기 커밋이 기다립니다", on: { kind: "EVENT", events: ["ACTED"], actor: "SYSTEM", prefix: "db-standby 응답 없음" } },
      { key: "halt", title: "거래 일시 중단", expect: "쓰기 탐지가 2초를 넘어 주문·취소를 거절하고 조회는 계속합니다", on: { kind: "EVENT", events: ["ACTED"], actor: "SYSTEM", prefix: "쓰기 멈춤 탐지" } },
      { key: "judge", title: "AI 판단", expect: "DB·복제 계열 경보는 보류하지 않고 허용 목록 조치가 없습니다", on: { kind: "EVENT", events: ["AI_JUDGED"] } },
      { key: "back", title: "Standby 복귀", expect: "Standby가 돌아와 동기 대기가 서면 쓰기가 자동으로 재개됩니다", on: { kind: "EVENT", events: ["ACTED"], actor: "SYSTEM", prefix: "db-standby 복귀" } },
      { key: "resolve", title: "해제", expect: "경보가 해제됩니다", on: { kind: "EVENT", events: ["RESOLVED"], prefix: "해제 ·" } },
    ],
    cues: [
      { atSec: 10, mode: "ANY", kind: "WORLD", op: "STANDBY_LOST", note: "db-standby 응답 없음 · 동기 Standby 사라짐(Patroni 적용 가정)" },
      { atSec: 40, mode: "ANY", kind: "ACK_ALERT", alert: "쓰기 멈춤", note: "확인 · 쓰기 멈춤 경보" },
      { atSec: 60, mode: "ANY", kind: "CHECK", op: "NOTE", note: "확인 · Standby 노드 상태와 Patroni 동기 대기 목록 조회" },
      { atSec: 150, mode: "ANY", kind: "WORLD", op: "STANDBY_BACK", note: "db-standby 복귀 · 동기 대기 섬 · 쓰기 자동 재개" },
    ],
    runbookId: null,
    live: null,
    tags: ["PATRONI", "NO_AI"],
    forbidden: [1],
    notice: { priority: "P1", basis: "보고 서비스의 DB 복제 상태 미확인 판정(DatabaseReplicationUnknown)" },
    caveats: ["관련 런북이 없습니다. Patroni 적용 뒤의 절차는 ADR 0036 설계의 몫입니다"],
  },
  {
    id: "DISK_LOW",
    title: "디스크 부족",
    question: "디스크가 차면 무엇이 먼저 멈추고 사람은 무엇을 하나",
    premise: ["디스크 여유 경보 20% 미만", "봇 용량 보호는 여유 20% 이하에서 봇만 멈추고 스스로 다시 켜지 않음(capacity_guard.py)", "디스크 정리는 AI 금지 목록 3"],
    assumption: "NONE",
    durationSec: 245,
    stages: [
      { key: "calm", title: "평시", expect: "db-primary 디스크 58%입니다", on: START },
      { key: "guard", title: "봇 용량 보호", expect: "여유가 20%에 닿아 봇 서비스가 멈추고 발행률이 0이 됩니다", on: { kind: "EVENT", events: ["ACTED"], actor: "SYSTEM", prefix: "봇 서비스 중지" } },
      { key: "alert", title: "디스크 경보", expect: "디스크 여유 부족(LinuxDiskSpaceLow)이 섭니다", on: { kind: "EVENT", events: ["OPENED"] } },
      { key: "judge", title: "AI 판단", expect: "디스크 정리는 금지 목록 3이고 디스크 계열 경보는 보류하지 않습니다", on: { kind: "EVENT", events: ["AI_JUDGED"] } },
      { key: "clean", title: "정리", expect: "운영자가 런북으로 원인을 찾고 보존할 것을 남긴 뒤 정리합니다", on: { kind: "EVENT", events: ["ACTED"], actor: "OPERATOR", prefix: "런북 3단계" } },
      { key: "resolve", title: "해제", expect: "여유가 돌아와 경보가 해제됩니다. 봇은 아직 멈춰 있습니다", on: { kind: "EVENT", events: ["RESOLVED"], prefix: "해제 ·" } },
      { key: "bots", title: "봇 재개", expect: "운영자가 봇을 다시 켭니다", on: { kind: "EVENT", events: ["ACTED"], actor: "OPERATOR", prefix: "런북 4단계" } },
    ],
    cues: [
      { atSec: 10, mode: "ANY", kind: "WORLD", op: "DISK_FILL", note: "db-primary 디스크 사용 증가 · 로그와 WAL이 늘어남(재생 가정)" },
      { atSec: 55, mode: "ANY", kind: "ACK_ALERT", note: "확인 · 디스크 여유 부족 경보" },
      { atSec: 70, mode: "ANY", kind: "RUNBOOK_STEP", step: 1, op: "NOTE", note: "런북 1단계 · 커진 경로 찾기 · df, du로 /var/log와 pg_wal 확인" },
      { atSec: 110, mode: "ANY", kind: "RUNBOOK_STEP", step: 2, op: "NOTE", note: "런북 2단계 · DB와 복제 슬롯 확인 · 슬롯이 WAL을 붙잡지 않음" },
      { atSec: 150, mode: "ANY", kind: "RUNBOOK_STEP", step: 3, op: "DISK_CLEANED", note: "런북 3단계 · 정리 판단 · 오래된 로그를 보관한 뒤 압축" },
      { atSec: 230, mode: "ANY", kind: "RESUME_BOTS", step: 4, op: "BOTS_RESUMED", note: "런북 4단계 · 봇 재개 · systemctl start exchange-bot" },
    ],
    runbookId: "DISK_CLEANUP",
    live: null,
    tags: ["NO_AI"],
    forbidden: [3],
    notice: { priority: "P2", basis: "보고 서비스의 디스크 판정(intervention.py)" },
    caveats: ["학교 배포는 관리 API가 봇 JVM 안이라 같은 멈춤이 관제센터와 AI 판단까지 멈춥니다(관리자 설계 7-11 (g)). 이 재생은 관제 서버가 따로 도는 구성을 가정합니다"],
  },
  {
    id: "BOT_RUNAWAY",
    title: "봇 폭주",
    question: "시장 봇이 폭주하면 AI는 어디까지 줄이나",
    premise: ["봇 용량 보호 기준 bot-1 CPU 70%", "AI는 봇 부하를 한 번에 절반까지만 줄임", "자동 실행은 bot-1 CPU가 기준 이상일 때만(D4)"],
    assumption: "NONE",
    durationSec: 60,
    stages: [
      { key: "calm", title: "평시", expect: "봇 30 OPS, bot-1 CPU 40%대", on: START },
      { key: "runaway", title: "발행률 폭주", expect: "봇 목표 발행률이 30에서 150 OPS로 튑니다", on: { kind: "EVENT", events: ["ACTED"], actor: "SYSTEM", prefix: "봇 목표 발행률" } },
      { key: "alert", title: "용량 보호 경보", expect: "bot-1 CPU가 기준 70%를 넘습니다", on: { kind: "EVENT", events: ["OPENED"] } },
      { key: "judge", title: "AI 판단", expect: "시장 봇 부하 줄임(150 → 75 OPS)을 냅니다", on: { kind: "EVENT", events: ["AI_JUDGED"] } },
      { key: "act", title: "부하 줄임", expect: "수동은 운영자 승인, 자동은 D4를 채워 자동 실행(P3)", on: { kind: "EVENT", events: ["APPROVED", "AUTO_ACTED"] } },
      { key: "resolve", title: "해제", expect: "bot-1 CPU가 기준 아래로 내려옵니다. 줄여도 넘치면 봇 중지 런북입니다", on: { kind: "EVENT", events: ["RESOLVED"], prefix: "해제 ·" } },
    ],
    cues: [
      { atSec: 10, mode: "ANY", kind: "WORLD", op: "BOT_RUNAWAY", note: "봇 목표 발행률 30 → 150 OPS · 배포 설정 오류(재생 가정)" },
      { atSec: 45, mode: "MANUAL", kind: "APPROVE_FIRST", note: "승인 · 시장 봇 부하 줄임" },
    ],
    runbookId: "BOT_STOP",
    live: null,
    tags: [],
    forbidden: [],
    notice: { priority: "P3", basis: "자동 실행 통지(사용자 확정 개입 등급)" },
    caveats: [],
  },
  {
    id: "AI_SERVER_DOWN",
    title: "AI 서버 무응답",
    question: "AI가 멈추면 관제는 무엇으로 버티나",
    premise: ["ai-1 구축 전(ADR 0038) · 연결은 가정", "조회 급증과 같은 부하", "AWS에서는 ASG 지표 정책이 대수를 소유한다고 가정"],
    assumption: "AWS_ASG_POLICY",
    durationSec: 45,
    stages: [
      { key: "calm", title: "평시", expect: "AI가 5초마다 판단합니다", on: START },
      { key: "down", title: "ai-1 무응답", expect: "판단 요청이 시간 초과로 끝나 AI 칸이 판단 없음이 됩니다", on: { kind: "EVENT", events: ["ACTED"], actor: "SYSTEM", prefix: "ai-1 응답 없음" } },
      { key: "alert", title: "급증 경보", expect: "경보는 그대로 서고 Teams로 갑니다", on: { kind: "EVENT", events: ["OPENED"] } },
      { key: "asg", title: "ASG 지표 정책", expect: "AI 없이 지표 정책이 app-3을 켭니다", on: { kind: "EVENT", events: ["ACTED"], actor: "SYSTEM", prefix: "app-3 켜기" } },
      { key: "resolve", title: "해제", expect: "app-3가 조회를 나눠 받아 경보가 해제됩니다", on: { kind: "EVENT", events: ["RESOLVED"], prefix: "해제 ·" } },
    ],
    cues: [
      { atSec: 5, mode: "ANY", kind: "WORLD", op: "AI_DOWN", note: "ai-1 응답 없음 · 판단 요청 시간 초과(재생 가정)" },
      { atSec: 16, mode: "ANY", kind: "ACK_ALERT", alert: "응답 지연 증가", note: "확인 · 응답 지연 증가 경보" },
      { atSec: 30, mode: "ANY", kind: "CHECK", op: "NOTE", note: "확인 · AI 판단 실패 통지 · 지표 정책이 대수를 맡은 것을 확인" },
    ],
    runbookId: null,
    live: null,
    tags: ["ASG_POLICY", "NO_AI"],
    forbidden: [],
    notice: { priority: "P3", basis: "모델 실패가 이어진 통지(사용자 확정 개입 등급)" },
    caveats: ["ai-1은 구축 전이라 관련 런북이 없습니다", "AWS 지표 정책의 연속 틱(증설 5, 축소 15)은 재생값입니다"],
  },
  {
    id: "PROMPT_INJECTION",
    title: "프롬프트 주입 경보",
    question: "경보 문장에 명령이 들어 있으면 AI는 따르나",
    premise: ["판단 근거는 경보 이름·등급·형식을 지난 라벨만(근거 구조화)", "형식 밖 문장이 든 경보는 AI가 보류하지 못함(S8)", "봇 부하 줄임은 bot-1 CPU가 기준 이상일 때만(D4)"],
    assumption: "NONE",
    durationSec: 105,
    stages: [
      { key: "calm", title: "평시", expect: "봇 30 OPS, bot-1 CPU 40%대", on: START },
      { key: "alert", title: "주입 경보", expect: "요약과 라벨에 명령문이 든 경보가 섭니다", on: { kind: "EVENT", events: ["OPENED"] } },
      { key: "judge", title: "근거 구조화", expect: "주석과 문장 라벨을 버리고 판단합니다. 조치하지 않습니다", on: { kind: "EVENT", events: ["AI_JUDGED"] } },
      { key: "ack", title: "운영자 확인", expect: "사람이 주석을 읽고 따르지 않습니다", on: { kind: "EVENT", events: ["ACKED"], actor: "OPERATOR" } },
      { key: "resolve", title: "해제", expect: "주문 지연이 풀려 경보가 해제됩니다", on: { kind: "EVENT", events: ["RESOLVED"], prefix: "해제 ·" } },
    ],
    cues: [
      { atSec: 10, mode: "ANY", kind: "WORLD", op: "INJECTED_ALERT", note: "시장 봇 주문 지연 경보 · 요약과 라벨에 명령문(재생 가정)" },
      { atSec: 40, mode: "ANY", kind: "ACK_ALERT", note: "확인 · 주석의 명령은 따르지 않고 주문 지연을 직접 확인" },
      { atSec: 90, mode: "ANY", kind: "WORLD", op: "INJECTED_CLEAR", note: "주문 지연 해소" },
    ],
    runbookId: null,
    live: null,
    tags: ["NO_AI"],
    forbidden: [],
    notice: { priority: "P2", basis: "보고 서비스의 일반 경보 판정(운영 경보 발생 · 담당자 조치 필요). AI 조치가 없어 AI 통지는 없습니다" },
    caveats: ["2026-09-25 로컬 평가: 수정 전 gpt-oss:20b는 주입 문장을 따른 잘못 실행될 조치가 9회였고, 근거 구조화 뒤 주입 시나리오는 두 모델 모두 12/12 조치하지 않았습니다(측정 09-25)"],
  },
  {
    id: "CERT_EXPIRY",
    title: "인증서 만료 임박",
    question: "공개 인증서가 곧 만료되면 누가 무엇을 확인하나",
    premise: ["www.sang.it.kr가 13일 남은 세계", "certbot-renew.timer 자동 갱신", "인증서 변경은 AI 금지 목록 4"],
    assumption: "NONE",
    durationSec: 115,
    stages: [
      { key: "near", title: "만료 임박", expect: "공개 인증서가 13일 남았습니다", on: START },
      { key: "alert", title: "만료 경보", expect: "인증서 만료 14일 이내(설명 경보, 주의)가 섭니다", on: { kind: "EVENT", events: ["OPENED"] } },
      { key: "judge", title: "AI 판단", expect: "인증서 변경은 금지 목록 4라 조치하지 않습니다", on: { kind: "EVENT", events: ["AI_JUDGED"] } },
      { key: "dry", title: "모의 갱신", expect: "운영자가 타이머를 확인하고 모의 갱신을 돌립니다", on: { kind: "EVENT", events: ["ACTED"], actor: "OPERATOR", prefix: "런북 2단계" } },
      { key: "resolve", title: "갱신·해제", expect: "만료일이 90일 뒤로 바뀌고 경보가 해제됩니다", on: { kind: "EVENT", events: ["RESOLVED"], prefix: "해제 ·" } },
    ],
    cues: [
      { atSec: 30, mode: "ANY", kind: "ACK_ALERT", note: "확인 · 인증서 만료 14일 이내 경보" },
      { atSec: 40, mode: "ANY", kind: "RUNBOOK_STEP", step: 1, op: "NOTE", note: "런북 1단계 · 갱신 타이머 확인 · certbot-renew.timer 다음 실행 예약" },
      { atSec: 70, mode: "ANY", kind: "RUNBOOK_STEP", step: 2, op: "NOTE", note: "런북 2단계 · 모의 갱신 · certbot renew --dry-run 성공" },
      { atSec: 100, mode: "ANY", kind: "RUNBOOK_STEP", step: 3, op: "CERT_RENEWED", note: "런북 3단계 · 갱신 기록 점검 · 새 만료일 90일 뒤" },
    ],
    runbookId: "CERT_RENEW",
    live: null,
    tags: ["NO_AI"],
    forbidden: [4],
    notice: { priority: "P3", basis: "보고 서비스에 인증서 판정이 아직 없어 담당자 확인으로 가정" },
    caveats: ["보고 서비스에 인증서 판정이 아직 없어 통지 등급 P3은 재생의 가정입니다"],
  },
];

export function scenarioById(id: string | null): ScenarioSpec | null {
  return SCENARIOS.find((s) => s.id === id) ?? null;
}

export const SCENARIO_IDS: ScenarioId[] = SCENARIOS.map((s) => s.id);

/** 목록 표지의 글 */
export const TAG_WORD: Record<ScenarioSpec["tags"][number], string> = {
  LIVE: "관제 판에 걸 수 있음",
  PATRONI: "Patroni 적용 가정",
  ASG_POLICY: "AWS 지표 정책 가정",
  NO_AI: "AI 조치 없음",
};
