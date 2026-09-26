/**
 * 리스 행(S4 설계 4-17, API 설계 §6-C `leases[]`). 관제센터 거래 요약 띠의 매칭 리더에 자원·보유 노드·epoch·남은 시간·마켓 수를
 * 자원마다 낸다. P1·P2는 matching-engine 한 행이고 파티션 리더 모드(P3)는 두 행이다.
 *
 * 서버가 주지 않은 남은 시간은 "미측정"으로 적는다. 없는 값을 0초로 적으면 만료 직전처럼 읽힌다.
 */

import type { HaLease } from "./types";

export interface LeaseRow {
  resource: string;
  /** 보유 노드. holder UUID 앞 8자다. 아무도 쥐지 않았으면 "없음" */
  holder: string;
  epoch: number;
  /** 남은 시간 문구. 살아 있으면 초 단위, 만료면 "만료", 서버가 주지 않았으면 "미측정" */
  remaining: string;
  marketCount: number;
  alive: boolean;
}

export function leaseRows(leases: readonly HaLease[] | undefined): LeaseRow[] {
  if (!leases) return [];
  return leases.map((l) => ({
    resource: l.resource,
    holder: l.holder ? l.holder.slice(0, 8) : "없음",
    epoch: l.epoch,
    remaining: !l.leaderAlive ? "만료" : l.leaseRemainingMs === null ? "미측정" : `${(l.leaseRemainingMs / 1000).toFixed(1)}초`,
    marketCount: l.markets.length,
    alive: l.leaderAlive,
  }));
}
