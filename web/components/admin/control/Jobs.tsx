"use client";

/**
 * 운영 작업 상태. 백업은 Databasus의 결과를 판독만 하고 설정을 바꾸지 않는다(관리자 설계 4-4).
 * 인증서 만료와 일일 보고 전송은 날짜로 판정한다.
 */

import { useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { useCan } from "@/lib/admin/store";
import type { JobOutcome, OpsBackup, OpsJobs } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import adminStyles from "../admin.module.css";
import { ago, hm, Shape } from "./parts";
import styles from "./control.module.css";

/** Databasus 화면 주소. 빌드 때 넣고 없으면 링크를 그리지 않는다(GrafanaLink와 같은 규약) */
const DATABASUS = (process.env.NEXT_PUBLIC_DATABASUS_BASE ?? "").replace(/\/+$/, "");

export function DatabasusLink() {
  if (!DATABASUS) return null;
  return (
    <a className={adminStyles.boardLink} href={DATABASUS} target="_blank" rel="noreferrer">
      Databasus에서 열기
      <span aria-hidden="true">↗</span>
      <span className="sr-only">새 창에서 백업 관리 화면 열기</span>
    </a>
  );
}

function Outcome({ o, ok, fail = "실패" }: { o: JobOutcome; ok: string; fail?: string }) {
  if (o === "SUCCESS") {
    return (
      <span>
        <Shape tone="success">●</Shape> {ok}
      </span>
    );
  }
  if (o === "FAILED") {
    return (
      <span>
        <Shape tone="danger">■</Shape> {fail}
      </span>
    );
  }
  return (
    <span>
      <Shape tone="off">◌</Shape> 기록 없음
    </span>
  );
}

function size(bytes: number | null): string {
  if (bytes === null) return "크기 미상";
  return bytes >= 1e9 ? `${(bytes / 1e9).toFixed(2)}GB` : `${Math.round(bytes / 1e6)}MB`;
}

function dayWord(iso: string, now: number): string {
  const d = new Date(iso);
  const same = d.toDateString() === new Date(now).toDateString();
  const tomorrow = d.toDateString() === new Date(now + 86_400_000).toDateString();
  return `${same ? "오늘" : tomorrow ? "내일" : `${d.getMonth() + 1}.${d.getDate()}`} ${hm(iso)}`;
}

export function BackupLine({ b, now, onChanged, detail }: { b: OpsBackup; now: number; onChanged: () => void; detail?: boolean }) {
  const canSuper = useCan("SUPER");
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true);
    try {
      await getAdminApi().runBackupNow();
      toast("Databasus 백업 시작", "info");
      onChanged();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "백업 실행 실패", "error");
    } finally {
      setBusy(false);
    }
  };
  if (detail) {
    return (
      <dl className={styles.kv}>
        <dt>마지막 성공</dt>
        <dd>
          {b.lastSuccessAt ? (
            <>
              <b className="num">{dayWord(b.lastSuccessAt, now)}</b> · {ago(b.lastSuccessAt, now)} · {size(b.lastSizeBytes)}
            </>
          ) : (
            "기록 없음"
          )}
          {b.running && " · 지금 실행 중"}
        </dd>
        <dt>외부 보관({b.offsite.target})</dt>
        <dd>
          <Outcome o={b.offsite.outcome} ok="보관 완료" /> {b.offsite.at && `· ${dayWord(b.offsite.at, now)}`}
        </dd>
        <dt>복원 검증</dt>
        <dd>
          <Outcome o={b.restoreCheck.outcome} ok="통과" fail="실패" />
          {b.restoreCheck.lastSuccessAt && <> · 마지막 성공 {dayWord(b.restoreCheck.lastSuccessAt, now)}</>}
          {b.restoreCheck.lastFailureAt && <> · 마지막 실패 {dayWord(b.restoreCheck.lastFailureAt, now)}</>}
        </dd>
        <dt>다음 예약</dt>
        <dd>{b.nextAt ? <b className="num">{dayWord(b.nextAt, now)}</b> : "예약 없음"}</dd>
        <dt>즉시 실행</dt>
        <dd>
          오늘 {b.manualRunsToday}회 · AI 자동 실행은 하루 1회{" "}
          <button type="button" className={adminStyles.neutralBtn} disabled={!canSuper || busy || b.running} onClick={() => void run()}>
            {b.running ? "실행 중" : "지금 백업"}
          </button>
        </dd>
      </dl>
    );
  }
  return (
    <div className={styles.job}>
      <span className={styles.jobKey}>백업</span>
      <span className={styles.jobVal}>
        {b.running ? (
          <span>
            <Shape tone="warning">▲</Shape> 실행 중
          </span>
        ) : (
          <Outcome o={b.lastSuccessAt ? "SUCCESS" : "NONE"} ok={b.lastSuccessAt ? `${dayWord(b.lastSuccessAt, now)} · ${size(b.lastSizeBytes)}` : ""} />
        )}
        <span>
          외부 보관 <Outcome o={b.offsite.outcome} ok={b.offsite.target} />
        </span>
        <span>
          복원 검증 <Outcome o={b.restoreCheck.outcome} ok="통과" />
        </span>
      </span>
      <span className={styles.jobAct}>
        <button type="button" className={styles.smallBtn} disabled={!canSuper || busy || b.running} onClick={() => void run()}>
          지금 백업
        </button>
      </span>
    </div>
  );
}

export default function JobsBlock({ jobs, now, onChanged }: { jobs: OpsJobs; now: number; onChanged: () => void }) {
  const days = Math.floor((Date.parse(jobs.certificate.expiresAt) - now) / 86_400_000);
  const certTone = days < 14 ? "danger" : days < 30 ? "warning" : "success";
  const report = jobs.dailyReport.lastSentAt;
  const sentToday = report !== null && new Date(report).toDateString() === new Date(now).toDateString();
  const due = new Date(now).getHours() >= jobs.dailyReport.hour;
  return (
    <div className={styles.jobs}>
      <BackupLine b={jobs.backup} now={now} onChanged={onChanged} />
      <div className={styles.job}>
        <span className={styles.jobKey}>인증서</span>
        <span className={styles.jobVal}>
          <span>
            <Shape tone={certTone}>{certTone === "success" ? "●" : certTone === "warning" ? "▲" : "■"}</Shape> {jobs.certificate.domain}
          </span>
          <span>
            만료 <b className="num">D-{days}</b> · {new Date(jobs.certificate.expiresAt).getMonth() + 1}.{new Date(jobs.certificate.expiresAt).getDate()}
          </span>
        </span>
        <span />
      </div>
      <div className={styles.job}>
        <span className={styles.jobKey}>일일 보고</span>
        <span className={styles.jobVal}>
          <span>
            {String(jobs.dailyReport.hour).padStart(2, "0")}:00 {jobs.dailyReport.channel}
          </span>
          {sentToday ? (
            <span>
              <Shape tone="success">●</Shape> 전송 {hm(report as string)}
            </span>
          ) : due ? (
            <span>
              <Shape tone="warning">▲</Shape> 오늘 전송 기록 없음
            </span>
          ) : (
            <span>
              <Shape tone="off">○</Shape> 오늘 {String(jobs.dailyReport.hour).padStart(2, "0")}:00 예정
            </span>
          )}
        </span>
        <span />
      </div>
      <DatabasusLink />
    </div>
  );
}
