"use client";

/**
 * 판단 순서 푸터. 이 화면에서 무엇을 먼저 보고 다음에 어디로 가는지를 화면이 스스로 말한다.
 *
 * Grafana 관제 화면이 바닥에 같은 형식을 박아 두고 있다(04 DB 관제의 "진단 순서 · SQL 부하
 * → 세션 대기·차단 → 복제 지연"). 그 언어를 콘솔이 이어받는다. 화면을 처음 보는 운영자가
 * 어디부터 봐야 하는지 몰라 헤매는 시간이 장애 대응에서 가장 먼저 새는 시간이다.
 *
 * 계기가 아니라 안내라 발화하지 않는다. 무채로 눕히고 색을 쓰지 않는다.
 * 단계 사이 화살표는 CSS가 비지 않은 단계 사이에만 그린다. 단계로 넘긴 요소가 null을 그려도
 * 화살표가 겹치지 않는다.
 */

import type { ReactNode } from "react";
import styles from "./admin.module.css";

export default function DecisionFooter({ steps }: { steps: ReactNode[] }) {
  return (
    <div className={styles.decisionFooter}>
      {steps.map((s, i) => (
        <span key={i} className={styles.decisionStep}>
          {s}
        </span>
      ))}
    </div>
  );
}
