"use client";

/**
 * 관제 화면 머리. 제목 옆에 환경 배지와 출처 칩을 둔다(§12-9). 머리 높이는 판형의 예약 높이를
 * 그대로 써서 다른 관리 화면과 제목 자리가 같다(레이아웃 §7).
 */

import type { ReactNode } from "react";
import type { ControlCenter } from "@/lib/admin/types";
import adminStyles from "../admin.module.css";
import { EnvBadge, OpsSourceChip } from "./parts";
import styles from "./control.module.css";

export default function OpsHead({ title, desc, center, now, actions }: {
  title: string;
  desc: ReactNode;
  center: ControlCenter | null;
  now: number;
  actions?: ReactNode;
}) {
  return (
    <header className={adminStyles.pageHead}>
      <div>
        <div className={styles.titleRow}>
          <h1 className={adminStyles.pageTitle}>{title}</h1>
          {center && <EnvBadge env={center.environment} />}
          <OpsSourceChip center={center} now={now} />
        </div>
        <p className={styles.headDesc}>{desc}</p>
      </div>
      {actions != null && <div className={styles.headActions}>{actions}</div>}
    </header>
  );
}
