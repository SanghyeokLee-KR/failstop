"use client";

/**
 * 단계 막대(디자인 시스템 §12-15 StageBar). 사건 생애(다섯), 호출 단계(넷), 변경 요청(다섯), 시나리오 단계가 같은 부품을
 * 쓴다. 칸은 같은 폭이고 16px 원과 뒤를 지나는 2px 선이다. 지난 단계는 채운 원, 지금 단계는 크롬 악센트 테두리와 sunken
 * 고리, 오지 않은 단계는 빈 원, 건너뛴 단계는 점선 원과 짧은 말이다. 진행 중인 사건의 지금 단계만 주의 색 고리(urgent)다.
 *
 * 순서 있는 목록이고 지금 단계에 aria-current="step"을 둔다. 지난 선은 가로 배율로 늘고 새 지금 단계에 링 펄스가 한 번
 * 선다. 움직임 줄이기에서는 곧바로 서고 3px 정적 고리가 1.8초 선다(CSS). 뜻은 형태와 글이 싣는다. 칸이 좁으면 이름을 두
 * 줄까지 두고, 그래도 모자라면 번호만 두고 이름은 툴팁과 접근 이름이 싣는다(컨테이너 쿼리).
 */

import type { CSSProperties, ReactNode } from "react";
import type { StageMark } from "@/lib/admin/opsDetail";
import { hm } from "@/lib/admin/timeText";
import { useChangeFlags } from "./useChangeFlags";
import StatusShape from "./StatusShape";
import styles from "./StageBar.module.css";

const STATE_WORD: Record<StageMark["state"], string> = {
  done: "완료",
  current: "지금 단계",
  todo: "앞 단계 대기",
  skipped: "건너뜀",
  failed: "실패",
};

/** 칸 아래 둘째 줄. 지난 단계는 시각(과 보조 말), 나머지는 보조 말이다 */
function whenText(m: StageMark): string {
  switch (m.state) {
    case "done":
      return m.at ? `${hm(m.at)}${m.note ? ` · ${m.note}` : ""}` : m.note ?? "";
    case "current":
      return m.note ?? "대기";
    case "skipped":
      return m.note ?? "없음";
    case "failed":
      return m.note ?? "실패";
    default:
      return m.note ?? "";
  }
}

export default function StageBar({ items, label, urgent = false, renderWhen, plan = false }: {
  items: StageMark[];
  /** 목록의 접근 이름('사건 생애 단계') */
  label: string;
  /** 지금 단계가 실제 주의 상태인가(진행 중인 사건). 고리를 주의 색으로 칠한다 */
  urgent?: boolean;
  /** 둘째 줄을 바꿔 그린다(다음 호출까지 남은 시간처럼 매초 바뀌는 글). null이면 기본 글이다 */
  renderWhen?: (m: StageMark) => ReactNode | null;
  /**
   * 진행이 없는 정책 보기(설정의 호출 단계). 모든 칸이 같은 빈 원이고 선을 채우지 않으며, 상태어('앞 단계 대기')를 읽지 않고
   * 이름과 보조 말만 읽는다. 칸의 상태는 todo로 넘긴다
   */
  plan?: boolean;
}) {
  const current = items.findIndex((m) => m.state === "current");
  // 선은 지난(또는 실패한) 마지막 칸까지 채운다. 그 앞에서 건너뛴 칸은 지나간 칸이라 함께 채우고, 끝에 남은 건너뛴 칸
  // (확인으로 멈춘 호출 단계)은 오지 않은 칸이라 채우지 않는다. 칸의 가운데를 잇는 선이라 칸 수에서 하나를 뺀 몫이다
  const reached = items.reduce((a, m, i) => (m.state === "done" || m.state === "failed" ? i : a), 0);
  const span = Math.max(1, items.length - 1);
  const style = { "--stage-n": items.length, "--stage-progress": reached / span } as CSSProperties;
  // 지금 단계가 옮겨 간 순간에만 링 펄스를 건다. 처음 그린 단계는 전이가 아니다
  const pulse = useChangeFlags({ cur: current >= 0 ? items[current].key : "" }, 1800).cur;
  return (
    <div className={styles.wrap}>
      <ol className={styles.bar} aria-label={label} style={style} data-plan={plan ? "" : undefined}>
        {items.map((m, i) => {
          const custom = renderWhen?.(m) ?? null;
          const text = whenText(m);
          return (
            <li
              key={m.key}
              className={styles.stage}
              data-state={m.state}
              data-ai={m.ai ? "" : undefined}
              data-urgent={m.state === "current" && urgent ? "" : undefined}
              data-pulse={i === current && pulse ? "" : undefined}
              aria-current={m.state === "current" ? "step" : undefined}
            >
              <span className={styles.dot} aria-hidden="true">
                {m.state === "failed" && <StatusShape kind="square" size={8} />}
              </span>
              {m.marks.length > 0 && (
                <span className={styles.marks} aria-hidden="true">
                  {m.marks.map((x) => (
                    <span key={x.label} className={styles.mark} data-ai={x.ai ? "" : undefined} title={`${x.label} ${hm(x.at)}`} />
                  ))}
                </span>
              )}
              <span className={styles.name} title={m.label} aria-hidden="true">
                {m.label}
              </span>
              <span className={styles.no} aria-hidden="true" title={m.label}>
                {i + 1}
              </span>
              <span className={`${styles.when} num`} aria-hidden="true">
                {custom ?? text}
              </span>
              <span className="sr-only">
                {m.label}
                {plan ? "" : ` · ${STATE_WORD[m.state]}`}
                {text && m.state !== "current" ? ` · ${text}` : m.note ? ` · ${m.note}` : ""}
                {m.marks.map((x) => ` · ${x.label} ${hm(x.at)}`).join("")}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
