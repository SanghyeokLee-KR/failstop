"use client";

/**
 * 관제센터 오른쪽 기둥. 활성 경보, 사건 타임라인, 운영 작업이다. 판단 결과가 실제 조치와 기록으로
 * 이어지는 자리라 누가 언제 무엇을 했는지를 싣는다.
 */

import { memo } from "react";
import Link from "next/link";
import type { IncidentLog, OpsAlert, OpsJobs, OpsSection } from "@/lib/admin/types";
import AlertList from "./Alerts";
import JobsBlock from "./Jobs";
import { PanelHead, SectionBody } from "./parts";
import TimelineBlock from "./Timeline";
import styles from "./control.module.css";

/** 경보·사건·운영 작업 세 칸만 받는다. 세 칸이 그대로면 판이 매초 새로 와도 다시 그리지 않는다 */
function ActionsColumn({ alerts, incidents, jobs, onChanged }: {
  alerts: OpsSection<OpsAlert[]>;
  incidents: OpsSection<IncidentLog>;
  jobs: OpsSection<OpsJobs>;
  onChanged: () => void;
}) {
  const firing = alerts.state === "READY" ? alerts.data.length : null;
  return (
    <section className={styles.column} aria-labelledby="col-actions">
      <PanelHead id="col-actions" title="조치와 기록" actions={<Link href="/admin/incidents" className={styles.stripLink}>경보·사건</Link>} />
      <div className={styles.scrollArea}>
        <div className={styles.stack} style={{ gap: "var(--space-3)" }}>
          <div className={styles.subHead}>
            활성 경보 {firing !== null && <span className={`${styles.count} num`}>{firing}</span>}
          </div>
          <SectionBody section={alerts} what="경보" compact>
            {(list) => <AlertList alerts={list} onChanged={onChanged} />}
          </SectionBody>
          <div className={styles.divider} />
          {incidents.state === "READY" ? (
            <TimelineBlock log={incidents.data} />
          ) : (
            <>
              <div className={styles.subHead}>사건 타임라인</div>
              <SectionBody section={incidents} what="사건 기록" compact>
                {() => null}
              </SectionBody>
            </>
          )}
          <div className={styles.divider} />
          <div className={styles.subHead}>운영 작업</div>
          <SectionBody section={jobs} what="운영 작업" compact>
            {(j) => <JobsBlock jobs={j} onChanged={onChanged} />}
          </SectionBody>
        </div>
      </div>
    </section>
  );
}

export default memo(ActionsColumn);
