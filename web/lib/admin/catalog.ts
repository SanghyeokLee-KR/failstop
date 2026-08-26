/**
 * lib/admin/catalog.ts. 정적 카탈로그(카오스 실험 E1~E9, 불변식 7종).
 * 관측 값과 무관한 상수라 sim·http 구현이 공유한다(§2-2 정적 데이터 제시).
 */

import type { ChaosExperimentDef } from "./types";

/* 라벨은 한국어 평이어 1차 표기(§4-2 표시층). 원어는 description·title 채널이 전달하고 id·injectPath는 불변 */
export const CHAOS_CATALOG: ChaosExperimentDef[] = [
  { id: "E1", label: "저널 DB 응답 지연", injectPath: "/admin/v1/chaos/network", blastRadius: "저널 DB 연결", description: "PostgreSQL 응답에 지연·지터 주입" },
  { id: "E2", label: "저널 DB 연결 차단", injectPath: "/admin/v1/chaos/network", blastRadius: "저널 DB 연결", description: "PG 연결을 down·timeout으로 차단" },
  { id: "E3", label: "세션 캐시(Redis) 장애", injectPath: "/admin/v1/chaos/network", blastRadius: "세션·캐시 계층", description: "Redis toxic·app 장애 주입" },
  { id: "E4", label: "매칭 리더 강제 종료", injectPath: "/admin/v1/chaos/kill-node", blastRadius: "체결 엔진 리더", description: "리더 노드 강제 종료, failover 유도" },
  { id: "E5", label: "노드 간 네트워크 분할", injectPath: "/admin/v1/chaos/network", blastRadius: "노드 간 링크", description: "split-brain 유도 분할" },
  { id: "E6", label: "폭주 + 리더 종료 복합", injectPath: "/admin/v1/chaos/kill-node", blastRadius: "전 체결 경로", description: "봇 스파이크와 리더 kill 조합(최종 증명)" },
  { id: "E7", label: "리더 일시 정지", injectPath: "/admin/v1/chaos/kill-node", blastRadius: "체결 엔진 리더", description: "리더 일시 정지(pause), 응답 지연 유도" },
  { id: "E8", label: "앱 내부 지연·예외 폭풍", injectPath: "/admin/v1/chaos/app", blastRadius: "애플리케이션 스레드", description: "앱 내부 지연·예외 주입" },
  { id: "E9", label: "커밋 응답 절단", injectPath: "/admin/v1/chaos/network", blastRadius: "커밋 응답 경로", description: "limit_data·reset_peer로 멱등성 증명" },
];

/* label은 릴레이 7등분 1행 셀 폭 예산 내 단문. 도메인 원어는 gloss로 강등해 title 채널이 전달한다 */
export const INVARIANT_DEFS: { name: string; label: string; gloss?: string }[] = [
  { name: "ledger_imbalance", label: "자산 보존", gloss: "원장 균형" },
  { name: "no_duplicate_fill", label: "중복 체결 없음", gloss: "멱등" },
  { name: "sequence_continuity", label: "처리 순번 연속", gloss: "seq 연속성" },
  { name: "order_journal_monotonic", label: "주문 기록 순증", gloss: "저널 단조 증가" },
  { name: "price_time_priority", label: "가격·시간 우선순위", gloss: "price-time priority" },
  { name: "orderbook_consistency", label: "호가 정합성", gloss: "orderbook consistency" },
  { name: "journal_replay_match", label: "재현 검증 일치", gloss: "저널 재현 지문" },
];
