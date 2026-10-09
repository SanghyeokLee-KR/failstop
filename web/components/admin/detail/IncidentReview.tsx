"use client";

import { incidentJudgmentId, incidentReview } from "@/lib/admin/incidentReview";
import { mdhm } from "@/lib/admin/timeText";
import type { AiJudgment, Incident, IncidentEvent } from "@/lib/admin/types";
import styles from "./detail.module.css";

export default function IncidentReview({ inc, events, history, onOpenJudgment }: {
  inc: Incident; events: IncidentEvent[]; history: AiJudgment[];
  onOpenJudgment: (id: string) => void;
}) {
  const review = incidentReview(inc, events);
  return (
    <section className={`${styles.section} ${styles.review}`} aria-label="기록 기반 사후 검토">
      <div className={styles.sectionHead}>
        <h3 className={styles.sectionTitle}>기록 기반 사후 검토</h3>
        <span className={styles.sectionMeta}>{inc.resolvedAt ? "경보 해제 · 효과 확인 필요" : "진행 중 · 중간 검토"}</span>
      </div>
      <p className={styles.reviewLead}>
        {inc.resolvedAt ? "경보는 해제됐습니다. 조치 효과는 전후 지표로 확인합니다." : "판단과 조치 기록을 모았습니다. 해제 여부는 계속 관측합니다."}
      </p>
      <p className={styles.note}>저장된 사건 기록을 정리한 내용입니다. 새로운 AI 분석이나 운영자가 완료한 사후 분석은 아닙니다.</p>
      <dl className={styles.kv}>
        <dt>판단 근거</dt>
        <dd>{review.judgments.length === 0 ? "연결된 판단 기록 없음" : review.judgments.map((e) => {
          const id = incidentJudgmentId(e, history);
          return <div key={e.id} className={styles.reviewItem}>
            <span>{mdhm(e.at)} · {e.text}</span>
            {id ? <button type="button" className={styles.linkBtn} onClick={() => onOpenJudgment(id)}>실측 근거·JSON 보기</button> : <span className={styles.note}>판단 참조 없음</span>}
          </div>;
        })}</dd>
        <dt>승인·조치</dt>
        <dd>{review.actions.length === 0 ? "연결된 조치 기록 없음" : review.actions.map((e) => <div key={e.id} className={styles.reviewItem}>{mdhm(e.at)} · {e.actorName} · {e.text}</div>)}</dd>
        <dt>경보 해제</dt>
        <dd>{inc.resolvedAt ? mdhm(inc.resolvedAt) : "해제 전"}
          {review.clearRecordedAt && review.clearRecordedAt !== inc.resolvedAt && <p className={styles.note}>해제 기록 수신 {mdhm(review.clearRecordedAt)} · 경보 해제 시각과 수신 시각은 다를 수 있습니다.</p>}
        </dd>
        <dt>사후 관측</dt>
        <dd>{review.observations.length === 0 ? "연결된 사후 관측 기록 없음 · 효과 미확인" : review.observations.map((e) => <div key={e.id} className={styles.reviewItem}>{mdhm(e.at)} · {e.text}</div>)}</dd>
      </dl>
      <div className={styles.sectionTitle}>다음 확인</div>
      <ul className={styles.reviewChecks}>{review.followUp.map((text) => <li key={text}>{text}</li>)}</ul>
    </section>
  );
}
