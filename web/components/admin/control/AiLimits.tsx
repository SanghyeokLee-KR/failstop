"use client";

/**
 * AI 한도(ADR 0035, 관리자 설계 4-4). 운영자가 1인 승인 티켓(ops.ai.limits)으로 확신도 기준, 자동 조치
 * 상한, 임계값 항목당 횟수, 봇 부하 쿨다운, 백업 디스크 여유를 바꾼다. 코드에 고정한 하드 범위 밖의 값은
 * 요청을 만들지 않는다. AI 권한을 좁히는 변경은 곧바로, 넓히는 변경은 자동 모드 중이면 다음 활성부터다.
 */

import { useState } from "react";
import type { AiDesk, AiLimit, AiLimitChange, ApprovalPayload } from "@/lib/admin/types";
import ApprovalList from "../ApprovalList";
import RequestButton from "../RequestButton";
import adminStyles from "../admin.module.css";
import { hms, PanelHead } from "./parts";
import styles from "./control.module.css";

function effective(l: AiLimit): number {
  return l.pending ?? l.value;
}

/** 이 값으로 바꾸면 AI가 할 수 있는 일이 줄어드는가. 같은 값이면 넓히는 쪽이 아니다 */
function tightens(l: AiLimit, to: number): boolean {
  return to === l.value || (l.tighterWhenHigher ? to > l.value : to < l.value);
}

function applyWord(l: AiLimit, to: number, mode: AiDesk["mode"]): string {
  if (tightens(l, to)) return "AI 권한을 좁히는 변경이라 승인하고 실행하면 곧바로 적용합니다.";
  if (mode === "AUTO") return "AI 권한을 넓히는 변경이라 지금 자동 모드가 끝난 뒤(다음 활성부터) 적용합니다.";
  return "자동 모드가 꺼져 있어 승인하고 실행하면 곧바로 적용합니다. 다음 자동 활성부터 이 값을 씁니다.";
}

function payloadOf(l: AiLimit, to: number, reason: string, revertOf: string | null): ApprovalPayload {
  return { action: "ops.ai.limits", key: l.key, label: l.label, unit: l.unit, from: effective(l), to, reason, revertOf };
}

function History({ rows, items, mode }: { rows: AiLimitChange[]; items: AiLimit[]; mode: AiDesk["mode"] }) {
  if (rows.length === 0) {
    return (
      <div className={styles.empty}>
        <span className={styles.emptyMark} aria-hidden="true" />
        변경 이력 없음 · 확정 초기값 그대로
      </div>
    );
  }
  return (
    <table className={styles.hist}>
      <thead>
        <tr>
          <th>시각</th>
          <th>항목</th>
          <th>변경</th>
          <th>적용</th>
          <th>누가</th>
          <th>
            <span className="sr-only">되돌리기</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {rows.map((h) => {
          const l = items.find((x) => x.key === h.key);
          // 지금 값(대기 값 포함)이 이 변경의 결과일 때만 되돌린다. 뒤에 다른 변경이 얹혔으면 그 변경부터다
          const canRevert = l !== undefined && !h.reverted && h.revertOf === null && effective(l) === h.to;
          return (
            <tr key={h.id}>
              <td className="num">{hms(h.at)}</td>
              <td title={h.reason}>{h.label}</td>
              <td className="num">
                <b>
                  {h.from} → {h.to}
                </b>
                {h.unit}
              </td>
              <td>{h.apply === "NOW" ? "곧바로" : "다음 활성부터"}</td>
              <td>{h.by}</td>
              <td>
                {canRevert && l ? (
                  <RequestButton
                    action="ops.ai.limits"
                    payload={payloadOf(l, h.from, "이력에서 되돌리기", h.id)}
                    label="되돌리기"
                    confirm={{
                      title: "AI 한도 되돌리기 요청",
                      description: "되돌리기도 같은 1인 승인을 거칩니다. 발급하고 실행하면 적용 규칙에 따라 적용됩니다.",
                      target: `${h.label} ${h.to}${h.unit} → ${h.from}${h.unit}`,
                      blastRadius: applyWord(l, h.from, mode),
                      tone: "warning",
                      confirmLabel: "되돌리기 요청",
                    }}
                  />
                ) : h.reverted ? (
                  <span className={styles.none}>되돌림</span>
                ) : null}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

export default function AiLimitsPanel({ ai, onChanged }: { ai: AiDesk | null; onChanged: () => void }) {
  const [draft, setDraft] = useState<{ key: AiLimit["key"]; value: number } | null>(null);
  const [reason, setReason] = useState("");
  const editing = ai && draft ? ai.limits.find((l) => l.key === draft.key) ?? null : null;
  const dirty = editing !== null && draft !== null && draft.value !== effective(editing);
  const outOfRange = editing !== null && draft !== null && (!Number.isInteger(draft.value) || draft.value < editing.min || draft.value > editing.max);
  const reasonOk = reason.trim().length >= 2;
  const cancel = () => {
    setDraft(null);
    setReason("");
  };

  return (
    <section className={styles.panel} aria-labelledby="ai-limits-title" id="ai-limits">
      <PanelHead id="ai-limits-title" title="AI 한도" meta="운영자가 1인 승인으로 바꿉니다 · AI는 바꿀 수 없습니다" />
      {ai === null ? (
        <p className={styles.lead}>AI 판단 서비스와 서버 정책 엔진 연결 전. 한도 판독과 변경 없음</p>
      ) : (
        <div className={styles.stack} style={{ gap: "var(--space-3)" }}>
          <table className={styles.thTable}>
            <thead>
              <tr>
                <th>항목</th>
                <th data-num="">현재값</th>
                <th data-num="">허용 범위</th>
                <th>새 값</th>
              </tr>
            </thead>
            <tbody>
              {ai.limits.map((l) => {
                const mine = draft?.key === l.key;
                return (
                  <tr key={l.key} data-draft={mine && dirty ? "" : undefined}>
                    <td>
                      <span className={`${styles.thName} ${styles.limitName}`}>
                        <b>{l.label}</b>
                        <span>{l.desc}</span>
                      </span>
                    </td>
                    <td className={`${styles.thValue} num`}>
                      {l.value}
                      <small>{l.unit}</small>
                      {l.pending !== null && (
                        <span className={styles.limitPending}>
                          → {l.pending}
                          {l.unit} 다음 활성부터
                        </span>
                      )}
                    </td>
                    <td className={`${styles.thRange} num`}>
                      {l.min}~{l.max}
                      {l.unit}
                    </td>
                    <td>
                      <input
                        type="number"
                        className={styles.limitInput}
                        min={l.min}
                        max={l.max}
                        step={1}
                        value={mine && draft ? draft.value : effective(l)}
                        aria-label={`${l.label} 새 값`}
                        onChange={(e) => setDraft({ key: l.key, value: Number(e.target.value) })}
                      />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {editing && draft && dirty ? (
            <div className={styles.review}>
              <div className={styles.subHead}>변경 검토 · {editing.label}</div>
              <div className={`${styles.reviewDiff} num`}>
                <s>
                  {effective(editing)}
                  {editing.unit}
                </s>
                <span aria-hidden="true">→</span>
                <span>
                  {draft.value}
                  {editing.unit}
                </span>
                <span className="sr-only">
                  {effective(editing)}에서 {draft.value}로
                </span>
              </div>
              {outOfRange ? (
                <p className={styles.reviewError}>
                  ■ 허용 범위 {editing.min}~{editing.max}
                  {editing.unit} 밖입니다. 요청을 만들지 않습니다
                </p>
              ) : (
                <p className={styles.reviewText}>
                  <b>적용</b> · {applyWord(editing, draft.value, ai.mode)}
                </p>
              )}
              <label className={styles.autoFormLabel} htmlFor="limit-reason">
                사유 · 승인 원장과 감사 로그, 사건 기록에 남음
              </label>
              <input
                id="limit-reason"
                className={styles.autoReason}
                value={reason}
                maxLength={80}
                placeholder="예: 야간 대응 동안 자동 조치를 더 좁힘"
                onChange={(e) => setReason(e.target.value)}
              />
              <div className={styles.reviewActions}>
                {!outOfRange && reasonOk ? (
                  <RequestButton
                    action="ops.ai.limits"
                    payload={payloadOf(editing, draft.value, reason.trim(), null)}
                    label="변경 요청"
                    onRequested={cancel}
                    confirm={{
                      title: "AI 한도 변경 요청",
                      description: applyWord(editing, draft.value, ai.mode),
                      target: `${editing.label} ${effective(editing)}${editing.unit} → ${draft.value}${editing.unit}`,
                      blastRadius: "승인 목록에서 발급·실행 뒤 적용",
                      tone: "warning",
                      confirmLabel: "변경 요청",
                    }}
                  />
                ) : (
                  <button type="button" className={adminStyles.neutralBtn} disabled title={outOfRange ? "허용 범위 밖" : "사유 입력 필요"}>
                    변경 요청
                  </button>
                )}
                <button type="button" className={styles.smallBtn} onClick={cancel}>
                  취소
                </button>
                {!outOfRange && !reasonOk && <span className={styles.reviewText}>사유를 두 글자 이상 적어야 요청할 수 있습니다</span>}
              </div>
            </div>
          ) : (
            <div className={styles.review} data-empty="">
              <div className={styles.subHead}>변경 검토</div>
              <p className={styles.reviewText}>
                값을 바꾸면 변경 전후와 적용 시점이 여기에 나타납니다. AI 권한을 좁히는 변경은 곧바로, 넓히는 변경은 자동
                모드 중이면 다음 활성부터 적용합니다.
              </p>
            </div>
          )}
          <div className={styles.subHead}>승인 대기</div>
          <ApprovalList
            actions={["ops.ai.limits"]}
            onChange={onChanged}
            emptyLabel="대기 중인 한도 변경 없음"
            emptyDesc="변경 요청을 하면 여기서 발급하고 실행"
          />
          <div className={styles.subHead}>변경 이력</div>
          <History rows={ai.limitHistory} items={ai.limits} mode={ai.mode} />
        </div>
      )}
    </section>
  );
}
