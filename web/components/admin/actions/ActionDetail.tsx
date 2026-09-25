"use client";

/**
 * 조치 상세 모달(720px, 관리자 설계 4-10-4). 원문 코드와 주해, 행위자와 인증 주체, 승인 티켓(발급·소각 시각), 관련 사건과
 * 판단 기록(누르면 그 층), 되돌림 관계(이 조치를 되돌린 기록, 이 조치가 되돌린 기록), 접힌 원문(JSON)이다. 실행 단추는 없다.
 * 되돌림 관계를 누르면 모달 안에서 그 기록으로 옮긴다. 사건과 판단 기록은 운영 관제 셸의 상세 층이라 이 모달을 닫고 연다.
 */

import Link from "next/link";
import { useState } from "react";
import { ACTOR_WORD, CATEGORY_WORD, classify, RESULT_WORD } from "@/lib/admin/actionLog";
import { useOpsDetail } from "@/lib/admin/detailStore";
import { hms, mdhm, mdw } from "@/lib/admin/timeText";
import type { ActionRecord, ApprovalRequest } from "@/lib/admin/types";
import StatusShape from "../StatusShape";
import { Modal } from "../layer/Layer";
import { SimMark } from "../ui";
import detailStyles from "../detail/detail.module.css";
import { ActorMark, ResultMark } from "./ActionTable";
import styles from "./actions.module.css";

const APPROVAL_STATE_WORD: Record<ApprovalRequest["state"], string> = {
  ISSUED: "발급됨 · 실행 대기",
  CONSUMED: "사용됨",
  EXPIRED: "만료",
  REJECTED: "거부",
};

/** 변경 요청·계획 작업 창의 조치는 변경·작업 화면의 그 드로어로 잇는다(sim의 대상 글이 'CHG-n · 제목', 'W-n · 대상'이다) */
function changeLink(r: ActionRecord): { href: string; label: string } | null {
  const chg = /^(CHG-\d+)/.exec(r.target);
  if (r.category === "CHANGE" && chg) return { href: `/admin/changes?id=${chg[1]}`, label: `${chg[1]} 변경 요청` };
  const win = /^(W-\d+)/.exec(r.target);
  if (r.category === "WINDOW" && win) return { href: `/admin/changes?window=${win[1]}`, label: `계획 작업 창 ${win[1]}` };
  return null;
}

export default function ActionDetail({ id, rows, approvals, sim, partial, onClose }: {
  id: string;
  rows: ActionRecord[];
  approvals: ApprovalRequest[];
  sim: boolean;
  /** 받아 온 쪽만 담았는가(http). 관계의 짝이 목록 밖일 수 있다 */
  partial: boolean;
  onClose: () => void;
}) {
  const [cur, setCur] = useState(id);
  const r = rows.find((x) => x.id === cur) ?? null;
  const detail = useOpsDetail.getState();

  if (r === null) {
    return (
      <Modal name="action" width={720} title="조치 기록을 찾지 못했습니다" onClose={onClose}>
        <p className={detailStyles.note}>받아 온 목록에 이 기록이 없습니다. 목록을 다시 읽었거나 더 이전 쪽의 기록입니다.</p>
      </Modal>
    );
  }
  const c = classify(r.action);
  const ticket = r.approvalId ? approvals.find((a) => a.id === r.approvalId) ?? null : null;
  const reverter = r.revertedBy ? rows.find((x) => x.id === r.revertedBy) ?? null : null;
  const reverted = r.revertOf ? rows.find((x) => x.id === r.revertOf) ?? null : null;
  const hasRaw = Object.keys(r.raw).length > 0;
  const change = changeLink(r);
  const jump = (to: ActionRecord) => setCur(to.id);
  const openIncident = (incidentId: string) => {
    onClose();
    detail.openIncident(incidentId);
  };
  const openJudgment = (judgmentId: string) => {
    onClose();
    detail.openJudgment(judgmentId);
  };

  return (
    <Modal
      name="action"
      width={720}
      lead={<ActorMark actor={r.actor} />}
      title={c.gloss}
      badge={<ResultMark result={r.result} chip />}
      sub={
        <>
          <span className="num">
            {mdw(r.at)} {hms(r.at)}
          </span>
          <span>
            {ACTOR_WORD[r.actor]} {r.actorName}
          </span>
          {sim && <SimMark />}
        </>
      }
      onClose={onClose}
    >
      <section className={detailStyles.section} aria-label="조치">
        <dl className={styles.kv}>
          <dt>원문 코드</dt>
          <dd>
            <code className={styles.kvCode}>{r.action}</code>
          </dd>
          <dt>종류</dt>
          <dd>
            {CATEGORY_WORD[r.category]} · {c.gloss}
          </dd>
          <dt>행위자</dt>
          <dd>
            {ACTOR_WORD[r.actor]} · {r.actorName}
          </dd>
          <dt>인증 주체</dt>
          <dd>
            <code className={styles.kvCode}>{r.actorId}</code>
          </dd>
          <dt>대상</dt>
          <dd>
            {r.title ?? r.target}
            {r.title && r.title !== r.target && (
              <>
                {" · "}
                <code className={styles.kvCode}>{r.target}</code>
              </>
            )}
          </dd>
          <dt>결과</dt>
          <dd>{RESULT_WORD[r.result]}</dd>
          {r.requestId && (
            <>
              <dt>요청 id</dt>
              <dd>
                <code className={styles.kvCode}>{r.requestId}</code>
              </dd>
            </>
          )}
        </dl>
      </section>

      <section className={detailStyles.section} aria-label="승인 티켓">
        <div className={detailStyles.sectionHead}>
          <h3 className={detailStyles.sectionTitle}>승인 티켓</h3>
          {r.approvalId && <code className={styles.kvCode}>{r.approvalId}</code>}
        </div>
        {r.approvalId === null ? (
          <div className={detailStyles.empty}>승인 티켓 없이 한 조치입니다</div>
        ) : ticket === null ? (
          <div className={detailStyles.empty}>승인 원장의 받아 온 쪽에 이 티켓이 없습니다</div>
        ) : (
          <dl className={styles.kv}>
            <dt>승인 대상</dt>
            <dd>{ticket.targetLabel}</dd>
            <dt>요청·발급</dt>
            <dd>
              {ticket.requesterName} 요청 · {ticket.approverName} 발급 · <span className="num">{mdhm(ticket.issuedAt)}</span>
            </dd>
            <dt>소각</dt>
            <dd className="num">{ticket.consumedAt ? mdhm(ticket.consumedAt) : APPROVAL_STATE_WORD[ticket.state]}</dd>
            <dt>상태</dt>
            <dd>{APPROVAL_STATE_WORD[ticket.state]}</dd>
          </dl>
        )}
      </section>

      <section className={detailStyles.section} aria-label="관련 기록">
        <div className={detailStyles.sectionHead}>
          <h3 className={detailStyles.sectionTitle}>관련 기록</h3>
        </div>
        {r.incidentId === null && r.judgmentId === null && change === null && r.revertedBy === null && r.revertOf === null ? (
          <div className={detailStyles.empty}>잇는 사건·판단·되돌림 기록 없음</div>
        ) : (
          <ul className={styles.relation}>
            {r.incidentId && (
              <li>
                <span className={styles.relationKey}>사건</span>
                <span>
                  <button type="button" className={styles.linkBtn} aria-haspopup="dialog" onClick={() => openIncident(r.incidentId as string)}>
                    {r.incidentId} 사건 드로어
                  </button>
                </span>
              </li>
            )}
            {change && (
              <li>
                <span className={styles.relationKey}>변경·작업</span>
                <span>
                  <Link href={change.href} className={styles.linkBtn}>
                    {change.label}
                  </Link>
                </span>
              </li>
            )}
            {r.judgmentId && (
              <li>
                <span className={styles.relationKey}>AI 판단 기록</span>
                <span>
                  <button type="button" className={styles.linkBtn} aria-haspopup="dialog" onClick={() => openJudgment(r.judgmentId as string)}>
                    판단 기록 {r.judgmentId}
                  </button>
                </span>
              </li>
            )}
            {r.revertedBy && (
              <li>
                <span className={styles.relationKey}>이 조치를 되돌린 기록</span>
                <span>
                  {reverter ? (
                    <button type="button" className={styles.linkBtn} onClick={() => jump(reverter)}>
                      <StatusShape kind="triangle" size={9} />
                      {classify(reverter.action).gloss} · <span className="num">{mdhm(reverter.at)}</span> · {reverter.actorName}
                    </button>
                  ) : (
                    <code className={styles.kvCode}>{r.revertedBy}</code>
                  )}
                </span>
              </li>
            )}
            {r.revertOf && (
              <li>
                <span className={styles.relationKey}>이 조치가 되돌린 기록</span>
                <span>
                  {reverted ? (
                    <button type="button" className={styles.linkBtn} onClick={() => jump(reverted)}>
                      {classify(reverted.action).gloss} · <span className="num">{mdhm(reverted.at)}</span> · {reverted.actorName}
                    </button>
                  ) : (
                    <code className={styles.kvCode}>{r.revertOf}</code>
                  )}
                </span>
              </li>
            )}
          </ul>
        )}
        {partial && (r.revertedBy || r.revertOf) && <p className={detailStyles.note}>받아 온 쪽 밖의 기록은 id만 적습니다.</p>}
      </section>

      {hasRaw && (
        <details className={detailStyles.raw}>
          <summary>감사 원문(JSON)</summary>
          <pre>{JSON.stringify(r.raw, null, 2)}</pre>
        </details>
      )}
    </Modal>
  );
}
