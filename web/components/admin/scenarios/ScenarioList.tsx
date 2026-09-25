"use client";

/**
 * 시나리오 목록(관리자 설계 4-10-6). 320px 칸이 본문 스크롤에서 위에 붙는다. 행마다 제목, 질문 한 줄, 길이, 표지(관제 판에 걸 수
 * 있음, Patroni 적용 가정, AWS 지표 정책 가정, AI 조치 없음)다. 위아래 화살표로 고르고 고른 행은 사이드바 활성 항목과 같은 왼쪽
 * 2px 선과 sunken 면이다(aria-current).
 */

import type { KeyboardEvent } from "react";
import { SCENARIOS, TAG_WORD } from "@/lib/admin/scenarios";
import { mmss } from "@/lib/admin/timeText";
import type { ScenarioId } from "@/lib/admin/types";
import StatusShape from "../StatusShape";
import styles from "./scenarios.module.css";

export default function ScenarioList({ picked, onPick }: { picked: ScenarioId; onPick: (id: ScenarioId) => void }) {
  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    e.preventDefault();
    const i = SCENARIOS.findIndex((s) => s.id === picked);
    const next = SCENARIOS[(i + (e.key === "ArrowDown" ? 1 : SCENARIOS.length - 1)) % SCENARIOS.length];
    onPick(next.id);
    e.currentTarget.querySelectorAll<HTMLButtonElement>("button")[SCENARIOS.indexOf(next)]?.focus();
  };
  return (
    <aside className={styles.listPanel} aria-labelledby="sc-list">
      <div className={styles.listHead}>
        <h2 id="sc-list" className={styles.listTitle}>
          시나리오
        </h2>
        <span className={`${styles.listMeta} num`}>{SCENARIOS.length}개 · 재생 세계</span>
      </div>
      <div className={styles.list} role="group" aria-label="시나리오 목록 · 위아래 화살표로 고름" onKeyDown={onKey}>
        {SCENARIOS.map((s) => (
          <button key={s.id} type="button" className={styles.row} aria-current={s.id === picked ? "true" : undefined} onClick={() => onPick(s.id)}>
            <span className={styles.rowTitle}>{s.title}</span>
            <span className={`${styles.rowTime} num`}>{mmss(s.durationSec * 1000)}</span>
            <span className={styles.rowQuestion}>{s.question}</span>
            {s.tags.length > 0 && (
              <span className={styles.rowTags}>
                {s.tags.map((t) => (
                  <span key={t} className={styles.tag} data-tag={t}>
                    {t === "LIVE" && <StatusShape kind="play" size={8} />}
                    {TAG_WORD[t]}
                  </span>
                ))}
              </span>
            )}
          </button>
        ))}
      </div>
      <p className={styles.listFoot}>재생은 관제센터의 값을 바꾸지 않습니다. 사람 몫은 재생 대본이고 콘솔이 실행한 일이 아닙니다</p>
    </aside>
  );
}
