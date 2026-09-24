/**
 * 무대 머리 줄 맨 왼쪽의 출처. 관제 값 모드는 관제센터와 같은 한 판에서 옮긴 값이라 그 사실을 중립 칩으로 적고,
 * 재생 중에는 관제 판과 다른 세계라 '재생 · 관제 값과 별개' 표식을 크게 둔다(디자인 시스템 §12-12).
 * 실측 꼴('● 실측 · N초 전', 지연이면 ▲)은 백엔드 값을 연결할 때 그 데이터 원천과 함께 만든다(관리자 설계 7-10 (i) 2).
 */

import { SimMark } from "@/components/admin/ui";
import type { FlowMode } from "@/lib/admin/flowRun";
import styles from "./flow.module.css";

export default function SourceChip({ mode }: { mode: FlowMode }) {
  if (mode === "replay") return <SimMark kind="replay" />;
  return (
    <span className={styles.sourceChip}>
      관제 판 값
      <span className="sr-only"> · 관제센터와 같은 한 판에서 옮긴 값입니다</span>
    </span>
  );
}
