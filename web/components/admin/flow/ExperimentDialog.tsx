"use client";

/**
 * 재생 대화상자. 노드나 버튼에서 상황을 고르면 재생 전에 무엇이 일어나고 무엇을 볼지 먼저 보인다.
 *
 * ConfirmDialog 를 쓰지 않은 이유. 그쪽은 폭이 420px 이고 제목 옆에 위험도를 둘 자리가 없으며
 * 본문이 길어질 때 안에서 스크롤하지 않는다. 이 대화상자는 영향·관찰 지표·근거·명령까지 싣고
 * 820px 높이 화면에도 들어가야 한다. 그래서 틀은 따로 두고 접근성 동작만 ConfirmDialog.tsx 를
 * 옮겼다. 포털, Tab 순환, 취소에 둔 초기 포커스, Escape 닫기, 토스트만 남긴 배경 격리,
 * 닫힐 때 여는 버튼으로 포커스 복귀가 같다. 다른 점은 격리 해제와 포커스 복귀의 순서를 한
 * 이펙트 안에 고정한 것 하나다(아래 정리 함수 주석). 한쪽을 고치면 다른 쪽도 함께 본다.
 *
 * 재생은 화면 안의 시뮬레이션이라 컨테이너에 아무 명령도 보내지 않는다. 같은 일을 실제로
 * 일으키는 명령은 복사 칩으로만 보이고 콘솔은 실행하지 않는다.
 */

import { useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";
import { CommandChip } from "@/components/admin/DrillStepper";
import { HEALTH_ICON, HealthPill, type Health } from "@/components/admin/ui";
import type { Availability, Risk, ScenarioView } from "@/lib/admin/flowSim";
import styles from "./panels.module.css";

/* 위험도의 중간·높음만 헬스 도메인의 주의·장애 필로 옮긴다. 낮음은 상태가 아니라서 정상 초록 점
   (§12-3)을 받지 않고 형태 없이 잉크 글자로만 적는다 */
const RISK_HEALTH: Partial<Record<Risk, Health>> = { 높음: "danger", 중간: "warning" };

export interface ExperimentDialogProps {
  view: ScenarioView;
  availability: Availability;
  /** 재생 시작. 부모가 시나리오를 걸고 대화상자를 닫는다 */
  onStart: () => void;
  onClose: () => void;
}

export default function ExperimentDialog({ view, availability, onStart, onClose }: ExperimentDialogProps) {
  const titleId = useId();
  const calloutId = useId();
  const reasonId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const riskHealth = RISK_HEALTH[view.risk];
  // 부모가 매초 다시 그리며 인라인 화살표를 넘겨도 포커스 이펙트를 다시 돌리지 않게 최신 참조만 잡는다.
  // 다시 돌면 초기 포커스가 취소로 되돌아가 읽던 자리를 잃는다(ConfirmDialog 와 같은 이유)
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    // 여는 버튼은 배경을 격리하기 전에 잡는다. inert 에 걸린 요소에 포커스가 있으면 브라우저가 body 로 뺀다
    const prevFocused = document.activeElement as HTMLElement | null;
    const dialog = dialogRef.current;
    const overlay = overlayRef.current;

    // 배경 격리. 토스트 리전은 뺀다. inert 에 걸리면 열려 있는 동안 토스트 낭독과 닫기가 죽는다
    const bg = Array.from(document.body.children).filter(
      (el) => el !== overlay && !el.hasAttribute("data-toast-region"),
    ) as HTMLElement[];
    const prevInert = bg.map((n) => ({ n, inert: n.hasAttribute("inert") }));
    bg.forEach((n) => n.setAttribute("inert", ""));

    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        onCloseRef.current();
        return;
      }
      // 포커스 트랩. Tab 이 대화상자 밖으로 새지 않게 처음과 끝을 잇는다
      if (e.key === "Tab" && dialog) {
        const focusables = dialog.querySelectorAll<HTMLElement>(
          'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        );
        if (focusables.length === 0) {
          e.preventDefault();
          dialog.focus();
          return;
        }
        const first = focusables[0];
        const last = focusables[focusables.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey);
    // 초기 포커스는 취소. 실수로 Enter 를 눌러도 재생이 시작되지 않는다
    if (cancelRef.current) cancelRef.current.focus();
    else dialog?.focus();

    return () => {
      document.removeEventListener("keydown", onKey);
      /* 격리를 먼저 풀고 포커스를 돌려준다. ConfirmDialog 는 둘을 이펙트 두 개로 나눴는데 React 는
         정리를 선언 순서대로 돌려서, 포커스 복귀가 아직 inert 인 배경에 부딪혀 body 로 떨어진다.
         한 이펙트 안에서 순서를 적어 두면 개발 모드의 이중 실행에서도 같은 순서가 지켜진다 */
      prevInert.forEach(({ n, inert }) => {
        if (!inert) n.removeAttribute("inert");
      });
      prevFocused?.focus?.();
    };
    // 열릴 때 한 번만. 매초 오는 틱 리렌더와 무관하게 열림·닫힘 경계에서만 포커스를 옮긴다
  }, []);

  const danger = view.tone === "danger";
  const blocked = !availability.ok;

  return createPortal(
    <div ref={overlayRef} className={styles.overlay} onMouseDown={onClose}>
      <div
        ref={dialogRef}
        className={styles.dialog}
        role="dialog"
        tabIndex={-1}
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={calloutId}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <header className={styles.dialogHead}>
          <span className={styles.dialogIcon} data-tone={view.tone} aria-hidden="true">
            {danger ? <AlertGlyph /> : <ReplayGlyph />}
          </span>
          <div className={styles.dialogHeading}>
            <h2 id={titleId} className={styles.dialogTitle}>
              {view.title}
            </h2>
            <p className={styles.dialogTarget}>
              <span className={styles.dialogTargetKey}>대상</span>
              <span className={styles.dialogTargetValue}>{view.target}</span>
            </p>
          </div>
          <span className={styles.dialogRisk}>
            {riskHealth ? (
              <HealthPill health={riskHealth} label={`위험도 ${view.risk}`} />
            ) : (
              <span className={styles.riskInk}>위험도 {view.risk}</span>
            )}
          </span>
        </header>

        {/* 본문만 스크롤한다. 머리와 발은 고정이라 어디까지 내려도 대상과 시작 버튼이 보인다.
            키보드로도 내려 읽을 수 있게 본문 자체를 포커스 대상으로 둔다 */}
        <div className={styles.dialogBody} tabIndex={0} role="region" aria-label="재생 설명">
          {/* 안내 자체는 무채다. 위험도가 낮은 재생에 주의 틴트와 삼각을 얹으면 머리의 위험도 필과
              어긋난다. 위험 재생일 때만 실제 영향 한 줄에 위험 형태와 색을 붙인다 */}
          <div id={calloutId} className={styles.callout}>
            <p className={styles.calloutLead}>화면 안 재생 · 컨테이너에 명령을 보내지 않음</p>
            {danger && view.impacts.length > 0 && (
              <p className={styles.calloutImpact}>
                <span className={styles.calloutShape} aria-hidden="true">
                  {HEALTH_ICON.danger}
                </span>
                <span>실제 실행 시 {view.impacts[0]}</span>
              </p>
            )}
          </div>

          <section className={styles.section} aria-labelledby={`${titleId}-impact`}>
            <h3 id={`${titleId}-impact`} className={styles.sectionTitle}>
              예상 영향
            </h3>
            <ul className={styles.impactList}>
              {view.impacts.map((t) => (
                <li key={t}>{t}</li>
              ))}
            </ul>
          </section>

          <section className={styles.section} aria-labelledby={`${titleId}-observe`}>
            <h3 id={`${titleId}-observe`} className={styles.sectionTitle}>
              관찰 지표
            </h3>
            {/* 추이 차트는 서비스 흐름에서 지표 관제로 옮겼다. 두 화면은 같은 재생을 보므로 재생을 건 뒤
                그쪽으로 넘어가면 이 지표가 이어져 그려진다(lib/admin/flowRun.ts) */}
            <p className={styles.sectionNote}>지표 관제 화면의 추이 차트</p>
            {/* 입력이 아니라 볼 목록이다. 체크박스처럼 보이면 눌러야 하는 것으로 읽히므로 네모 틀 없이 체크 표시만 붙인다 */}
            <ul className={styles.observeList}>
              {view.observe.map((t) => (
                <li key={t}>
                  <CheckGlyph />
                  <span>{t}</span>
                </li>
              ))}
            </ul>
          </section>

          <section className={styles.section} aria-labelledby={`${titleId}-evidence`}>
            <h3 id={`${titleId}-evidence`} className={styles.sectionTitle}>
              근거
            </h3>
            <div className={styles.evidenceBox}>
              <p className={styles.evidenceSource}>{view.evidenceSource}</p>
              <ul className={styles.evidenceList}>
                {view.evidence.map((t) => (
                  <li key={t}>{t}</li>
                ))}
              </ul>
            </div>
          </section>

          <section className={styles.section} aria-labelledby={`${titleId}-host`}>
            <h3 id={`${titleId}-host`} className={styles.sectionTitle}>
              실제 실행
            </h3>
            <p className={styles.sectionNote}>복사 전용 · 콘솔은 실행하지 않음</p>
            <ul className={styles.hostList}>
              {view.host.map((c) => (
                <li key={c}>
                  <CommandChip command={c} />
                </li>
              ))}
            </ul>
          </section>
        </div>

        <footer className={styles.dialogFoot}>
          <div className={styles.footMeta}>
            <span className={`${styles.footLength} num`}>재생 길이 {view.length}초</span>
            {blocked && availability.reason && (
              <span id={reasonId} className={styles.footReason}>
                {availability.reason}
              </span>
            )}
          </div>
          <button ref={cancelRef} type="button" className={`btn btn--secondary ${styles.footBtn}`} onClick={onClose}>
            취소
          </button>
          <button
            type="button"
            className={styles.startBtn}
            data-tone={danger ? "danger" : "neutral"}
            disabled={blocked}
            aria-describedby={blocked && availability.reason ? reasonId : undefined}
            onClick={onStart}
          >
            {/* 색 단독 금지라 위험 재생은 사각 형태를 라벨 앞에 붙인다(ConfirmDialog 확정 버튼과 같은 규칙) */}
            {danger && <span aria-hidden="true">{HEALTH_ICON.danger}</span>}
            재생 시작
          </button>
        </footer>
      </div>
    </div>,
    document.body,
  );
}

/* 아이콘은 관리 평면 내비 아이콘(icons.tsx)과 같은 제도 어휘다. 16px 격자, 선 1.5, 둥근 끝 */

/** 위험 재생. 사각 틀 안의 느낌표. 위험 형태가 사각이라 삼각 경고표를 쓰지 않는다 */
function AlertGlyph() {
  return (
    <svg width="20" height="20" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <rect x="2.25" y="2.25" width="11.5" height="11.5" rx="2" />
      <path d="M8 5 V8.75" />
      <path d="M8 11 V11.05" />
    </svg>
  );
}

/** 주의 재생. 원 안의 재생 삼각 */
function ReplayGlyph() {
  return (
    <svg width="20" height="20" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="8" cy="8" r="5.75" />
      <path d="M6.75 5.75 L10.25 8 L6.75 10.25 Z" />
    </svg>
  );
}

function CheckGlyph() {
  return (
    <svg
      className={styles.checkGlyph}
      width="16"
      height="16"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M3.5 8.25 L6.5 11.25 L12.5 4.75" />
    </svg>
  );
}
