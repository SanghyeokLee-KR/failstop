"use client";

/**
 * 서비스 흐름의 사건 기록. 재생 단계와 리스 만료, 증설 권고가 일어난 순서를 시각과 함께 짧게 보인다.
 *
 * 추이 차트는 지표 관제(/admin/monitor)로 옮겼다. 이 화면에는 구성도만 남기므로 무엇이 언제
 * 일어났는지는 이 목록이 말한다. 눈에 보이는 목록은 최근 8건만 최신순으로 둔다. 구성도 아래에서
 * 스크롤 없이 한눈에 읽히는 양이고, 더 긴 흐름은 지표 관제의 사건 표식이 맡는다.
 *
 * 사용자가 화면의 목록을 빼기로 했다(2026-09-23). visible 을 끄면 눈에 보이는 목록 없이 보조기술용
 * 두 가지만 남긴다. 사건은 구성도의 색과 선으로만 보이므로, 그 변화를 말로 알릴 곳이 여기뿐이다.
 *
 * 보조기술에는 두 가지를 더 싣는다. 새 틱의 사건을 정중하게 낭독하는 알림 영역과, 목록보다 긴
 * 최근 50건의 표다. 재생을 여러 번 돌려도 표가 끝없이 길어지지 않게 상한을 둔다.
 */

import { HEALTH_ICON } from "@/components/admin/ui";
import type { FlowEvent, Health } from "@/lib/admin/flowSim";
import styles from "./flow.module.css";

/** 눈에 보이는 목록의 길이 */
const VISIBLE_ROWS = 8;
/** 보조기술용 사건 표의 상한 */
const MAX_TABLE_ROWS = 50;

/** 형태 기호는 aria-hidden 이라 판정은 이 말로 읽힌다 */
export const HEALTH_WORD: Record<Health, string> = { success: "정상", warning: "주의", danger: "장애" };

const pad = (n: number) => String(n).padStart(2, "0");

/** 벽시계 초를 이 기기의 시각 HH:MM:SS 로 적는다 */
export function clock(sec: number): string {
  const d = new Date(sec * 1000);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export interface EventLogProps {
  /** 주행의 사건 전체. 오래된 것부터 쌓인다(flowSim) */
  events: FlowEvent[];
  /** 틱 0 의 벽시계 초. 사건 시각은 origin + at 이다 */
  origin: number;
  /** 눈에 보이는 목록을 그릴지. 끄면 알림 영역과 보조기술용 표만 남는다 */
  visible?: boolean;
}

export default function EventLog({ events, origin, visible = true }: EventLogProps) {
  // 사건 배열은 한 주행 안에서 뒤에만 붙는다. 그래서 절대 위치가 행의 열쇠로 안정하다.
  // 초기화하면 새 주행이 0 부터 다시 세므로 부모가 주행 열쇠로 이 목록을 새로 세운다
  const from = Math.max(0, events.length - VISIBLE_ROWS);
  const recent = events
    .slice(from)
    .map((e, k) => ({ e, i: from + k }))
    .reverse();

  // 같은 틱에 사건 둘이 겹치면(재생 첫 단계와 권고) 마지막 하나만 읽히지 않게 그 틱의 사건을 모두 싣는다
  const lastEvent = events[events.length - 1];
  const announce = lastEvent
    ? events
        .filter((e) => e.at === lastEvent.at)
        .map((e) => `${HEALTH_WORD[e.health]} ${e.label}`)
        .join(", ")
    : "";
  const tableRows = events.slice(-MAX_TABLE_ROWS);

  return (
    <div className={styles.log}>
      {!visible ? null : recent.length === 0 ? (
        <p className={styles.logEmpty}>사건 없음 · 노드를 눌러 재생 선택</p>
      ) : (
        <ol className={styles.logList} aria-label={`최근 사건 ${recent.length}건 · 최신순`}>
          {recent.map(({ e, i }) => (
            <li key={i} className={styles.logRow}>
              <span className={`${styles.logTime} num`}>{clock(origin + e.at)}</span>
              {/* 색 하나로만 알리지 않도록 형태 기호를 함께 둔다. 기호는 장식이라 판정어를 따로 싣는다 */}
              <span className={styles.logShape} data-health={e.health} aria-hidden="true">
                {HEALTH_ICON[e.health]}
              </span>
              <span className="sr-only">{HEALTH_WORD[e.health]}</span>
              <span className={styles.logLabel}>{e.label}</span>
            </li>
          ))}
        </ol>
      )}

      <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {announce}
      </p>
      {/* 가림 상자를 표 바깥에 둔다. 표는 1px 높이를 무시하고 행 높이만큼 커져서, 표에 바로 걸면 그 상자가
          본문 스크롤 높이를 늘린다. 한 화면에 맞춘 서비스 흐름에서 세로 스크롤이 생기던 이유다 */}
      <div className="sr-only">
        <table>
          <caption>재생 사건 · 최근 {MAX_TABLE_ROWS}건</caption>
          <thead>
            <tr>
              <th scope="col">시각</th>
              <th scope="col">사건</th>
            </tr>
          </thead>
          <tbody>
            {tableRows.length === 0 ? (
              <tr>
                <td colSpan={2}>사건 없음</td>
              </tr>
            ) : (
              tableRows.map((e, i) => (
                <tr key={`${e.at}-${i}`}>
                  <td>{clock(origin + e.at)}</td>
                  <td>
                    {HEALTH_WORD[e.health]} {e.label}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
