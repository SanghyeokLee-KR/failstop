"use client";

/**
 * 데이터베이스(/admin/database, 관리자 설계 4-10-3). 데이터가 복제·백업되고 있고 Primary를 잃으면 어떻게 되돌리는지에 답한다.
 * 왼쪽 칸은 DB 묶음(Primary·Standby와 복제), 복제 방식, DB 통계, 느린 질의이고 오른쪽 칸은 백업(Databasus), 전환 절차(DB 수동
 * 승격 런북), 엄격 동기 설명이다(레이아웃 §7 두 칸 1 : 1).
 *
 * 복제 방식은 두 원천에서 같은 사실이다(비동기 복제와 수동 승격, Patroni 자동 전환은 다른 브랜치에서 구성 중). DB 통계는 sim
 * 예시이고 http는 원천 연결 전이다. 느린 질의는 pg_stat_statements를 적재하지 않아 두 원천 모두 연결 전이다. DB 승격과 복제
 * 재구성은 사람이 SSM 세션에서 하고 AI 금지 목록 1이다. 이 화면에는 승격 단추가 없다.
 */

import Link from "next/link";
import type { ReactNode } from "react";
import { bytesWord, DB_STATS_SOURCE, DB_TOPOLOGY, SLOW_QUERY_SOURCE, walGap } from "@/lib/admin/dbFacts";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import { runbookById, runbookHref } from "@/lib/admin/runbooks";
import type { DbStats, OpsSection, Replication } from "@/lib/admin/types";
import GrafanaLink from "@/components/admin/GrafanaLink";
import { AdminErrorState } from "@/components/admin/ResourceState";
import { alertRefMap, DbPair } from "@/components/admin/control/Infra";
import { BackupLine, DatabasusLink } from "@/components/admin/control/Jobs";
import OpsHead from "@/components/admin/control/OpsHead";
import PageSkeleton from "@/components/admin/control/PageSkeleton";
import { NotConnected, PanelHead, SectionBody, SectionGap } from "@/components/admin/control/parts";
import { StateChip, StatStrip, StatTile } from "@/components/admin/ui";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";
import dbStyles from "@/components/admin/database/database.module.css";

const PROMOTE = runbookById("DB_PROMOTE");

const STREAM_WORD: Record<Replication["stream"], { tone: "success" | "warning" | "danger"; word: string }> = {
  STREAMING: { tone: "success", word: "스트리밍" },
  CATCHUP: { tone: "warning", word: "따라잡는 중" },
  STOPPED: { tone: "danger", word: "끊김" },
};

/** 복제 방식. 지금 구성과 적용 전 구성을 한 표에 가른다. 두 원천이 같은 사실이다 */
function ReplicationMode({ repl }: { repl: OpsSection<Replication> }) {
  const stream = repl.state === "READY" ? STREAM_WORD[repl.data.stream] : null;
  return (
    <dl className={dbStyles.modes}>
      <div className={dbStyles.mode}>
        <dt>복제</dt>
        <dd>
          <span className={dbStyles.modeValue}>{DB_TOPOLOGY.mode === "ASYNC" ? "비동기 스트리밍" : "엄격 동기"}</span>
          {stream ? <StateChip tone={stream.tone} label={stream.word} /> : <StateChip tone="off" shape="dashed" label="복제 원천 연결 전" />}
          <span className={dbStyles.modeNote}>Primary를 잃으면 지연 동안 쓰인 기록을 함께 잃을 수 있습니다 · ADR 0024</span>
        </dd>
      </div>
      <div className={dbStyles.mode}>
        <dt>전환</dt>
        <dd>
          <span className={dbStyles.modeValue}>수동 승격</span>
          <StateChip tone="neutral" shape="ring" label="사람이 실행" />
          <span className={dbStyles.modeNote}>운영자가 옛 Primary를 격리하고 Standby를 승격합니다. AI 금지 목록 1이라 어느 모드에서도 AI가 하지 않습니다</span>
        </dd>
      </div>
      <div className={dbStyles.mode}>
        <dt>자동 전환</dt>
        <dd>
          <span className={dbStyles.modeValue}>Patroni · 엄격 동기</span>
          <StateChip tone="off" shape="dashed" label={DB_TOPOLOGY.failover === "PATRONI" ? "적용" : "구성 중"} />
          <span className={dbStyles.modeNote}>다른 작업 브랜치에서 설계·구현 중이고 이 환경에 적용하지 않았습니다 · ADR 0036</span>
        </dd>
      </div>
    </dl>
  );
}

/** DB 통계 칸 하나. 원천이 없으면(http) 값 자리에 '◌'과 원천 연결 전이다 */
function stat(stats: DbStats | null, value: (s: DbStats) => ReactNode, sub: (s: DbStats) => ReactNode): { value: ReactNode; sub: ReactNode } {
  if (stats === null) return { value: <span className={dbStyles.missing}>◌</span>, sub: "원천 연결 전" };
  return { value: value(stats), sub: sub(stats) };
}

function DbStatsPanel({ section, repl }: { section: OpsSection<DbStats>; repl: OpsSection<Replication> }) {
  const s = section.state === "READY" ? section.data : null;
  const lagBytes = repl.state === "READY" ? repl.data.lagBytes : null;
  const conn = stat(s, (x) => (x.connections ? `${x.connections.used}/${x.connections.max}` : "◌"), (x) => (x.connections ? `상한의 ${Math.round((x.connections.used / x.connections.max) * 100)}% · max_connections` : "원천 없음"));
  const tps = stat(s, (x) => x.tps ?? "◌", () => "초당 트랜잭션 · Primary");
  const cache = stat(s, (x) => (x.cacheHitPct === null ? "◌" : x.cacheHitPct.toFixed(2)), () => "버퍼 캐시 적중률");
  const primary = stat(s, (x) => <code className={dbStyles.lsn}>{x.wal?.primaryLsn ?? "◌"}</code>, () => "Primary 현재 WAL 위치");
  const replay = stat(
    s,
    (x) => <code className={dbStyles.lsn}>{x.wal?.standbyReplayLsn ?? "◌"}</code>,
    (x) => {
      const gap = walGap(x);
      return gap === null ? "Standby 재생 위치" : `재생 뒤처짐 ${bytesWord(gap)}${lagBytes !== null ? " · DB 묶음 지연과 같은 값" : ""}`;
    },
  );
  const size = stat(s, (x) => (x.sizeBytes === null ? "◌" : bytesWord(x.sizeBytes)), () => "exchange DB 크기");
  return (
    <section className={styles.panel} aria-labelledby="db-stats">
      <PanelHead id="db-stats" title="DB 통계" meta={s ? "postgres_exporter 지표" : undefined} />
      <StatStrip label="DB 통계" count={6}>
        <StatTile label="연결 수" value={conn.value} sub={conn.sub} />
        <StatTile label="초당 트랜잭션" value={tps.value} unit={s ? "TPS" : undefined} sub={tps.sub} />
        <StatTile label="캐시 적중" value={cache.value} unit={s ? "%" : undefined} sub={cache.sub} />
        <StatTile label="Primary WAL" value={primary.value} sub={primary.sub} />
        <StatTile label="Standby 재생" value={replay.value} sub={replay.sub} />
        <StatTile label="DB 크기" value={size.value} sub={size.sub} />
      </StatStrip>
      {section.state !== "READY" && <p className={dbStyles.source}>◌ 원천 연결 전 · {section.state === "NOT_CONNECTED" ? section.source : DB_STATS_SOURCE}</p>}
    </section>
  );
}

export default function DatabasePage() {
  const { data, loading, refresh } = useOpsCenter();
  const reg = data?.thresholds.state === "READY" ? data.thresholds.data : null;
  const alertRefs = alertRefMap(data);

  return (
    <div className={adminStyles.page}>
      <OpsHead title="데이터베이스" center={data} actions={<GrafanaLink board="msp-db-replication" label="06 복제·복구 추이" />} />
      {data === null ? (
        loading ? <PageSkeleton columns={[1, 1]} rows={[4, 4]} label="데이터베이스 판을 불러오는 중입니다" /> : <AdminErrorState onRetry={() => void refresh()} />
      ) : (
        <div className={styles.split}>
          <div className={styles.stack}>
            <section className={styles.panel} aria-labelledby="db-repl">
              <PanelHead id="db-repl" title="Primary · Standby" meta="PostgreSQL 17 · 노드 이름을 누르면 상세가 열립니다" />
              <SectionBody section={data.hosts} what="DB 호스트 지표">
                {(hosts) => {
                  const primary = hosts.find((h) => h.role === "DB_PRIMARY");
                  const standby = hosts.find((h) => h.role === "DB_STANDBY");
                  return primary && standby ? (
                    <DbPair primary={primary} standby={standby} repl={data.replication} reg={reg} alertRefs={alertRefs} />
                  ) : (
                    <NotConnected what="DB 호스트" source="Prometheus 호스트 지표에 DB 두 대가 없음" compact />
                  );
                }}
              </SectionBody>
              <p className={styles.lead}>지연 경보 기준은 임계값 관리의 복제 지연 경보입니다. 지연만큼이 수동 승격 때 잃을 수 있는 범위입니다.</p>
            </section>
            <section className={styles.panel} aria-labelledby="db-mode">
              <PanelHead id="db-mode" title="복제 방식" meta="두 데이터 원천에서 같은 사실" />
              <ReplicationMode repl={data.replication} />
            </section>
            <DbStatsPanel section={data.database} repl={data.replication} />
            <section className={styles.panel} aria-labelledby="db-slow">
              <PanelHead id="db-slow" title="느린 질의" />
              {data.database.state === "READY" ? (
                <SectionBody section={data.database.data.slowQueries} what="느린 질의">
                  {() => null}
                </SectionBody>
              ) : (
                <SectionGap section={{ state: "NOT_CONNECTED", source: SLOW_QUERY_SOURCE }} what="느린 질의" />
              )}
            </section>
          </div>
          <div className={styles.stack}>
            <section className={styles.panel} aria-labelledby="db-backup">
              <PanelHead id="db-backup" title="백업 · Databasus" meta="PostgreSQL 백업, PITR, 복원 검증" actions={<DatabasusLink />} />
              <SectionBody section={data.jobs} what="백업">
                {(jobs) => (
                  <>
                    {jobs.backup ? (
                      <BackupLine b={jobs.backup} onChanged={() => void refresh(true)} detail />
                    ) : (
                      <NotConnected what="백업" source={jobs.backupSource ?? "Databasus"} compact />
                    )}
                    <p className={styles.lead}>관제센터는 Databasus가 낸 결과를 판독만 하고 백업 설정을 바꾸지 않습니다. 즉시 실행은 Databasus의 백업 실행을 뜻합니다.</p>
                  </>
                )}
              </SectionBody>
            </section>

            <section className={styles.panel} aria-labelledby="db-promote">
              <PanelHead
                id="db-promote"
                title="전환 절차 · DB 수동 승격"
                meta={<span className="num">{PROMOTE.steps.length}단계</span>}
                actions={
                  <Link href={runbookHref(PROMOTE.id)} className={styles.stripLink}>
                    런북에서 명령과 확인 조건 보기
                  </Link>
                }
              />
              <ol className={dbStyles.steps}>
                {PROMOTE.steps.map((st, i) => (
                  <li key={st.title} className={dbStyles.step}>
                    <span className={`${dbStyles.stepNo} num`}>{i + 1}</span>
                    <span className={dbStyles.stepBody}>
                      <span className={dbStyles.stepTitle}>{st.title}</span>
                      <span className={dbStyles.stepCheck}>{st.check}</span>
                    </span>
                  </li>
                ))}
              </ol>
              <p className={dbStyles.who}>운영자가 SSM 세션에서 실행합니다 · 이 콘솔에는 실행 단추가 없습니다 · 승격은 되돌릴 수 없습니다</p>
            </section>

            <section className={styles.panel} aria-labelledby="db-sync">
              <PanelHead id="db-sync" title="엄격 동기에서 Standby를 잃으면" actions={<StateChip tone="off" shape="dashed" label="Patroni 구성 중" />} />
              <div className={dbStyles.sync}>
                <div className={dbStyles.syncCase} data-kind="after">
                  <span className={dbStyles.syncHead}>Patroni 적용 뒤 · 엄격 동기 · ADR 0036</span>
                  <p>
                    Patroni를 적용하면 복제는 엄격 동기입니다. Standby가 없으면 Primary가 살아 있어도 쓰기가 멈추고, 거래 화면은 거래 일시 중단으로 바뀌며 조회는 계속됩니다. Standby가 돌아오면 자동으로
                    재개합니다.
                  </p>
                </div>
                <div className={dbStyles.syncCase} data-kind="now">
                  <span className={dbStyles.syncHead}>지금 구성 · 비동기 · ADR 0024</span>
                  <p>지금 구성(비동기)에서는 Standby가 멈춰도 쓰기가 계속되고, Primary를 잃으면 복제되지 않은 기록을 잃을 수 있습니다.</p>
                </div>
              </div>
            </section>
          </div>
        </div>
      )}
    </div>
  );
}
