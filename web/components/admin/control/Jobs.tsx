"use client";

/**
 * 운영 작업 상태. 백업은 Databasus의 결과를 판독만 하고 설정을 바꾸지 않는다(관리자 설계 4-4).
 * 인증서 만료와 일일 보고 전송은 날짜로 판정한다. http 원천은 Databasus와 인증서 만료가 연결 전이라 백업은
 * 백업 지표(마지막 성공, 복원 검증)만, 인증서는 연결 전으로 그린다
 */

import Link from "next/link";
import { useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { CERT_SOURCE, certDateWord, certDaysLeft, certTone, soonestCert } from "@/lib/admin/certs";
import { useCan } from "@/lib/admin/store";
import type { CertificateInfo, JobOutcome, OpsBackup, OpsJobs } from "@/lib/admin/types";
import KeepNames from "../KeepNames";
import { toast } from "@/components/Toast";
import ConfirmDialog from "../ConfirmDialog";
import adminStyles from "../admin.module.css";
import { kstDay, kstParts } from "@/lib/admin/timeText";
import { ago, hm, Shape, useClock } from "./parts";
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

/** KST 날짜가 같은가. 콘솔의 날짜 경계는 KST 자정이다(§12-12) */
function sameKstDay(a: number, b: number): boolean {
  return kstDay(a) === kstDay(b);
}

function dayWord(iso: string, now: number): string {
  const t = Date.parse(iso);
  const k = kstParts(t);
  const word = sameKstDay(t, now) ? "오늘" : sameKstDay(t, now + 86_400_000) ? "내일" : `${k.month}.${k.day}`;
  return `${word} ${hm(iso)}`;
}

export function BackupLine({ b, onChanged, detail }: { b: OpsBackup; onChanged: () => void; detail?: boolean }) {
  // 날짜와 'n분 전'만 읽으므로 분 단위로만 다시 그린다
  const now = useClock(60_000);
  const canSuper = useCan("SUPER");
  const [busy, setBusy] = useState(false);
  const [asking, setAsking] = useState(false);
  const run = async () => {
    setBusy(true);
    try {
      await getAdminApi().runBackupNow();
      toast("Databasus 백업 시작", "info");
      setAsking(false);
      onChanged();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "백업 실행 실패", "error");
    } finally {
      setBusy(false);
    }
  };
  // 즉시 실행은 확인 대화상자를 거친다(관리자 설계 4-10-3). 대상과 영향, 오늘 실행 수를 다시 보인다
  const confirm = asking && (
    <ConfirmDialog
      title="Databasus 백업을 지금 실행합니다"
      description="읽기 전용 계정으로 전체 백업과 외부 보관, 복원 검증을 한 번 돌립니다. 운영 DB 쓰기에는 영향이 없고 백업 한 벌만큼 보관 공간을 씁니다."
      target={`exchange DB · 오늘 즉시 실행 ${b.manualRunsToday === null ? "수 원천 연결 전" : `${b.manualRunsToday}회`}`}
      blastRadius="감사 로그(ops.backup.run)와 사건 밖 운영 기록에 남습니다"
      confirmLabel="지금 백업"
      tone="approve"
      busy={busy}
      onConfirm={() => void run()}
      onClose={() => setAsking(false)}
    />
  );
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
        <dt>외부 보관{b.offsite ? `(${b.offsite.target})` : ""}</dt>
        <dd>
          {b.offsite ? (
            <>
              <Outcome o={b.offsite.outcome} ok="보관 완료" /> {b.offsite.at && `· ${dayWord(b.offsite.at, now)}`}
            </>
          ) : (
            <span>
              <Shape tone="off">◌</Shape> 원천 연결 전 · Databasus
            </span>
          )}
        </dd>
        <dt>복원 검증</dt>
        <dd>
          <Outcome o={b.restoreCheck.outcome} ok="통과" fail="실패" />
          {b.restoreCheck.lastSuccessAt && <> · 마지막 성공 {dayWord(b.restoreCheck.lastSuccessAt, now)}</>}
          {b.restoreCheck.lastFailureAt && <> · 마지막 실패 {dayWord(b.restoreCheck.lastFailureAt, now)}</>}
        </dd>
        <dt>다음 예약</dt>
        <dd>{b.nextAt ? <b className="num">{dayWord(b.nextAt, now)}</b> : "예약 없음"}</dd>
        {/* 단추가 있는 줄은 이름과 글을 단추 높이의 가운데에 맞춘다 */}
        <dt data-mid="">즉시 실행</dt>
        <dd className={styles.kvAction}>
          <span>{b.manualRunsToday === null ? "오늘 실행 수 원천 연결 전" : `오늘 ${b.manualRunsToday}회`} · AI 자동 실행은 하루 1회{b.canRun ? "" : " · Databasus 연결 전이라 실행할 수 없습니다"}</span>
          <button type="button" className={adminStyles.neutralBtn} disabled={!canSuper || busy || b.running || !b.canRun} onClick={() => setAsking(true)}>
            {b.running ? "실행 중" : "지금 백업"}
          </button>
          {confirm}
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
        {b.offsite && (
          <span>
            외부 보관 <Outcome o={b.offsite.outcome} ok={b.offsite.target} />
          </span>
        )}
        <span>
          복원 검증 <Outcome o={b.restoreCheck.outcome} ok="통과" />
        </span>
      </span>
      <span className={styles.jobAct}>
        <button type="button" className={styles.smallBtn} disabled={!canSuper || busy || b.running || !b.canRun} onClick={() => setAsking(true)}
          title={b.canRun ? undefined : "Databasus 연결 전이라 실행할 수 없습니다"}>
          지금 백업
        </button>
        {confirm}
      </span>
    </div>
  );
}

/** 원천이 없는 작업 줄. 연결 전 모양(◌)으로 붙일 원천을 적는다 */
function MissingJob({ name, source }: { name: string; source: string }) {
  return (
    <div className={styles.job}>
      <span className={styles.jobKey}>{name}</span>
      <span className={styles.jobVal}>
        <span>
          <Shape tone="off">◌</Shape> 원천 연결 전 · {source}
        </span>
      </span>
      <span />
    </div>
  );
}

/**
 * 가장 먼저 만료되는 인증서 한 줄. 네트워크·보안의 인증서 표, 사이드바 배지와 같은 판정(certs.ts)이라 세 자리가 같은 날과
 * 모양을 적는다(30일 이내 ▲, 7일 이내 ■). 나머지 인증서는 개수만 적고 네트워크·보안으로 보낸다
 */
function CertificateLine({ certs, now }: { certs: CertificateInfo[]; now: number }) {
  const cert = soonestCert(certs);
  if (!cert) return <MissingJob name="인증서" source="인증서 기록 없음" />;
  const days = certDaysLeft(cert, now);
  const tone = certTone(days);
  return (
    <div className={styles.job}>
      <span className={styles.jobKey}>인증서</span>
      <span className={styles.jobVal}>
        <span>
          <Shape tone={tone}>{tone === "success" ? "●" : tone === "warning" ? "▲" : "■"}</Shape> <KeepNames text={cert.domain} />
        </span>
        <span>
          만료 <b className="num">D-{days}</b> · <span className="num">{certDateWord(cert)}</span>
        </span>
        {certs.length > 1 && (
          <Link href="/admin/security" className={styles.stripLink}>
            외 {certs.length - 1}장
          </Link>
        )}
      </span>
      <span />
    </div>
  );
}

export default function JobsBlock({ jobs, onChanged }: { jobs: OpsJobs; onChanged: () => void }) {
  const now = useClock(60_000);
  const report = jobs.dailyReport.lastSentAt;
  const run = jobs.dailyReport.lastRunAt ?? null;
  const hour = jobs.dailyReport.hour;
  const sentToday = report !== null && sameKstDay(Date.parse(report), now);
  const ranToday = run !== null && sameKstDay(Date.parse(run), now);
  const due = hour !== null && kstParts(now).h >= hour;
  const hh = hour === null ? null : String(hour).padStart(2, "0");
  return (
    <div className={styles.jobs}>
      {jobs.backup ? <BackupLine b={jobs.backup} onChanged={onChanged} /> : <MissingJob name="백업" source={jobs.backupSource ?? "Databasus"} />}
      {jobs.certificates ? <CertificateLine certs={jobs.certificates} now={now} /> : <MissingJob name="인증서" source={CERT_SOURCE} />}
      <div className={styles.job}>
        <span className={styles.jobKey}>일일 보고</span>
        <span className={styles.jobVal}>
          <span>
            {hh === null ? "예약 시각 원천 연결 전" : `${hh}:00`} {jobs.dailyReport.channel}
          </span>
          {sentToday ? (
            <span>
              <Shape tone="success">●</Shape> 전송 {hm(report as string)}
            </span>
          ) : ranToday ? (
            <span>
              <Shape tone="off">○</Shape> 실행 {hm(run as string)} · 전송 기록 없음
            </span>
          ) : due ? (
            <span>
              <Shape tone="warning">▲</Shape> 오늘 전송 기록 없음
            </span>
          ) : hh !== null ? (
            <span>
              <Shape tone="off">○</Shape> 오늘 {hh}:00 예정
            </span>
          ) : (
            <span>
              <Shape tone="off">◌</Shape> 오늘 실행·전송 기록 없음
            </span>
          )}
        </span>
        <span />
      </div>
      <DatabasusLink />
    </div>
  );
}
