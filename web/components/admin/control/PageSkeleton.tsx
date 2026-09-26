/**
 * 관제 화면의 뼈대 로딩(디자인 시스템 §12-4 뼈대 로딩, 레이아웃 §7 3라운드 판형). 판을 기다리는 동안 그 화면의 격자(요약
 * 칸 수와 두 칸 비율)대로 골격을 세워 판이 들어와도 자리가 움직이지 않게 한다. 관제센터는 제 뼈대(OpsSkeleton)를 쓴다.
 * 띠는 기다리는 동안에만 지나가고 움직임 줄이기면 멈춘 골격이다.
 */

import type { CSSProperties } from "react";
import styles from "./control.module.css";

function Bone({ w, h = 12 }: { w: string | number; h?: number }) {
  return <i className={styles.bone} style={{ width: w, height: h }} />;
}

export default function PageSkeleton({ tiles = 0, columns = [1, 1], rows = [4, 4], label }: {
  /** 요약 칸 수(0이면 없음) */
  tiles?: number;
  /** 두 칸 이상 판의 비율(fr) */
  columns?: number[];
  /** 칸마다 행 수 */
  rows?: number[];
  /** 보조기술이 읽는 글('경보·사건 판을 불러오는 중입니다') */
  label: string;
}) {
  const grid: CSSProperties = { gridTemplateColumns: columns.map((c) => `minmax(0, ${c}fr)`).join(" ") };
  return (
    <div className={styles.skeleton} aria-busy="true">
      <span className="sr-only">{label}</span>
      {tiles > 0 && (
        <div className={styles.summary} data-cols={tiles} aria-hidden="true">
          {Array.from({ length: tiles }, (_, i) => (
            <div key={i} className={styles.skTile}>
              <Bone w="46%" />
              <Bone w="58%" h={28} />
              <Bone w="72%" h={10} />
            </div>
          ))}
        </div>
      )}
      <div className={styles.skGrid} style={grid} aria-hidden="true">
        {columns.map((_, c) => (
          <div key={c} className={styles.skColumn} data-short="">
            <Bone w="34%" h={14} />
            {Array.from({ length: rows[c] ?? 3 }, (_, r) => (
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
