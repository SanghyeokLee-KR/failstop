"use client";

/**
 * Grafana 딥링크. 추이는 이 콘솔이 그리지 않는다.
 *
 * 관측은 Grafana가 소유하고 콘솔은 조작·판정을 소유한다(ADR 0032). 콘솔이 시계열을 다시
 * 그리면 같은 것을 두 번 수집하고, 그린 쪽은 반드시 Grafana보다 못하다. 그래서 값이 왜
 * 그렇게 됐는지 묻는 자리마다 링크를 두고 판단은 그쪽에서 이어 간다.
 *
 * uid를 문자열로 받지 않고 목록으로 좁힌 이유. 오타가 나면 링크가 죽는데 죽은 링크는
 * 눌러 보기 전에는 보이지 않는다. 로컬 프로비저닝에 실재하는 것만 열거한다.
 * 07 디스크·용량(msp-storage)은 학교 배포본에만 있고 로컬에는 없어 여기 없다.
 */

import type { ReactNode } from "react";
import styles from "./admin.module.css";

/** harness/grafana/dashboards 프로비저닝에 실재하는 uid */
export type GrafanaBoard =
  | "msp-overview"
  | "msp-service"
  | "msp-infra"
  | "msp-db"
  | "msp-db-workload"
  | "msp-db-replication"
  | "proof-integrity"
  | "proof-recovery"
  | "proof-load-slo";

const BOARD_TITLE: Record<GrafanaBoard, string> = {
  "msp-overview": "01 서비스 관제",
  "msp-service": "02 트래픽·Spring",
  "msp-infra": "03 AWS·서버",
  "msp-db": "04 DB 관제",
  "msp-db-workload": "05 SQL·세션",
  "msp-db-replication": "06 복제·복구",
  "proof-integrity": "① 체결 무결성 증명",
  "proof-recovery": "② 자동 복구 증명",
  "proof-load-slo": "③ 부하·SLO",
};

/**
 * 관측 스택 주소. 배포마다 다르므로 빌드 타임에 주입한다.
 * 값이 없으면 링크를 렌더하지 않는다. 닿지 않는 주소를 걸어 두면 운영자가
 * 링크를 누르고 나서야 관측이 붙어 있지 않다는 것을 안다.
 */
const BASE = (process.env.NEXT_PUBLIC_GRAFANA_BASE ?? "").replace(/\/+$/, "");

export function grafanaConfigured(): boolean {
  return BASE.length > 0;
}

export default function GrafanaLink({
  board,
  label,
  from = "now-1h",
}: {
  board: GrafanaBoard;
  /** 생략하면 대시보드 이름. 맥락이 있는 자리는 무엇을 보러 가는지로 덮어쓴다 */
  label?: ReactNode;
  from?: string;
}) {
  if (!BASE) return null;
  const href = `${BASE}/d/${board}/?orgId=1&from=${encodeURIComponent(from)}&to=now`;
  return (
    <a className={styles.boardLink} href={href} target="_blank" rel="noreferrer">
      {label ?? BOARD_TITLE[board]}
      <span aria-hidden="true">↗</span>
      <span className="sr-only">새 창에서 관측 대시보드 열기</span>
    </a>
  );
}
