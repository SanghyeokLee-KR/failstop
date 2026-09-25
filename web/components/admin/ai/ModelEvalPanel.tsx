"use client";

/**
 * AI 판단 모델 평가(관리자 설계 4-10-2). 측정 문서의 '재평가(근거 구조화 뒤)' 최종 회차를 옮긴 정적 기록이라 '시뮬레이션'
 * 표식 대신 출처와 날짜를 단다. 행은 모델 선택 기준(ADR 0035)의 순서이고, 두 모델 가운데 나은 값만 굵게 둔다. 평가 결과는
 * 상태가 아니라 헬스 색을 쓰지 않는다. 로컬 RTX 4090의 결과이고 DGX Spark(ai-1)의 결과가 아니라고 캡션에 적는다.
 */

import { evalValue, MODEL_EVAL_METRICS, MODEL_EVALUATION } from "@/lib/admin/modelEval";
import type { ModelEvalRow } from "@/lib/admin/types";
import { PanelHead } from "../control/parts";
import controlStyles from "../control/control.module.css";
import styles from "./ai.module.css";

/** 두 값 가운데 나은 쪽인가. 같으면 둘 다 굵게 하지 않는다 */
function isBetter(key: keyof ModelEvalRow, better: "low" | "high" | null, row: ModelEvalRow, rows: ModelEvalRow[]): boolean {
  if (better === null) return false;
  const mine = evalValue(row[key]);
  const others = rows.filter((r) => r !== row).map((r) => evalValue(r[key]));
  if (mine === null || others.some((v) => v === null)) return false;
  const vs = others as number[];
  return better === "low" ? vs.every((v) => mine < v) : vs.every((v) => mine > v);
}

export default function ModelEvalPanel() {
  const ev = MODEL_EVALUATION;
  return (
    <section className={controlStyles.panel} aria-labelledby="ai-eval">
      <PanelHead id="ai-eval" title="모델 평가" meta={<span className="num">측정 · 2026-09-25</span>} />
      <p className={styles.evalCaption}>
        {ev.where} · 2026-09-25 측정 · DGX Spark 결과 아님
      </p>
      <table className={styles.evalTable}>
        <caption className="sr-only">
          판단 모델 두 개의 평가. 상황 37개를 세 번씩 부른 111회와 주입 시험, 상담 문항의 결과
        </caption>
        <thead>
          <tr>
            <th scope="col">항목</th>
            {ev.rows.map((r) => (
              <th key={r.model} scope="col" data-num="">
                <code className={styles.modelName}>{r.model}</code>
                <span className={styles.think}>{r.think === null ? "think 미지정" : `think=${r.think}`}</span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {MODEL_EVAL_METRICS.map((m) => (
            <tr key={m.key} data-key={m.key}>
              <th scope="row">
                <span className={styles.metric}>{m.label}</span>
                {m.note && <span className={styles.metricNote}>{m.note}</span>}
              </th>
              {ev.rows.map((r) => (
                <td key={r.model} data-num="" className="num" data-better={isBetter(m.key, m.better, r, ev.rows) ? "" : undefined}>
                  {r[m.key]}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <div className={styles.evalNotes}>
        <span className={styles.evalNotesTitle}>오답 유형</span>
        <ul className={styles.evalList}>
          {ev.errorTypes.map((t) => (
            <li key={t}>{t}</li>
          ))}
        </ul>
        <p className={styles.evalNext}>{ev.next}</p>
        <p className={styles.evalSource}>
          출처 <code>{ev.source}</code> · 재평가 최종 회차 · 판단 프롬프트 {ev.promptVersion} · {ev.measuredAt}
        </p>
      </div>
    </section>
  );
}
