"use client";

/**
 * AI 판단 상세(모달 960px, 디자인 시스템 §12-13의 3, 관리자 설계 4-9). 판단한 순간의 근거 스냅숏과 판단 과정
 * 다섯 단계(근거, 가설, 후보 조치, 정책 엔진 검사, 결과)를 한 판에 둔다. 판단 과정은 관제 판의 판단 기록(trace)
 * 그대로이고 화면이 새로 판정하지 않는다. 한 판단을 처음 열 때만 단계가 120ms 간격으로 차례로 켜진다.
 */

import { useEffect, useState } from "react";
import { stepDelays } from "@/lib/admin/motion";
import { joinDots } from "@/lib/admin/text";
import { prefersReducedMotion } from "@/lib/reducedMotion";
import type { AiCheckState, AiJudgment, ControlCenter } from "@/lib/admin/types";
import { Modal } from "../layer/Layer";
import StatusShape, { GLYPH_SHAPE } from "../StatusShape";
import { SimMark, StateChip } from "../ui";
import { AiMark, hms, SEVERITY_SHAPE } from "../control/parts";
import controlStyles from "../control/control.module.css";
import styles from "./detail.module.css";

/** 이 탭에서 이미 연 판단. 다시 열면 단계를 기다리게 하지 않는다 */
const seen = new Set<string>();

const RISK_WORD = { LOW: "낮음", MEDIUM: "중간" } as const;
const CELL_SHAPE: Record<AiCheckState, "circle" | "square" | "triangle" | null> = { PASS: "circle", BLOCK: "square", HOLD: "triangle", NA: null };
const CELL_WORD: Record<AiCheckState, string> = { PASS: "통과", BLOCK: "막음", HOLD: "승인 대기", NA: "해당 없음" };

function resultTone(outcome: string): "warning" | "neutral" | "success" {
  if (outcome.startsWith("자동 되돌림") || outcome.startsWith("AI 정지")) return "warning";
  if (outcome === "이상 없음") return "success";
  return "neutral";
}

export default function JudgmentDetail({ center, id, sim, onClose }: { center: ControlCenter; id: string; sim: boolean; onClose: () => void }) {
  const ai = center.ai.state === "READY" ? center.ai.data : null;
  const j: AiJudgment | null = ai?.history.find((h) => h.id === id) ?? null;
  const [first] = useState(() => !seen.has(id));
  useEffect(() => {
    seen.add(id);
  }, [id]);
  if (!ai || !j) {
    return (
      <Modal name="judgment" width={960} title="판단 기록을 찾지 못했습니다" onClose={onClose}>
        <p className={styles.note}>관제 판은 최근 판단 기록만 싣습니다. 이 기록은 판에서 빠졌거나 AI 원천이 연결 전입니다.</p>
      </Modal>
    );
  }
  const tr = j.trace;
  const delays = stepDelays(5, first, prefersReducedMotion());
  const step = (i: number) => ({ style: { animationDelay: `${delays[i]}ms` }, "data-still": first ? undefined : "" });

  return (
    <Modal
      name="judgment"
      width={960}
      lead={
        <span className={controlStyles.aiGlyph} aria-hidden="true">
          <AiMark size={16} />
        </span>
      }
      title={
        <>
          판단 <span className="num">{hms(j.at)}</span>
        </>
      }
      badge={<StateChip tone={resultTone(j.outcome)} label={j.outcome} shape={resultTone(j.outcome) === "warning" ? "triangle" : undefined} />}
      sub={
        <>
          <span>모델 {ai.model}</span>
          <span>근거와 값은 판단한 순간의 것이라 지금 판의 값과 다를 수 있습니다</span>
          {sim && <SimMark />}
        </>
      }
      onClose={onClose}
    >
      {tr === null ? (
        <section className={styles.section}>
          <p className={styles.stepText}>{j.summary}</p>
          <p className={styles.note}>판단이 아닌 기록이라 판단 과정이 없습니다.</p>
        </section>
      ) : (
        <>
          <div className={styles.judgeGrid}>
            <section className={styles.section} aria-label="판단 시각 근거">
              <div className={styles.sectionHead}>
                <span className={styles.sectionTitle}>근거 스냅숏</span>
                <span className={`${styles.sectionMeta} num`}>{tr.evidenceAt ? `수집 ${hms(tr.evidenceAt)}` : "수집 없음"}</span>
              </div>
              {tr.evidence.length === 0 ? (
                <div className={styles.empty}>근거가 없습니다. 정지 중이거나 판단할 이상이 없었습니다</div>
              ) : (
                <ul className={styles.evList} data-shapes={tr.evidence.some((e) => e.severity) ? "" : undefined}>
                  {tr.evidence.map((e) => (
                    <li key={e.id}>
                      {e.severity ? (
                        <StatusShape kind={GLYPH_SHAPE[SEVERITY_SHAPE[e.severity]]} tone={e.severity === "critical" ? "danger" : e.severity} className={controlStyles.shape} />
                      ) : tr.evidence.some((x) => x.severity) ? (
                        <span aria-hidden="true" />
                      ) : null}
                      <span>{e.label}</span>
                      <b className="num">{e.value}</b>
                    </li>
                  ))}
                </ul>
              )}
              <p className={styles.note}>
                상황 · {joinDots(j.summary)}
              </p>
            </section>
            <ol className={styles.steps} aria-label="판단 과정">
              <li className={styles.step} {...step(0)}>
                <span className={`${styles.stepNo} num`}>1</span>
                <div className={styles.stepBody}>
                  <span className={styles.stepTitle}>근거 {tr.evidence.length}건</span>
                  <span className={styles.stepText}>{tr.evidence.length === 0 ? "모은 근거가 없습니다" : tr.evidence.map((e) => e.label).join(", ")}</span>
                </div>
              </li>
              <li className={styles.step} {...step(1)}>
                <span className={`${styles.stepNo} num`}>2</span>
                <div className={styles.stepBody}>
                  <span className={styles.stepTitle}>원인 가설</span>
                  <span className={styles.stepText}>{tr.hypothesis ?? "세울 가설이 없습니다. 모든 지표가 임계 안입니다"}</span>
                </div>
              </li>
              <li className={styles.step} {...step(2)}>
                <span className={`${styles.stepNo} num`}>3</span>
                <div className={styles.stepBody}>
                  <span className={styles.stepTitle}>후보 조치 {tr.candidates.length}건</span>
                  {tr.candidates.length === 0 ? (
                    <span className={styles.stepText}>허용 목록에 맞는 조치가 없습니다</span>
                  ) : (
                    <ul className={styles.cands}>
                      {tr.candidates.map((c, i) => (
                        <li key={c.id}>
                          <span>
                            <b className="num">{i + 1}</b> {c.title}
                          </span>
                          <span className="num">
                            확신도 <b>{c.confidencePct}%</b> · 위험 {RISK_WORD[c.risk]}
                          </span>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              </li>
              <li className={styles.step} {...step(3)}>
                <span className={`${styles.stepNo} num`}>4</span>
                <div className={styles.stepBody}>
                  <span className={styles.stepTitle}>정책 엔진 검사</span>
                  {tr.checks.length === 0 ? (
                    <span className={styles.stepText}>검사할 후보가 없습니다</span>
                  ) : (
                    <table className={`${styles.table} ${styles.checkTable}`}>
                      <thead>
                        <tr>
                          <th scope="col">규칙</th>
                          {tr.candidates.map((c, i) => (
                            <th key={c.id} scope="col" title={c.title}>
                              후보 {i + 1}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {tr.checks.map((r) => (
                          <tr key={r.rule}>
                            <th scope="row">{r.label}</th>
                            {r.results.map((cell, i) => {
                              const shape = CELL_SHAPE[cell.state];
                              return (
                                <td key={i}>
                                  <span className={styles.cell} data-state={cell.state}>
                                    {shape ? <StatusShape kind={shape} /> : <span aria-hidden="true">·</span>}
                                    <span>
                                      <span className="sr-only">{CELL_WORD[cell.state]} · </span>
                                      {cell.note || CELL_WORD[cell.state]}
                                    </span>
                                  </span>
                                </td>
                              );
                            })}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              </li>
              <li className={styles.step} data-kind="result" {...step(4)}>
                <span className={`${styles.stepNo} num`}>5</span>
                <div className={styles.stepBody}>
                  <span className={styles.stepTitle}>결과</span>
                  <span className={styles.stepText}>{tr.result}</span>
                </div>
              </li>
            </ol>
          </div>
          <details className={styles.raw}>
            <summary>판단 기록 원문(JSON)</summary>
            <pre>{JSON.stringify(j, null, 2)}</pre>
          </details>
        </>
      )}
    </Modal>
  );
}
