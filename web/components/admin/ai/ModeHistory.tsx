"use client";

/**
 * AI 모드·정지 이력(관리자 설계 4-10-2). 시각, 전환, 행위자, 사유, 유효 시간, 승인 티켓이다. 감사 기록의 ops.ai.* 행을
 * 옮기며(aiHistory.ts) 두 원천이 같은 코드를 쓴다. 서버 감사 목록은 사유를 싣지 않아 http의 사유 칸은 비고 그렇게 적는다.
 * 모드를 바꾸는 조작(자동 켜기, 재개, 한도)은 지금의 확인 대화상자와 승인 절차 그대로이고 이 표는 읽기만 한다.
 */

import Link from "next/link";
import type { AdminResource } from "@/components/admin/useAdminResource";
import { aiModeHistory, MODE_CHANGE_WORD, MODE_WORD } from "@/lib/admin/aiHistory";
import { hms, kstDay, mdhm } from "@/lib/admin/timeText";
import type { AdminPage, AiModeRow, AuditEntry } from "@/lib/admin/types";
import { AiMark, PanelHead, Unavailable, useClock } from "../control/parts";
import StatusShape from "../StatusShape";
import controlStyles from "../control/control.module.css";
import styles from "./ai.module.css";

const ACTOR_WORD: Record<AiModeRow["actor"], string> = { OPERATOR: "운영자", AI: "AI", SYSTEM: "시스템" };

function Actor({ row }: { row: AiModeRow }) {
  return (
    <span className={styles.actor} data-actor={row.actor} title={`${ACTOR_WORD[row.actor]} · ${row.actorName}`}>
      {row.actor === "AI" && <AiMark size={11} />}
      <span className={styles.actorName}>{row.actorName}</span>
    </span>
  );
}

export default function ModeHistory({ audit, live }: { audit: AdminResource<AdminPage<AuditEntry>>; live: boolean }) {
  const now = useClock(60_000);
  const today = kstDay(now);
  const rows = audit.data ? aiModeHistory(audit.data.data).slice(0, 12) : [];
  return (
    <section className={controlStyles.panel} aria-labelledby="ai-modes">
      <PanelHead
        id="ai-modes"
        title="모드·정지 이력"
        meta={live ? "감사 목록(ops.ai.*) · 사유는 감사 상세에만 있어 비웁니다" : "감사 기록(ops.ai.*)"}
        actions={
          <Link href="/admin/actions?actor=AI" className={controlStyles.stripLink}>
            조치 기록에서 AI만 보기
          </Link>
        }
      />
      {audit.loading ? (
        <div className={styles.tableSkeleton} aria-busy="true">
          <span className="sr-only">모드·정지 이력을 불러오는 중입니다</span>
          {Array.from({ length: 3 }, (_, i) => (
            <i key={i} className={controlStyles.bone} style={{ width: `${86 - i * 14}%`, height: 12 }} />
          ))}
        </div>
      ) : audit.error ? (
        <Unavailable what="모드·정지 이력" source="감사 로그(GET /admin/v1/audit)" reason={audit.errorReason === "session" ? "세션 만료" : "조회 실패"} compact />
      ) : rows.length === 0 ? (
        <div className={controlStyles.empty}>
          <span className={controlStyles.emptyMark} aria-hidden="true" />
          모드 전환 기록 없음
        </div>
      ) : (
        <table className={styles.table}>
          <thead>
            <tr>
              <th scope="col" data-col="at">시각</th>
              <th scope="col" data-col="change">전환</th>
              <th scope="col" data-col="who">행위자</th>
              <th scope="col">사유</th>
              <th scope="col" data-col="hours" data-num="">유효 시간</th>
              <th scope="col" data-col="ticket">승인 티켓</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.auditId} data-kind={r.kind}>
                <td className="num" data-col="at">
                  {kstDay(Date.parse(r.at)) === today ? hms(r.at) : mdhm(r.at)}
                </td>
                <td>
                  <span className={styles.change}>
                    {(r.kind === "STOP" || r.kind === "SYSTEM") && <StatusShape kind="triangle" tone="warning" className={styles.changeShape} />}
                    <b>{MODE_CHANGE_WORD[r.kind]}</b>
                  </span>
                  <span className={styles.changeSub}>
                    {MODE_WORD[r.from]} → {MODE_WORD[r.to]}
                  </span>
                </td>
                <td>
                  <Actor row={r} />
                </td>
                <td className={styles.reason}>{r.reason ?? <span className={styles.quiet}>{live ? "감사 상세" : "없음"}</span>}</td>
                <td className="num" data-num="">
                  {r.hours === null ? <span className="sr-only">해당 없음</span> : `${r.hours}시간`}
                </td>
                <td className={styles.ticket}>{r.approvalId ? <code title={r.approvalId}>{r.approvalId}</code> : <span className={styles.quiet}>없음</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
