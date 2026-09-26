"use client";

/**
 * 조치 확인 v2(모달 560px, 디자인 시스템 §12-13의 5). 조치를 동사로 적은 제목, 변경 전후의 큰 숫자, 대상·영향·비용·
 * 되돌리기·쿨다운, 판단 시각의 근거를 한 번 더 보이고 운영자의 결정을 받는다. 오발주를 막으려고 첫 포커스는
 * '취소'이고 덮개를 눌러도 닫지 않는다. 확정은 운영자의 긍정 결정이라 approve 모양이다(§12-5).
 */

import { useId } from "react";
import { canAutoRun, cooldownSec, type AiLimits } from "@/lib/admin/aiPolicy";
import { actionVerb, changeParts } from "@/lib/admin/opsDetail";
import type { AiDesk, AiProposal } from "@/lib/admin/types";
import { joinDots } from "@/lib/admin/text";
import adminStyles from "../admin.module.css";
import { Modal } from "../layer/Layer";
import StatusShape, { GLYPH_SHAPE } from "../StatusShape";
import { hms, SEVERITY_SHAPE } from "./parts";
import detailStyles from "../detail/detail.module.css";
import styles from "./control.module.css";

export default function ActionConfirm({ p, index, ai, limits, busy, onConfirm, onClose }: {
  p: AiProposal;
  index: number;
  ai: AiDesk;
  limits: AiLimits;
  busy: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const descId = useId();
  const cd = cooldownSec(p.kind, limits);
  const parts = p.change ? changeParts(p.change) : null;
  const judgedAt = ai.judgedAt ? hms(ai.judgedAt) : null;
  return (
    <Modal
      name="action-confirm"
      role="alertdialog"
      width={560}
      describedBy={descId}
      closeOnBackdrop={false}
      busy={busy}
      initialFocus="[data-cancel]"
      title={actionVerb(p)}
      sub={
        <>
          <span className="num">조치안 {index}</span>
          <span>{canAutoRun(p, ai.mode, limits) ? "자동 실행 가능 · 지금 승인해 실행" : "승인 필요"}</span>
        </>
      }
      footer={(close) => (
        <>
          <button type="button" data-cancel="" className={adminStyles.neutralBtn} disabled={busy} onClick={close}>
            취소
          </button>
          <button type="button" className={adminStyles.approveBtn} disabled={busy} aria-busy={busy} onClick={onConfirm}>
            {busy ? "처리 중…" : "승인하고 실행"}
          </button>
        </>
      )}
      onClose={onClose}
    >
      <div className={detailStyles.section} id={descId}>
        {parts ? (
          <p className={`${detailStyles.change} num`} aria-label={`${parts.from}에서 ${parts.to}로`}>
            <span className={detailStyles.changeFrom}>{parts.from}</span>
            <span className={detailStyles.changeArrow} aria-hidden="true">
              →
            </span>
            <span>{parts.to}</span>
          </p>
        ) : (
          <p className={detailStyles.change}>{p.kind === "SILENCE" ? "보류 30분" : "백업 1회"}</p>
        )}
        <dl className={detailStyles.confirmKv}>
          <dt>대상</dt>
          <dd>{p.target}</dd>
          <dt>영향</dt>
          <dd>{joinDots(p.impact)}</dd>
          <dt>비용</dt>
          <dd>{p.cost}</dd>
          <dt>되돌리기</dt>
          <dd>{joinDots(p.revertPlan)}</dd>
          <dt>쿨다운</dt>
          <dd>{cd > 0 ? `실행 뒤 ${Math.round(cd / 60)}분 동안 AI가 같은 조치를 다시 자동으로 하지 않습니다` : "쿨다운이 없는 조치입니다"}</dd>
        </dl>
      </div>
      {ai.evidence.length > 0 && (
        <div className={detailStyles.section}>
          <div className={detailStyles.sectionHead}>
            <span className={detailStyles.sectionTitle}>판단 근거</span>
            {judgedAt && <span className={`${detailStyles.sectionMeta} num`}>판단 {judgedAt} 기준</span>}
          </div>
          <div className={detailStyles.chips}>
            {ai.evidence.map((e) => (
              <span key={e.id} className={detailStyles.chip}>
                {e.severity && (
                  <StatusShape kind={GLYPH_SHAPE[SEVERITY_SHAPE[e.severity]]} tone={e.severity === "critical" ? "danger" : e.severity} className={styles.shape} />
                )}
                {e.label} <b className="num">{e.value}</b>
              </span>
            ))}
          </div>
        </div>
      )}
      {p.kind === "THRESHOLD" && (
        <p className={detailStyles.ticket}>임계값 변경이라 1인 승인 티켓(ops.threshold.set)을 발급하고 곧바로 소각해 승인 원장과 감사 로그에 남깁니다.</p>
      )}
    </Modal>
  );
}
