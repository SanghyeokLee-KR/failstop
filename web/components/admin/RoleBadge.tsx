/**
 * 운영자 역할 배지. 색 단독 표현 금지 원칙에 따라 라벨 텍스트로 의미를 전달한다.
 * 헬스·시세 색 도메인과 무관한 중립 뱃지(브랜드 약틴트).
 */

import type { AdminRole } from "@/lib/admin/types";
import { ROLE_LABEL } from "@/lib/admin/roles";
import styles from "./admin.module.css";

export default function RoleBadge({ role }: { role: AdminRole }) {
  return (
    <span className={styles.roleBadge} data-super={role === "SUPER" || undefined}>
      {ROLE_LABEL[role]}
    </span>
  );
}
