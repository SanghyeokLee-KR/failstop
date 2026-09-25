"use client";

/**
 * 보고 한 건의 상세와 'Teams로 보낸 카드' 미리보기, 전송 기록(관리자 설계 4-7). 상세는 보고 서비스의
 * 공통 표시 모델(presentation.py)과 같은 정보 구조를 이 콘솔의 판면으로 그린다. 제목과 개입 등급, 보고
 * 기준 시각(KST), 영향, 담당자 조치(담당 역할·착수·할 일·완료 조건), 후속 관측, 참고 분석, 미확인 자료,
 * 자동 조치 여부, PDF·HTML 보고서 순서다. AI 요약은 AI 표지와 모델 이름으로 사람이 정한 판정과 가른다.
 * http 원천의 보고 서비스 읽기 프록시는 목록 요약만 주므로(detail SUMMARY) 본문 자리는 연결 전으로 그리고, 본문은
 * 보고 서비스가 서명한 PDF·HTML로 본다.
 */

import { INTERVENTION_SHAPE, JOB_WORD, KIND_WORD, cardTone, teamsCard } from "@/lib/admin/reports";
import { kst } from "@/lib/admin/timeText";
import type { OpsReport } from "@/lib/admin/types";
import adminStyles from "../admin.module.css";
import { AdminIcon } from "../icons";
import { Modal } from "../layer/Layer";
import StatusShape, { GLYPH_SHAPE } from "../StatusShape";
import { NotConnected } from "../control/parts";
import styles from "./reports.module.css";

/** 초까지 적는 KST 시각. 전송 기록은 초 단위로 가른다 */
function kstSec(iso: string): string {
  const s = new Date(iso).toLocaleTimeString("ko-KR", { timeZone: "Asia/Seoul", hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit" });
  return `${s} KST`;
}

export function ReportDetail({ r, sim, onOpenCard }: { r: OpsReport; sim: boolean; onOpenCard: () => void }) {
  const shape = r.transition === "resolved" ? { shape: "●", tone: "success" as const } : INTERVENTION_SHAPE[r.intervention.code];
  const decisionTone = INTERVENTION_SHAPE[r.intervention.code].tone;
  const job = JOB_WORD[r.status];
  const ai = r.analysis.summary !== "";
  return (
    <article className={styles.body} aria-labelledby="report-title">
      <header className={styles.bodyHead}>
        <div className={styles.headRow}>
          <span className={styles.eyebrow}>
            <span className={styles.kindTag}>{KIND_WORD[r.kind]}</span>
            <span className="num">기록 {r.id}</span>
          </span>
          {/* 보낸 카드는 상세를 읽는 흐름을 끊지 않게 모달로 연다(§12-13의 4) */}
          <button type="button" className={adminStyles.neutralBtn} aria-haspopup="dialog" onClick={onOpenCard}>
            Teams로 보낸 카드
          </button>
        </div>
        <h2 id="report-title" className={styles.reportTitle}>
          {/* 형태는 제목과 같은 글자 단계의 줄 상자에 담아, 제목이 두 줄이 돼도 첫 줄에 선다(§12-2) */}
          <span className={styles.titleShape}>
            <StatusShape kind={GLYPH_SHAPE[shape.shape]} size={12} tone={shape.tone} className={styles.shape} />
          </span>
          <span>{r.title}</span>
        </h2>
        <p className={styles.decision}>
          <b className={styles.toneText} data-tone={decisionTone === "success" ? undefined : decisionTone}>
            {r.intervention.priority} · {r.intervention.label}
          </b>
          <span className={styles.decisionReason}>{r.intervention.reason}</span>
        </p>
      </header>
      <dl className={styles.facts}>
        <div className={styles.fact}>
          <dt>보고 기준</dt>
          <dd className="num">{kst(r.collectedAt)}</dd>
        </div>
        <div className={styles.fact}>
          <dt>수집 구간</dt>
          <dd className="num">
            {kst(r.windowStart, false)} ~ {kst(r.windowEnd, false)}
          </dd>
        </div>
        <div className={styles.fact}>
          <dt>고객 · 환경</dt>
          <dd>{r.customer || r.environment ? `${r.customer} / ${r.environment}` : "관제 서버 설정의 범위"}</dd>
        </div>
        <div className={styles.fact}>
          <dt>Teams 전송</dt>
          <dd>
            <StatusShape kind={GLYPH_SHAPE[job.shape]} tone={job.tone} className={styles.shape} />
            {job.word}
            {r.sentAt && <span className="num"> · {kst(r.sentAt, false)}</span>}
          </dd>
        </div>
      </dl>

      {r.detail === "SUMMARY" ? (
        <section className={styles.section} aria-label="요약">
          {r.lead && <p className={styles.lead}>{r.lead}</p>}
          <NotConnected what="보고 본문" source="보고 서비스 읽기 프록시(GET /admin/v1/msp/reports)는 목록 요약만 줍니다. 영향, 담당자 조치, 후속 관측, 참고 분석은 PDF·HTML 보고서에서 봅니다" compact />
        </section>
      ) : (
        <>
        <section className={styles.section} aria-label="상황과 영향">
          <p className={styles.lead}>{r.lead}</p>
          {r.events.length > 0 && (
            <ol className={styles.events} aria-label="사건 경과">
              {r.events.map((e) => (
                <li key={`${e.name}-${e.startedAt}`} className={styles.event}>
                  <span className={styles.eventState}>{e.statusLabel}</span>
                  <span className={styles.eventName}>
                    {e.name}
                    <small>{e.target}</small>
                  </span>
                  <span className={`${styles.eventTime} num`}>
                    {kst(e.startedAt, false)} 발생
                    {e.endedAt && (
                      <>
                        <br />
                        {kst(e.endedAt, false)} 해제
                      </>
                    )}
                  </span>
                </li>
              ))}
            </ol>
          )}
          <dl className={styles.kv}>
            <dt>영향</dt>
            <dd>{r.impact}</dd>
            <dt>수집 시점</dt>
            <dd>{r.currentState}</dd>
          </dl>
        </section>

        <section className={styles.section} aria-labelledby="report-actions">
          <h3 id="report-actions" className={styles.sectionHead}>
            담당자 조치 <span className={`${styles.sectionMeta} num`}>{r.actions.length}건</span>
          </h3>
          <div className={styles.actions}>
            <div className={`${styles.action} ${styles.actionHead}`} aria-hidden="true">
              <span>순위</span>
              <span>담당 역할 · 착수</span>
              <span>할 일</span>
              <span>완료 조건</span>
            </div>
            {r.actions.map((a) => (
              <div key={`${a.priority}-${a.task}`} className={styles.action}>
                <span className={`${styles.priority} num`}>{a.priority}</span>
                <span className={styles.owner}>
                  <b>{a.owner}</b>
                  <span>{a.timing}</span>
                </span>
                <span className={styles.task}>{a.task}</span>
                <span className={styles.done}>{a.doneWhen}</span>
              </div>
            ))}
          </div>
          <p className={styles.note}>수집 시점의 권고 기준입니다. 담당 역할과 착수 시점은 SLA나 작업 배정이 아닙니다.</p>
        </section>

        <section className={styles.section} aria-labelledby="report-metrics">
          <h3 id="report-metrics" className={styles.sectionHead}>
            후속 관측 <span className={`${styles.sectionMeta} num`}>{kst(r.collectedAt)}</span>
          </h3>
          <div className={styles.metrics}>
            {r.metrics.map((m) => (
              <div key={m.label} className={styles.metric} data-status={m.status}>
                <span className={styles.metricKey}>{m.label}</span>
                <span className={`${styles.metricValue} num`}>
                  {m.status === "attention" && <StatusShape kind="triangle" />}
                  {m.status === "unknown" && <StatusShape kind="dashed" />}
                  {m.value}
                </span>
                <span className={styles.metricDetail}>{m.detail}</span>
              </div>
            ))}
          </div>
        </section>

        <section className={styles.section} aria-labelledby="report-analysis">
          <h3 id="report-analysis" className={styles.sectionHead}>
            참고 분석
            {ai && (
              <>
                <span className={styles.aiMark} aria-hidden="true">
                  <AdminIcon name="ai" size={14} />
                </span>
                <span className={styles.modelTag}>AI 요약 · {r.analysis.model ?? "모델 미확인"}</span>
              </>
            )}
          </h3>
          {ai ? <p className={styles.prose}>{r.analysis.summary}</p> : <p className={styles.prose}>AI 분석 없이 수집 근거로만 판정한 보고입니다.</p>}
          <p className={styles.note}>{r.analysis.note}</p>
        </section>
        </>
      )}

      <footer className={styles.foot}>
        {r.detail === "SUMMARY" ? null : r.gaps.length > 0 ? (
          <details className={styles.gaps}>
            <summary>
              미확인 자료 <b className="num">{r.gaps.length}</b>항목
            </summary>
            <ul>
              {r.gaps.map((g) => (
                <li key={g}>{g}</li>
              ))}
            </ul>
          </details>
        ) : (
          <span>
            미확인 자료 <b className="num">0</b>항목
          </span>
        )}
        {r.detail === "FULL" && (
          <span>
            자동 조치 <b>{r.autoAction}</b>
          </span>
        )}
        <span className={styles.files}>
          {r.links.pdf === null && <span className={styles.filesNote}>{sim ? "예시 보고라 보고 파일이 없습니다" : "보고 파일이 없거나 서명할 수 없습니다"}</span>}
          <FileButton label="PDF 보고서" href={r.links.pdf} />
          <FileButton label="HTML 보고서" href={r.links.html} />
        </span>
      </footer>
    </article>
  );
}

/** 보고 서비스가 서명한 파일 주소. 없으면 §12-6의 비활성 단추로 자리만 남긴다 */
function FileButton({ label, href }: { label: string; href: string | null }) {
  if (href === null) {
    return (
      <button type="button" className={adminStyles.neutralBtn} disabled>
        {label}
      </button>
    );
  }
  return (
    <a className={adminStyles.neutralBtn} href={href} target="_blank" rel="noreferrer">
      {label}
      <span aria-hidden="true"> ↗</span>
      <span className="sr-only">새 창에서 열기</span>
    </a>
  );
}

/**
 * 'Teams로 보낸 카드'(모달 520px, §12-13의 4). 보고 서비스가 보낸 Adaptive Card 본문을 같은 순서로 그린다.
 * Teams의 모양을 흉내 내지 않고 이 콘솔의 판면으로 그린다
 */
export function TeamsCardModal({ r, onClose }: { r: OpsReport; onClose: () => void }) {
  if (r.detail === "SUMMARY") {
    return (
      <Modal name="report-card" width={520} title="Teams로 보낸 카드" onClose={onClose}>
        <NotConnected what="카드 본문" source="보고 서비스 읽기 프록시가 카드 본문을 싣지 않습니다. Teams 채널이나 PDF·HTML 보고서에서 봅니다" />
      </Modal>
    );
  }
  const card = teamsCard(r);
  const tone = cardTone(r).tone;
  const job = JOB_WORD[r.status];
  const last = r.attempts[r.attempts.length - 1] ?? null;
  return (
    <Modal
      name="report-card"
      width={520}
      title="Teams로 보낸 카드"
      sub={
        <>
          <span>보고 서비스가 보낸 본문 · 미리보기</span>
          <span className="num">
            {job.word}
            {last ? ` · ${kstSec(last.at)}` : ""}
          </span>
        </>
      }
      onClose={onClose}
    >
      <div className={styles.canvas}>
        <div className={styles.card} data-tone={tone}>
          {card.blocks.map((b, i) =>
            b.type === "facts" ? (
              <dl key={i} className={`${styles.cardFacts} ${b.separator ? styles.cardSep : ""}`}>
                {b.facts.map((f) => (
                  <div key={f.title} style={{ display: "contents" }}>
                    <dt>{f.title}</dt>
                    <dd className="num">{f.value}</dd>
                  </div>
                ))}
              </dl>
            ) : (
              <p
                key={i}
                className={`${styles.cardText} ${b.separator ? styles.cardSep : ""}`}
                data-size={b.size}
                data-bolder={b.bolder ? "" : undefined}
                data-subtle={b.subtle ? "" : undefined}
                data-tone={b.tone}
              >
                {b.shape && <span aria-hidden="true">{b.shape} </span>}
                {b.text}
              </p>
            ),
          )}
          {card.actions.length > 0 ? (
            <div className={styles.cardActions}>
              {card.actions.map((a) => (
                <span key={a.title} className={styles.cardButton}>
                  {a.title}
                </span>
              ))}
            </div>
          ) : (
            <p className={styles.cardNoFiles}>보고 파일이 없어 PDF·HTML 단추 없이 보냈습니다</p>
          )}
        </div>
      </div>
    </Modal>
  );
}

/** 이 보고의 Teams 전송 시도. 재시도와 실패 사유(정제한 오류 코드)를 시각 순서로 적는다 */
export function DeliveryLog({ r }: { r: OpsReport }) {
  return (
    <section className={styles.panel} aria-labelledby="delivery-log">
      <div className={styles.panelHead}>
        <h3 id="delivery-log" className={styles.panelTitle}>
          전송 기록
        </h3>
        <span className={`${styles.panelMeta} num`}>{r.detail === "SUMMARY" ? "요약" : `시도 ${r.attempts.length}회`}</span>
      </div>
      {r.detail === "SUMMARY" && (
        <p className={styles.note}>
          {JOB_WORD[r.status].word}
          {r.sentAt ? ` · ${kstSec(r.sentAt)}` : ""} · 시도별 기록은 보고 서비스 읽기 프록시가 싣지 않습니다
        </p>
      )}
      <ol className={styles.attempts}>
        {r.attempts.map((a) => {
          const w = JOB_WORD[a.status];
          return (
            <li key={a.attempt} className={styles.attempt}>
              <span className={`${styles.attemptNo} num`}>{a.attempt}</span>
              <span className={styles.attemptWord}>
                <StatusShape kind={GLYPH_SHAPE[w.shape]} tone={w.tone} className={styles.shape} />
                <b>{w.word}</b>
                {a.error && <small>오류 {a.error} · 다시 보냄</small>}
              </span>
              <time className={`${styles.attemptTime} num`} dateTime={a.at}>
                {kstSec(a.at)}
              </time>
            </li>
          );
        })}
      </ol>
      <p className={styles.attemptsNote}>전송됨은 웹훅이 받았다는 뜻이고 채널 게시를 보장하지 않습니다.</p>
    </section>
  );
}
