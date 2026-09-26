"use client";

/**
 * 운영 관제 사이드바의 상태 배지와 AI 모드 표시. 관제 판 폴러(opsCenter)를 구독하므로 운영 관제
 * 셸에서만 그린다. 배지는 형태와 색을 함께 쓰고(§12-3) 정상이면 그리지 않는다.
 *
 * 배지는 두 벌을 함께 그린다. 펼친 메뉴는 '제안 2'처럼 모양·이름표·수를 싣고, 접힌 아이콘 기둥은 8px 모양 하나만
 * 아이콘 오른쪽 위 모서리에 얹는다(§12-9 v1.14). 기둥 폭 72px에서 글자 배지는 아이콘을 덮고 목록 상자에 잘렸다.
 * 접힌 기둥의 건수는 화면 낭독용 글과 운영자 바의 경보 종이 싣는다.
 */

import type { ReactNode } from "react";
import type { OpsBadgeKind } from "@/lib/admin/nav";
import { badgeOf } from "@/lib/admin/navBadges";
import { useOpsPick, useOpsPickShallow } from "@/lib/admin/opsCenter";
import StatusShape from "../StatusShape";
import { AiMark, remainWord, useClock } from "./parts";
import styles from "./control.module.css";

export function NavBadge({ kind }: { kind: OpsBadgeKind }) {
  // 판은 매초 새로 오지만 배지 글자는 드물게 바뀐다. 글자가 같으면 다시 그리지 않는다
  const b = useOpsPickShallow((c) => (c ? badgeOf(kind, c) : null));
  if (!b) return null;
  return (
    <span className={styles.navBadge} data-tone={b.tone} data-bare={b.label === null && b.count === null ? "" : undefined}>
      <span className={styles.navBadgeInk} data-badge-part="ink" aria-hidden="true">
        {b.shape && <StatusShape kind={b.shape} size={8} />}
        {b.label && <span>{b.label}</span>}
        {b.count !== null && <span className="num">{b.count}</span>}
      </span>
      <span className={styles.navBadgeMark} data-badge-part="mark" aria-hidden="true">
        <StatusShape kind={b.shape ?? "circle"} size={8} />
      </span>
      <span className="sr-only">{b.sr}</span>
    </span>
  );
}

/**
 * 사이드바 바닥의 AI 모드. 자동이면 남은 시간을 모드 줄과 같은 꼴('1시간 59분 남음', §12-12)로, 정지면 주의 모양으로
 * 적는다. 모드 줄과 같은 박자(1초)로 읽어야 분이 바뀌는 순간에 두 자리가 다른 값을 보이지 않는다
 */
export function AiModeFoot() {
  const section = useOpsPick((c) => c?.ai ?? null);
  const now = useClock();
  const ai = section?.state === "READY" ? section.data : null;
  const auto = ai?.mode === "AUTO" && ai.auto !== null;
  const stopped = ai?.mode === "STOPPED";
  let text: ReactNode = "AI 연결 전";
  if (stopped) {
    text = (
      <>
        <span aria-hidden="true"><StatusShape kind="triangle" /> </span>AI <b>정지</b> · 판단 멈춤
      </>
    );
  } else if (ai && auto && ai.auto) {
    text = (
      <>
        AI <b>자동</b> · {remainWord(Date.parse(ai.auto.until) - now)} 남음
      </>
    );
  } else if (ai) {
    text = (
      <>
        AI <b>수동</b> · 실행은 승인
      </>
    );
  }
  return (
    <div className={styles.aiFootNav} data-mode={stopped ? "STOPPED" : auto ? "AUTO" : "MANUAL"} title="AI 운영 모드">
      <AiMark size={14} />
      <span className={styles.aiFootNavText}>{text}</span>
    </div>
  );
}
