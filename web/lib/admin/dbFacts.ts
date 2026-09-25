/**
 * 데이터베이스 화면의 사실과 판독(관리자 설계 4-10-3). 복제 방식과 전환 방식은 두 데이터 원천에서 같은 사실이라 여기
 * 한곳에 두고, DB 통계(연결 수, 초당 트랜잭션, 캐시 적중, WAL 위치, DB 크기)만 원천마다 다르다. sim은 관제 시뮬레이터의
 * 예시 값이고 http는 원천 연결 전(GET /admin/v1/msp/database, 3라운드 계약 초안)이다.
 */

import type { DbStats, FailoverMode, ReplicationMode } from "./types";

/**
 * 지금 구성. 비동기 복제와 수동 승격이다(ADR 0024). Patroni 엄격 동기 자동 전환(ADR 0036)은 다른 작업 브랜치에서
 * 설계·구현 중이라 이 저장소에는 번호만 있고 이 환경에 적용하지 않았다
 */
export const DB_TOPOLOGY: { mode: ReplicationMode; failover: FailoverMode } = { mode: "ASYNC", failover: "PATRONI_BUILDING" };

/** 느린 질의 원천. 학교 DB에 pg_stat_statements를 적재하지 않아 두 원천 모두 원천 연결 전이다 */
export const SLOW_QUERY_SOURCE = "pg_stat_statements 미적재(학교 DB) · 적재 뒤 postgres_exporter 명명 질의로 읽음";

/** http의 DB 통계 원천. 서버 경로가 3라운드 계약 초안이다 */
export const DB_STATS_SOURCE = "postgres_exporter 명명 질의(GET /admin/v1/msp/database · 3라운드 계약 초안)";

const HI = 2 ** 32;

/** WAL 위치 바이트를 PostgreSQL LSN 꼴('3/1C4A8F20')로 적는다 */
export function lsnOf(bytes: number): string {
  const b = Math.max(0, Math.floor(bytes));
  return `${Math.floor(b / HI).toString(16).toUpperCase()}/${(b % HI).toString(16).toUpperCase()}`;
}

/** LSN 꼴을 바이트로 읽는다. 꼴이 아니면 null이다 */
export function lsnBytes(lsn: string): number | null {
  const m = /^([0-9A-Fa-f]{1,8})\/([0-9A-Fa-f]{1,8})$/.exec(lsn.trim());
  if (!m) return null;
  return parseInt(m[1], 16) * HI + parseInt(m[2], 16);
}

/** Primary WAL 위치와 Standby 재생 위치의 차이(바이트). 두 위치 중 하나라도 읽지 못하면 null이다 */
export function walGap(s: Pick<DbStats, "wal">): number | null {
  if (s.wal === null) return null;
  const a = lsnBytes(s.wal.primaryLsn);
  const b = lsnBytes(s.wal.standbyReplayLsn);
  return a === null || b === null ? null : Math.max(0, a - b);
}

/** 바이트를 사람이 읽는 크기로 적는다(1KB는 1024바이트). DB 크기와 WAL 차이가 같은 꼴을 쓴다 */
export function bytesWord(n: number): string {
  if (n < 1024) return `${Math.round(n)}B`;
  const kb = n / 1024;
  if (kb < 1024) return `${Math.round(kb)}KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${Math.round(mb)}MB`;
  return `${(mb / 1024).toFixed(1)}GB`;
}
