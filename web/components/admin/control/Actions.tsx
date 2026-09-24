"use client";

/**
 * 관제센터 오른쪽 기둥. 활성 경보, 사건 타임라인, 운영 작업이다. 판단 결과가 실제 조치와 기록으로
 * 이어지는 자리라 누가 언제 무엇을 했는지를 싣는다.
 */

import Link from "next/link";
import type { ControlCenter } from "@/lib/admin/types";
import AlertList from "./Alerts";
import JobsBlock from "./Jobs";
import { PanelHead, SectionBody } from "./parts";
import TimelineBlock from "./Timeline";
import styles from "./control.module.css";

export default function ActionsColumn({ center, now, onChanged }: { center: ControlCenter | null; now: number; onChanged: () => void }) {
  const firing = center?.alerts.state === "READY" ? center.alerts.data.length : null;
  return (
    <section className={styles.column} aria-labelledby="col-actions">
      <PanelHead id="col-actions" title="조치와 기록" actions={<Link href="/admin/incidents" className={styles.stripLink}>경보·사건</Link>} />
      <div className={styles.scrollArea}>
        <div className={styles.stack} style={{ gap: "var(--space-3)" }}>
          <div className={styles.subHead}>
            활성 경보 {firing !== null && <span className={`${styles.count} num`}>{firing}</span>}
          </div>
          {center && (
            <SectionBody section={center.alerts} what="경보" compact>
              {(alerts) => <AlertList alerts={alerts} now={now} onChanged={onChanged} />}
            </SectionBody>
          )}
          <div className={styles.divider} />
          <div className={styles.subHead}>사건 타임라인</div>
          {center && (
            <SectionBody section={center.incidents} what="사건 기록" compact>
              {(log) => <TimelineBlock log={log} now={now} />}
            </SectionBody>
          )}
          <div className={styles.divider} />
          <div className={styles.subHead}>운영 작업</div>
          {center && (
            <SectionBody section={center.jobs} what="운영 작업" compact>
              {(jobs) => <JobsBlock jobs={jobs} now={now} onChanged={onChanged} />}
            </SectionBody>
          )}
        </div>
      </div>
    </section>
  );
}
