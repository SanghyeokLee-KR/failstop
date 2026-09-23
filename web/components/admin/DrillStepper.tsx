"use client";

/**
 * 시연 단계 목록. 한 단계가 한 줄이고 줄마다 조작·판독·증거가 같은 높이에 선다.
 *
 * 줄을 카드로 쪼개지 않는 이유. 단계는 순서가 있는 것이라 세로로 이어 읽어야 한다.
 * 카드 격자로 놓으면 눈이 좌우로 먼저 가고 순서가 사라진다. 왼쪽에 번호 기둥을 세우고
 * 그 기둥으로 순서를 말한다.
 *
 * 판독값을 제목 줄 오른쪽에 붙인다. 아래로 내리면 줄마다 다른 자리에 떨어져 값끼리
 * 눈으로 잇지 못한다. 오른쪽에 모아 두면 계기판처럼 한 열로 읽힌다.
 *
 * 램프는 지금 값이고 시각 도장은 이번 주행의 기록이다. 둘을 갈라 두는 이유가 있다.
 * 발행률을 올렸다가 되돌리면 그 단계는 다시 미충족이 된다. 그것이 사실이다. 다만 시연
 * 중에 "아까 됐던 것"이 사라지면 어디까지 왔는지 잃으므로, 처음 충족한 시각을 따로 적고
 * 그것이 기록임을 라벨로 밝힌다. 기록은 이 세션 안에서만 살고 저장되지 않는다.
 */

import { useState } from "react";
import type { DrillCheck, DrillDef, DrillState, DrillStep } from "@/lib/admin/drills";
import GrafanaLink from "./GrafanaLink";
import { HEALTH_ICON, type Health } from "./ui";
import { formatTimeHMS } from "@/lib/format";
import styles from "./admin.module.css";

/** 상태어. 원천 없음과 미수집은 미달이 아니라 판정 불가라 따로 적는다 */
const STATE_WORD: Record<DrillState, string> = {
  MET: "충족",
  PENDING: "대기",
  MISSING: "미수집",
  BLOCKED: "앞 단계 대기",
  NO_SOURCE: "원천 없음",
};

/** 헬스 색을 빌린다. 대기는 색을 쓰지 않는다. 아직 일어나지 않은 것은 결함이 아니다 */
const STATE_TONE: Record<DrillState, Health | undefined> = {
  MET: "success",
  PENDING: undefined,
  MISSING: "warning",
  BLOCKED: undefined,
  NO_SOURCE: undefined,
};

export interface DrillStepperProps {
  def: DrillDef;
  checks: DrillCheck[];
  /** 단계별 호스트 명령. 대상을 아직 관측하지 못한 명령은 null이라 칩을 그리지 않는다 */
  commands: (string | null)[];
  /** 이번 주행에서 처음 충족한 시각. 단계 id에서 ISO 문자열로 */
  metAt: Record<string, string>;
  currentIndex: number | null;
  /** 콘솔 조작 실행 */
  onAct: (step: DrillStep) => void | Promise<void>;
  /** 이 단계의 조작에 권한이 있는가. 조작마다 요구 역할이 다르다 */
  actAllowed: (step: DrillStep) => boolean;
  actBusy: string | null;
}

export default function DrillStepper({
  def,
  checks,
  commands,
  metAt,
  currentIndex,
  onAct,
  actAllowed,
  actBusy,
}: DrillStepperProps) {
  return (
    <ol className={styles.drillSteps}>
      {def.steps.map((step, i) => (
        <StepRow
          key={step.id}
          step={step}
          index={i}
          check={checks[i]}
          command={commands[i]}
          metAt={metAt[step.id]}
          current={i === currentIndex}
          onAct={onAct}
          allowed={actAllowed(step)}
          busy={actBusy === step.id}
        />
      ))}
    </ol>
  );
}

function StepRow({
  step,
  index,
  check,
  command,
  metAt,
  current,
  onAct,
  allowed,
  busy,
}: {
  step: DrillStep;
  index: number;
  check: DrillCheck;
  command: string | null;
  metAt: string | undefined;
  current: boolean;
  onAct: (step: DrillStep) => void | Promise<void>;
  allowed: boolean;
  busy: boolean;
}) {
  const tone = STATE_TONE[check.state];
  const hasFoot = Boolean(step.act || command || step.board || metAt);
  return (
    <li className={styles.drillStep} data-state={check.state} data-current={current ? "" : undefined}>
      <span className={styles.drillIndex} aria-hidden="true">
        {index + 1}
      </span>
      <div className={styles.drillBody}>
        <div className={styles.drillHead}>
          <span className={styles.drillTitle}>{step.title}</span>
          <span className={styles.drillMeasure}>
            <span className={`${styles.drillValue} num`}>{check.readout}</span>
            <span className={styles.drillState} data-tone={tone}>
              {tone && (
                <span className={styles.toneIcon} aria-hidden="true">
                  {HEALTH_ICON[tone]}
                </span>
              )}
              {STATE_WORD[check.state]}
            </span>
          </span>
        </div>
        <p className={styles.drillDetail}>{step.detail}</p>
        {hasFoot && (
          <div className={styles.drillActions}>
            {step.act && (
              <button
                type="button"
                className={current ? styles.primaryBtn : "btn"}
                disabled={!allowed || busy}
                onClick={() => void onAct(step)}
              >
                {step.act.label}
              </button>
            )}
            {command && <CommandChip command={command} />}
            {step.board && <GrafanaLink board={step.board} label="추이" />}
            {metAt && <span className={styles.drillStamp}>이번 주행 {formatTimeHMS(metAt)} 충족</span>}
          </div>
        )}
      </div>
    </li>
  );
}

/**
 * 호스트에서 칠 명령. 버튼처럼 보이지 않게 둔다. 누르면 복사만 되고 실행되지 않는다.
 * 클립보드는 안전 맥락이 아니면 없을 수 있어 실패해도 화면이 깨지지 않게 감싼다.
 */
export function CommandChip({ command }: { command: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(command);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      // 복사가 막힌 환경에서는 문자열이 화면에 그대로 있으므로 손으로 옮긴다
    }
  };
  return (
    <button type="button" className={styles.drillCommand} onClick={() => void copy()} title="클릭하면 복사">
      <code>{command}</code>
      <span className={styles.drillCopy} aria-hidden="true">
        {copied ? "복사됨" : "복사"}
      </span>
      <span className="sr-only">호스트 명령 복사. 콘솔은 실행하지 않음</span>
    </button>
  );
}
