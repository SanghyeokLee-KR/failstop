/**
 * AI가 하는 일과 하지 않는 일의 목록(ADR 0035). 모드 줄의 펼침, 자동 모드 켜기 대화상자, AI 판단 화면이 같은 부품을 쓴다.
 * 목록 머리 기호는 상태 형태(●■▲)가 아니다. 상태가 아닌 자리에 상태 모양이 붙으면 판을 훑는 문법이 흐려진다. 허용은 4px 무채
 * 네모 점, 금지는 가는 가위표, 자동 실행 조건은 번호다(디자인 시스템 §12-10 v1.21)
 */

import { AI_ACTION_LABEL, AI_FORBIDDEN, allowedRules, autoPreconditions, type AiLimits } from "@/lib/admin/aiPolicy";
import KeepNames from "../KeepNames";
import StatusShape from "../StatusShape";
import styles from "./control.module.css";

export function AllowedList({ limits }: { limits?: AiLimits }) {
  return (
    <ul className={styles.ruleList}>
      {allowedRules(limits).map((r) => (
        <li key={r.kind}>
          <span className={styles.ruleDot} aria-hidden="true" />
          <span>
            <b>
              <KeepNames text={AI_ACTION_LABEL[r.kind]} />
            </b>{" "}
            · <KeepNames text={r.rule} />
          </span>
        </li>
      ))}
    </ul>
  );
}

export function ForbiddenList() {
  return (
    <ul className={styles.ruleList}>
      {AI_FORBIDDEN.map((r) => (
        <li key={r}>
          <StatusShape kind="cross" className={styles.ruleCross} />
          <span>
            <KeepNames text={r} />
          </span>
        </li>
      ))}
    </ul>
  );
}

export function ConditionList({ limits }: { limits?: AiLimits }) {
  return (
    <ol className={styles.ruleList} data-numbered="">
      {autoPreconditions(limits).map((r, i) => (
        <li key={r}>
          <span className={`${styles.ruleNo} num`} aria-hidden="true">
            {i + 1}
          </span>
          <span>
            <KeepNames text={r} />
          </span>
        </li>
      ))}
    </ol>
  );
}
