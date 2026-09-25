"use client";

/**
 * 활성 경보. 확인은 누가 보고 있는지 남기는 기록이고 경보를 끄지 않는다. 보류는 최대 30분이고
 * 장애 등급과 같은 경보의 연속 보류는 막는다(ADR 0035). 막힌 이유는 버튼 옆에 적는다.
 */

import { useEffect, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { AI_RULES } from "@/lib/admin/aiPolicy";
import { refMatches, useOpsFocus } from "@/lib/admin/opsCenter";
import { useCan } from "@/lib/admin/store";
import type { OpsAlert } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import { useFreshKeys } from "../useFreshKeys";
import { durationWord, hm, SEVERITY_SHAPE, SEVERITY_WORD, Shape, useClock } from "./parts";
import styles from "./control.module.css";
import StatusShape from "../StatusShape";

const SILENCE_CHOICES = [10, 20, AI_RULES.silenceMaxMin];

function SilenceButton({ alert, onDone }: { alert: OpsAlert; onDone: () => void }) {
  const canSuper = useCan("SUPER");
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const esc = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", esc);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", esc);
    };
  }, [open]);
  const blocked = alert.severity === "critical" ? "장애 등급은 보류 불가"
    : alert.state === "SILENCED" ? null
    : alert.silencedBefore ? "같은 경보 연속 보류 불가"
    : !canSuper ? "총괄 권한 필요" : null;
  if (alert.state === "SILENCED") return null;
  const silence = async (minutes: number) => {
    setOpen(false);
    try {
      await getAdminApi().silenceAlert(alert.id, minutes);
      toast(`${alert.name} ${minutes}분 보류`, "info");
      onDone();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "보류 실패", "error");
    }
  };
  return (
    <span className={styles.silenceWrap} ref={ref}>
      <button
        type="button"
        className={styles.smallBtn}
        disabled={blocked !== null}
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        보류
      </button>
      {blocked && <span className={styles.alertNote}>{blocked}</span>}
      {open && (
        <span className={styles.silenceMenu} role="group" aria-label="보류 시간">
          {SILENCE_CHOICES.map((m) => (
            <button key={m} type="button" className={styles.smallBtn} onClick={() => void silence(m)}>
              {m}분
            </button>
          ))}
        </span>
      )}
    </span>
  );
}

/** 경보가 울린 지 얼마인지. 매초 바뀌는 글자라 이 조각만 시계를 탄다 */
function AlertAge({ since }: { since: string }) {
  const now = useClock();
  const ms = now - Date.parse(since);
  return <>{ms < 2000 ? "방금" : `${durationWord(ms)}째`}</>;
}

export function AlertRow({ alert, onChanged, fresh = false }: { alert: OpsAlert; onChanged: () => void; fresh?: boolean }) {
  const focus = useOpsFocus((s) => s.ref);
  const lit = refMatches(focus, { kind: "ALERT", id: alert.id });
  const ack = async () => {
    try {
      await getAdminApi().ackAlert(alert.id);
      toast(`${alert.name} 확인`, "info");
      onChanged();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "확인 실패", "error");
    }
  };
  const tone = alert.severity === "critical" ? "danger" : alert.severity === "warning" ? "warning" : "info";
  return (
    <li className={styles.alert} data-sev={alert.severity} data-focus={lit ? "" : undefined} data-fresh={fresh ? "" : undefined}>
      <Shape tone={tone}>{SEVERITY_SHAPE[alert.severity]}</Shape>
      <span className={styles.alertName}>
        <span className="sr-only">{SEVERITY_WORD[alert.severity]} 경보 </span>
        {alert.name}
      </span>
      <span className={`${styles.alertAge} num`}>
        <AlertAge since={alert.since} />
      </span>
      <span className={styles.alertDetail}>
        {alert.target} · {alert.detail}
      </span>
      <div className={styles.alertActions}>
        {alert.ackBy ? (
          <span className={styles.alertState}>
            <span aria-hidden="true"><StatusShape kind="circle" /></span> 확인 · {alert.ackBy} {alert.ackAt ? hm(alert.ackAt) : ""}
          </span>
        ) : (
          <button type="button" className={styles.smallBtn} onClick={() => void ack()}>
            확인
          </button>
        )}
        {alert.state === "SILENCED" && alert.silencedUntil ? (
          <span className={styles.alertState}>
            <span aria-hidden="true"><StatusShape kind="half" /></span> 보류 {hm(alert.silencedUntil)}까지 · {alert.silencedBy}
          </span>
        ) : (
          <SilenceButton alert={alert} onDone={onChanged} />
        )}
      </div>
    </li>
  );
}

export default function AlertList({ alerts, onChanged }: { alerts: OpsAlert[]; onChanged: () => void }) {
  // 새로 선 경보 행만 들어오는 움직임을 받는다(§12-4 목록 행 등장)
  const fresh = useFreshKeys(alerts.map((a) => a.id));
  if (alerts.length === 0) {
    return (
      <div className={styles.empty}>
        <span className={styles.emptyMark} aria-hidden="true" />
        활성 경보 없음
      </div>
    );
  }
  const order = { critical: 0, warning: 1, info: 2 } as const;
  const sorted = [...alerts].sort((a, b) => order[a.severity] - order[b.severity] || Date.parse(a.since) - Date.parse(b.since));
  return (
    <ul className={styles.alertList} aria-label="활성 경보">
      {sorted.map((a) => (
        <AlertRow key={a.id} alert={a} onChanged={onChanged} fresh={fresh.has(a.id)} />
      ))}
    </ul>
  );
}
