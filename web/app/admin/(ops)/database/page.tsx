"use client";

/**
 * 데이터베이스(/admin/database, 관리자 설계 4-2). 복제, 자동 전환, 백업을 본다.
 *
 * DB 승격과 복제 재구성은 사람이 하는 일이고 AI 금지 목록이다(ADR 0035). 자동 전환은 Patroni를 도입할
 * 예정이라 지금은 구성 전이고, 두 데이터 원천 모두에서 그렇게 적는다. 백업은 Databasus가 주 도구이며
 * 도입 전이라 sim 값이거나 원천 연결 전이다.
 */

import { useOpsCenter } from "@/lib/admin/opsCenter";
import Skeleton from "@/components/Skeleton";
import GrafanaLink from "@/components/admin/GrafanaLink";
import { AdminErrorState } from "@/components/admin/ResourceState";
import { alertRefMap, DbPair } from "@/components/admin/control/Infra";
import { BackupLine, DatabasusLink } from "@/components/admin/control/Jobs";
import OpsHead from "@/components/admin/control/OpsHead";
import { PanelHead, SectionBody, useClock } from "@/components/admin/control/parts";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";

export default function DatabasePage() {
  const { data, loading, refresh } = useOpsCenter();
  const now = useClock();
  const reg = data?.thresholds.state === "READY" ? data.thresholds.data : null;
  const alertRefs = alertRefMap(data);

  return (
    <div className={adminStyles.page}>
      <OpsHead
        title="데이터베이스"
        desc="복제, 자동 전환, 백업. DB 승격과 복제 재구성은 사람이 하고 AI는 하지 않습니다"
        center={data}
        now={now}
        actions={<GrafanaLink board="msp-db-replication" label="06 복제·복구 추이" />}
      />
      {data === null ? (
        loading ? <Skeleton variant="card" width="100%" height={420} /> : <AdminErrorState onRetry={() => void refresh()} />
      ) : (
        <div className={styles.split}>
          <div className={styles.stack}>
            <section className={styles.panel} aria-labelledby="db-repl">
              <PanelHead id="db-repl" title="Primary · Standby" meta="primary 한 대와 standby 한 대의 비동기 복제(ADR 0024)" />
              <SectionBody section={data.hosts} what="DB 호스트 지표">
                {(hosts) => {
                  const primary = hosts.find((h) => h.role === "DB_PRIMARY");
                  const standby = hosts.find((h) => h.role === "DB_STANDBY");
                  return primary && standby ? (
                    <DbPair primary={primary} standby={standby} repl={data.replication} reg={reg} now={now} alertRefs={alertRefs} />
                  ) : null;
                }}
              </SectionBody>
              <p className={styles.lead}>
                비동기라 지연 동안 쓰인 기록은 Primary를 잃으면 함께 잃을 수 있습니다. 지연 경보 기준은 임계값 관리의 복제 지연 경보입니다.
              </p>
            </section>
            <section className={styles.panel} aria-labelledby="db-failover">
              <PanelHead id="db-failover" title="자동 전환 · Patroni" meta="도입 예정" />
              <div className={styles.planned}>
                <span className={styles.plannedTitle}>
                  <span aria-hidden="true">◌</span> 구성 전
                </span>
                <p className={styles.lead}>
                  지금 DB 전환은 운영자가 옛 Primary를 격리하고 Standby를 승격한 뒤 앱 연결을 바꾸는 수동 절차입니다(DB 수동 페일오버 절차). Patroni를 붙이기 전까지 이 화면은 자동
                  전환 상태를 지어내지 않습니다.
                </p>
              </div>
            </section>
          </div>
          <section className={styles.panel} aria-labelledby="db-backup">
            <PanelHead id="db-backup" title="백업 · Databasus" meta="PostgreSQL 백업, PITR, 복원 검증" actions={<DatabasusLink />} />
            <SectionBody section={data.jobs} what="백업">
              {(jobs) => (
                <>
                  <BackupLine b={jobs.backup} now={now} onChanged={() => void refresh()} detail />
                  <p className={styles.lead}>
                    관제센터는 Databasus가 낸 결과를 판독만 하고 백업 설정을 바꾸지 않습니다. 즉시 실행은 Databasus의 백업 실행을 뜻합니다. 도입은 로컬 VM 예행 단계라 이 값은 시뮬레이션입니다.
                  </p>
                </>
              )}
            </SectionBody>
          </section>
        </div>
      )}
    </div>
  );
}
