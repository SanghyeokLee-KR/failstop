/**
 * DB 복제·승격 문구(관리자 설계 4-4·4-2). http 원천은 관제 판과 흐름 조회가 싣는 수집값이다. 복제 방식은 Primary 가 보는
 * pg_stat_replication.sync_state(동기 상대가 있으면 SYNC, 비동기 상대만 있으면 ASYNC)이고, 승격 방식은 Patroni 지표(있으면
 * AUTO, 일시 정지면 MANUAL)다. 모르면(null) 지어내지 않고 미수집·미확인이라 적는다. sim 판은 수집값을 싣지 않아(undefined)
 * 시뮬레이터가 그리는 ADR 0024 구성(비동기 복제, 수동 승격) 문구 그대로다. http 화면의 DB 역할 이름은 주 DB·예비 DB다
 * (2026-09-27 사용자 요청, 영상 자막과 같은 말). API 필드 값(PRIMARY·STANDBY)과 코드 식별자는 그대로다
 */

import type { DbPromotion, ReplicationSync } from "./types";

/** 복제 방식. DB 묶음 머리와 흐름 복제 선의 말이다. 엄격 동기는 Grafana 복제 방식 칸과 같은 말이다 */
export function replicationWord(mode: ReplicationSync | null | undefined): string {
  if (mode === undefined) return "비동기 복제";
  if (mode === "SYNC") return "엄격 동기 복제";
  if (mode === "ASYNC") return "비동기 복제";
  return "복제 방식 미수집";
}

/** 예비 DB 쪽 한 줄. sim 판은 원래 문구(Standby) 그대로다 */
export function standbyWord(mode: ReplicationSync | null | undefined): string {
  if (mode === undefined) return "Standby · 비동기 복제";
  if (mode === "SYNC") return "예비 DB · 엄격 동기";
  if (mode === "ASYNC") return "예비 DB · 비동기 복제";
  return "예비 DB · 복제 방식 미수집";
}

/** 승격 방식. Patroni 지표가 없으면 수동이라 단정하지 않고 미확인이다 */
export function promotionWord(promotion: DbPromotion | null | undefined): string {
  if (promotion === undefined) return "승격은 수동";
  if (promotion === "AUTO") return "자동 승격(Patroni)";
  if (promotion === "MANUAL") return "수동 승격(Patroni 일시 정지)";
  return "승격 방식 미확인";
}
