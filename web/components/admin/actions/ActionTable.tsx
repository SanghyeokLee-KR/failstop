"use client";

/**
 * 조치 기록 표(관리자 설계 4-10-4). 열은 시각, 행위자(형태와 이름), 종류, 한국어 주해와 원문 코드, 대상, 결과, 승인 티켓,
 * 사건이다. 행을 누르면 조치 상세 모달이 열린다. 네트워크·보안의 관리 접근 기록(4-10-3)도 같은 행 문법이고 조치 칸의 둘째 줄이
 * 원문 코드 대신 대상이다.
 *
 * 행위자는 이름만 적고 AI만 AI 표지를 앞에 둔다. 상태 모양(●○)을 행위자 머리 기호로 쓰지 않는다(디자인 시스템 §12-10 v1.21).
 * 결과는 성공을 칠하지 않고 잉크 글자로 두며 거부·오류·되돌림만 칩으로 적는다(백오피스 감사 로그와 같다).
 */

import { ACTOR_WORD, CATEGORY_WORD, classify } from "@/lib/admin/actionLog";
import { useOpsDetail } from "@/lib/admin/detailStore";
import { hms, kstDay, mdhm } from "@/lib/admin/timeText";
import type { ActionRecord, OpsActor } from "@/lib/admin/types";
import { StateChip } from "../ui";
import { AiMark } from "../control/parts";
import styles from "./actions.module.css";

export function ActorMark({ actor, name }: { actor: OpsActor; name?: string }) {
  return (
    <span className={styles.actor} data-actor={actor}>
      {actor === "AI" && (
        <span className={styles.actorShape} aria-hidden="true">
          <AiMark size={12} />
        </span>
      )}
      {name !== undefined ? <span title={`${ACTOR_WORD[actor]} · ${name}`}>{name}</span> : <span className="sr-only">{ACTOR_WORD[actor]}</span>}
    </span>
  );
}

/** 결과. 표에서는 성공을 칠하지 않은 잉크 글자로 두고, 상세의 머리(chip)에서는 성공도 칩으로 적어 제목과 한 덩이로 읽히지 않게 한다 */
export function ResultMark({ result, chip = false }: { result: ActionRecord["result"]; chip?: boolean }) {
  if (result === "OK") return chip ? <StateChip tone="success" label="성공" /> : <span className={styles.ok}>성공</span>;
  if (result === "ERROR") return <StateChip tone="warning" label="오류" />;
  if (result === "REVERTED") return <StateChip tone="warning" shape="triangle" label="되돌림" />;
  return <StateChip tone="danger" label="거부" />;
}

/** 시각. 오늘이면 시:분:초, 아니면 날짜와 시:분이다 */
function When({ at, today }: { at: string; today: string }) {
  return <span className={`${styles.time} num`}>{kstDay(Date.parse(at)) === today ? hms(at) : mdhm(at)}</span>;
}

/** 빈 칸. 칸마다 '없음'을 적으면 표가 같은 낱말로 덮여 읽을 값이 묻힌다. 하이픈은 낭독하지 않고 빈 칸이 부재를 전한다 */
function Nil() {
  return (
    <span className={styles.none} aria-hidden="true">
      -
    </span>
  );
}

export default function ActionTable({ rows, now, onOpen, columns = "full", incidentTitle }: {
  rows: ActionRecord[];
  now: number;
  onOpen: (id: string) => void;
  /** full은 조치 기록의 여덟 열, access는 관리 접근 기록의 네 열(시각, 운영자, 조치와 대상, 결과)이다 */
  columns?: "full" | "access";
  /** 사건 칸에 적을 사건 이름. 판에 없는 사건은 null이고 칸은 '사건 보기'다. 원시 id(inc-…)를 칸에 적지 않는다 */
  incidentTitle?: (id: string) => string | null;
}) {
  const today = kstDay(now);
  const openIncident = (id: string) => useOpsDetail.getState().openIncident(id);
  if (columns === "access") {
    return (
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <colgroup>
            <col className={styles.cAt} />
            <col style={{ width: "22%" }} />
            <col />
            <col className={styles.cResult} />
          </colgroup>
          <thead>
            <tr>
              <th scope="col">시각</th>
              <th scope="col">운영자</th>
              <th scope="col">조치</th>
              <th scope="col">결과</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const c = classify(r.action);
              const target = r.title ?? r.target;
              return (
                <tr key={r.id} className={styles.row} onClick={() => onOpen(r.id)}>
                  <td>
                    <When at={r.at} today={today} />
                  </td>
                  <td>
                    <ActorMark actor={r.actor} name={r.actorName} />
                  </td>
                  <td>
                    <button
                      type="button"
                      className={styles.gloss}
                      aria-haspopup="dialog"
                      title={`${c.gloss} · ${r.action}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        onOpen(r.id);
                      }}
                    >
                      {c.gloss}
                    </button>
                    <span className={styles.target} title={target || undefined}>
                      {target || "대상 없음"}
                    </span>
                  </td>
                  <td>
                    <ResultMark result={r.result} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    );
  }
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <colgroup>
          <col className={styles.cAt} />
          <col className={styles.cActor} />
          <col className={styles.cCat} />
          <col style={{ width: "22%" }} />
          <col />
          <col className={styles.cResult} />
          <col className={styles.cTicket} />
          <col className={styles.cIncident} />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">시각</th>
            <th scope="col">행위자</th>
            <th scope="col">종류</th>
            <th scope="col">조치</th>
            <th scope="col">대상</th>
            <th scope="col">결과</th>
            <th scope="col">승인 티켓</th>
            <th scope="col">사건</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const c = classify(r.action);
            const target = r.title ?? r.target;
            return (
              <tr key={r.id} className={styles.row} onClick={() => onOpen(r.id)}>
                <td>
                  <When at={r.at} today={today} />
                </td>
                <td>
                  <ActorMark actor={r.actor} name={r.actorName} />
                </td>
                <td>
                  <span className={styles.cell}>{CATEGORY_WORD[r.category]}</span>
                </td>
                <td>
                  <button
                    type="button"
                    className={styles.gloss}
                    aria-haspopup="dialog"
                    title={c.gloss}
                    onClick={(e) => {
                      e.stopPropagation();
                      onOpen(r.id);
                    }}
                  >
                    {c.gloss}
                  </button>
                  <code className={styles.code} title={r.action}>
                    {r.action}
                  </code>
                </td>
                <td>
                  <span className={styles.cell} title={target}>
                    {target || <Nil />}
                  </span>
                </td>
                <td>
                  <ResultMark result={r.result} />
                </td>
                <td>
                  {r.approvalId ? (
                    <span className={`${styles.ticket} num`} title={`승인 티켓 ${r.approvalId}`}>
                      #{r.approvalId}
                    </span>
                  ) : (
                    <Nil />
                  )}
                </td>
                <td>
                  {r.incidentId ? (
                    <button
                      type="button"
                      className={styles.incident}
                      aria-haspopup="dialog"
                      title={`${incidentTitle?.(r.incidentId) ?? "사건"} · 사건 드로어 열기`}
                      onClick={(e) => {
                        e.stopPropagation();
                        openIncident(r.incidentId as string);
                      }}
                    >
                      {incidentTitle?.(r.incidentId) ?? "사건 보기"}
                    </button>
                  ) : (
                    <Nil />
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
