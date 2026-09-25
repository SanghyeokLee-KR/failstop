"use client";

/**
 * 3라운드 화면의 뼈대(관리자 설계 4-10). 사이드바 항목, 주소, 화면 머리만 먼저 세웠고 본문은 그 절의 계약으로
 * 구현한다. 머리의 출처 표식은 다른 관제 화면처럼 관제 판을 따른다
 */

import { useOpsCenter } from "@/lib/admin/opsCenter";
import adminStyles from "../admin.module.css";
import StatusShape from "../StatusShape";
import OpsHead from "./OpsHead";
import styles from "./control.module.css";

export default function ScreenPending({ title, section }: { title: string; section: string }) {
  const { data } = useOpsCenter();
  return (
    <div className={adminStyles.page}>
      <OpsHead title={title} center={data} />
      <section className={styles.planned} aria-label={`${title} 준비 상태`}>
        <span className={styles.plannedTitle}>
          <span aria-hidden="true">
            <StatusShape kind="dashed" />
          </span>
          화면 준비 중
        </span>
        <p className={styles.lead}>관리자 설계 {section}의 계약으로 구현합니다. 아직 값과 조작이 없습니다.</p>
      </section>
    </div>
  );
}
