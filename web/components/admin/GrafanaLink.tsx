"use client";

/**
 * Grafana 딥링크. 추이는 이 콘솔이 그리지 않는다.
 *
 * 관측은 Grafana가 소유하고 콘솔은 조작·판정을 소유한다(ADR 0032). 콘솔이 시계열을 다시
 * 그리면 같은 것을 두 번 수집하고, 그린 쪽은 반드시 Grafana보다 못하다. 그래서 값이 왜
 * 그렇게 됐는지 묻는 자리마다 링크를 두고 판단은 그쪽에서 이어 간다.
 *
 * uid 목록은 lib/admin/boards.ts 가 소유한다. 링크를 그리지 않는 자리(시연 대본)도
 * 보드 이름을 쓰므로 데이터를 컴포넌트에서 꺼냈다.
 */

import type { ReactNode } from "react";
import { BOARD_TITLE, type GrafanaBoard } from "@/lib/admin/boards";
import styles from "./admin.module.css";

/* 보드 목록은 lib/admin/boards.ts 로 옮겼다. 여기서 다시 선언하지 않는다 */
export type { GrafanaBoard };

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
