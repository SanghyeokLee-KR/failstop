/**
 * 관제센터 뼈대 로딩(디자인 시스템 §12-4 v1.14, 레이아웃 §7). 판을 기다리는 동안 판과 같은 격자(모드 줄, 상태 요약
 * 다섯 칸, 세 기둥의 머리와 행)로 골격을 세워 판이 들어와도 자리가 움직이지 않게 한다. 띠는 기다리는 동안에만
 * 지나가고 움직임 줄이기면 멈춘 골격이다.
 */

import styles from "./control.module.css";

function Bone({ w, h = 12 }: { w: string | number; h?: number }) {
  return <i className={styles.bone} style={{ width: w, height: h }} />;
}

export default function OpsSkeleton() {
  return (
    <div className={styles.skeleton} aria-busy="true">
      <span className="sr-only">관제 판을 불러오는 중입니다</span>
      <div className={styles.skModeBar} aria-hidden="true">
        <Bone w={112} h={14} />
        <Bone w={132} h={32} />
        <Bone w="38%" />
      </div>
      <div className={styles.summary} aria-hidden="true">
        {Array.from({ length: 5 }, (_, i) => (
          <div key={i} className={styles.skTile}>
            <Bone w="46%" />
            <Bone w="58%" h={28} />
            <Bone w="72%" h={10} />
          </div>
        ))}
      </div>
      <div className={styles.columns} aria-hidden="true">
        {Array.from({ length: 3 }, (_, c) => (
          <div key={c} className={styles.skColumn}>
            <Bone w="34%" h={14} />
            {Array.from({ length: c === 1 ? 3 : 4 }, (_, r) => (
              <div key={r} className={styles.skCard}>
                <Bone w="42%" />
                <Bone w="86%" h={10} />
                <Bone w="64%" h={10} />
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
