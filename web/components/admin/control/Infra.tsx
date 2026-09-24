"use client";

/**
 * 인프라 판독. 호스트 카드, 조회 노드 ASG 띠, DB 복제 카드다. 게이지의 기준선은 임계값 표의
 * 현재값이라 표를 바꾸면 눈금이 함께 움직인다(§12-9). 추이는 Grafana로 보낸다.
 */

import Link from "next/link";
import type { ControlCenter, OpsHost, OpsHostRole, OpsRef, ReadTier, Replication, ThresholdKey, ThresholdRegistry } from "@/lib/admin/types";
import { refMatches, useOpsFocus } from "@/lib/admin/opsCenter";
import GrafanaLink from "../GrafanaLink";
import { MeterRail } from "../instruments/MeterRail";
import { ago, GaugeRow, PanelHead, SectionBody, Shape } from "./parts";
import styles from "./control.module.css";

export const ROLE_WORD: Record<OpsHostRole, string> = {
  APP: "매칭·주문",
  DB_PRIMARY: "Primary",
  DB_STANDBY: "Standby",
  BOT_OBS: "봇·관측",
};

type Tone = "warning" | "danger" | undefined;

function th(reg: ThresholdRegistry | null, key: ThresholdKey): number | null {
  return reg?.items.find((t) => t.key === key)?.value ?? null;
}

/** 호스트 한 대의 판정. 게이지 가운데 가장 나쁜 쪽을 따른다 */
export function hostTone(h: OpsHost, reg: ThresholdRegistry | null): Tone {
  if (h.collect === "STALE") return "warning";
  const cpu = th(reg, "host.cpu_alert");
  const mem = th(reg, "host.mem_alert");
  const free = th(reg, "host.disk_free_alert");
  const diskFree = h.diskUsedPct === null ? null : 100 - h.diskUsedPct;
  if (free !== null && diskFree !== null && diskFree < free / 2) return "danger";
  if (cpu !== null && h.cpuPct !== null && h.cpuPct >= cpu) return "warning";
  if (mem !== null && h.memPct !== null && h.memPct >= mem) return "warning";
  if (free !== null && diskFree !== null && diskFree < free) return "warning";
  return undefined;
}

/** 경보가 가리키는 자리. 근거 칩이 경보를 가리켜도 그 경보의 카드가 함께 켜진다 */
export function alertRefMap(center: ControlCenter | null): Map<string, OpsRef | null> {
  const m = new Map<string, OpsRef | null>();
  if (center?.alerts.state === "READY") for (const a of center.alerts.data) m.set(a.id, a.ref);
  return m;
}

export function HostCard({ host, reg, now, alertRefs, board = true }: {
  host: OpsHost;
  reg: ThresholdRegistry | null;
  now: number;
  alertRefs: Map<string, OpsRef | null>;
  board?: boolean;
}) {
  const focus = useOpsFocus((s) => s.ref);
  const tone = hostTone(host, reg);
  const diskFreeTh = th(reg, "host.disk_free_alert");
  const creditTh = th(reg, "host.cpu_credit_alert");
  const lowCredit = host.cpuCredits !== null && creditTh !== null && host.cpuCredits < creditTh;
  const lit = refMatches(focus, { kind: "HOST", id: host.id }, alertRefs);
  return (
    <article className={styles.host} data-tone={tone} data-focus={lit ? "" : undefined} aria-label={`${host.id} ${ROLE_WORD[host.role]}`}>
      <div className={styles.hostTop}>
        <span className={styles.hostName}>
          <Shape tone={tone === "danger" ? "danger" : tone === "warning" ? "warning" : host.collect === "MISSING" ? "off" : "success"}>
            {tone === "danger" ? "■" : tone === "warning" ? "▲" : host.collect === "MISSING" ? "◌" : "●"}
          </Shape>
          {host.id}
        </span>
        <span className={styles.hostRole}>{ROLE_WORD[host.role]}</span>
      </div>
      <GaugeRow label="CPU" name="CPU 사용률" value={host.cpuPct} threshold={th(reg, "host.cpu_alert")} />
      <GaugeRow label="MEM" name="메모리 사용률" value={host.memPct} threshold={th(reg, "host.mem_alert")} />
      <GaugeRow
        label="DISK"
        name="디스크 사용률"
        value={host.diskUsedPct}
        threshold={diskFreeTh === null ? null : 100 - diskFreeTh}
        danger={diskFreeTh === null ? undefined : 100 - diskFreeTh / 2}
      />
      <div className={styles.hostFoot}>
        <span title={`${host.instanceType} · ${host.az}`}>
          크레딧 <b className="num" data-tone={lowCredit ? "info" : undefined}>{host.cpuCredits ?? "◌"}</b>
          {lowCredit && <span> · 경보 {creditTh} 미만</span>}
        </span>
        <span>{host.collectedAt === null ? "◌ 미수집" : `수집 ${ago(host.collectedAt, now)}`}</span>
      </div>
      {board && (
        <div className={styles.hostFoot}>
          <span>
            {host.instanceType} · {host.az}
          </span>
          <GrafanaLink board="msp-infra" label="추이" />
        </div>
      )}
    </article>
  );
}

export function AsgBand({ tier, reg, alertRefs }: { tier: ReadTier; reg: ThresholdRegistry | null; alertRefs: Map<string, OpsRef | null> }) {
  const focus = useOpsFocus((s) => s.ref);
  const out = th(reg, "read.scale_out_cpu");
  const inn = th(reg, "read.scale_in_cpu");
  const avg = tier.avgCpuPct;
  const hot = avg !== null && out !== null && avg >= out;
  const lit = refMatches(focus, { kind: "READ_TIER" }, alertRefs);
  const serving = tier.nodes.filter((n) => n.state === "IN_SERVICE").length;
  return (
    <section className={styles.asg} data-tone={hot ? "warning" : undefined} data-focus={lit ? "" : undefined} aria-label="조회 노드 ASG">
      <div className={styles.asgHead}>
        <span className={styles.asgTitle}>조회 노드 ASG</span>
        <span className={`${styles.asgCount} num`}>
          {serving}/{tier.desired}대
        </span>
        <span className={styles.asgBounds}>
          최소 {tier.min} · 최대 {tier.max}
        </span>
        <span className={styles.asgAvg}>
          평균 CPU{" "}
          <b className="num" data-tone={hot ? "warning" : undefined}>
            {hot && <span aria-hidden="true">▲ </span>}
            {avg === null ? "◌" : `${avg}%`}
          </b>
        </span>
      </div>
      <div className={styles.asgNodes}>
        {tier.nodes.map((n) => (
          <span key={n.id} className={styles.asgNode} data-state={n.state}>
            <Shape tone={n.state === "IN_SERVICE" ? "success" : n.state === "OFF" ? "off" : "warning"}>
              {n.state === "IN_SERVICE" ? "●" : n.state === "OFF" ? "○" : "▲"}
            </Shape>
            <span className={styles.asgNodeId}>{n.id}</span>
            <span className={`${styles.asgNodeVal} num`}>
              {n.state === "IN_SERVICE" ? `${n.cpuPct ?? "◌"}%` : n.state === "PENDING" ? "기동 중" : n.state === "DRAINING" ? "드레이닝" : "꺼짐"}
            </span>
          </span>
        ))}
      </div>
      <div className={styles.asgRail}>
        <MeterRail value={avg ?? 0} max={100} threshold={out ?? undefined} tone={hot ? "warning" : undefined}
          marks={inn === null ? undefined : [{ at: inn, label: `축소 ${inn}%` }]} />
        <div className={styles.asgTicks} aria-hidden="true">
          {inn !== null && <span className={styles.asgTick} style={{ left: `${inn}%` }}>축소 {inn}%</span>}
          {out !== null && <span className={styles.asgTick} style={{ left: `${out}%` }}>증설 {out}%</span>}
        </div>
      </div>
    </section>
  );
}

const STREAM: Record<Replication["stream"], { word: string; tone: "success" | "warning" | "danger"; mark: string }> = {
  STREAMING: { word: "스트리밍", tone: "success", mark: "●" },
  CATCHUP: { word: "따라잡는 중", tone: "warning", mark: "▲" },
  STOPPED: { word: "끊김", tone: "danger", mark: "■" },
};

export function ReplicationCard({ repl, reg, alertRefs }: { repl: Replication; reg: ThresholdRegistry | null; alertRefs: Map<string, OpsRef | null> }) {
  const focus = useOpsFocus((s) => s.ref);
  const lagTh = th(reg, "db.replication_lag_alert");
  const late = repl.lagSec !== null && lagTh !== null && repl.lagSec >= lagTh;
  const st = STREAM[repl.stream];
  const lit = refMatches(focus, { kind: "REPLICATION" }, alertRefs);
  return (
    <section className={styles.repl} data-tone={late || st.tone !== "success" ? "warning" : undefined} data-focus={lit ? "" : undefined} aria-label="DB 복제">
      <div className={styles.replHead}>
        <span className={styles.asgTitle}>DB 복제 · 비동기</span>
        <span className={styles.replState}>
          <Shape tone={st.tone}>{st.mark}</Shape>
          {st.word}
        </span>
      </div>
      <div className={styles.replFlow}>
        <span className={styles.replNode}>
          <b>{repl.primary}</b> Primary
        </span>
        <span className={styles.replArrow} aria-hidden="true" />
        <span className={styles.replNode}>
          <b>{repl.standby}</b> Standby
        </span>
      </div>
      <div className={styles.lag}>
        <span>
          지연 <b className="num">{repl.lagBytes === null ? "◌" : `${Math.round(repl.lagBytes / 1024)}KB`}</b> ·{" "}
          <b className="num">{repl.lagSec === null ? "◌" : `${repl.lagSec}초`}</b>
          {lagTh !== null && <> · 경보 {lagTh}초</>}
        </span>
        <span className={styles.lagRail}>
          <MeterRail value={repl.lagSec ?? 0} max={lagTh === null ? 60 : lagTh * 2} threshold={lagTh ?? undefined} tone={late ? "warning" : undefined} />
        </span>
      </div>
    </section>
  );
}

/** 관제센터 왼쪽 기둥 */
export default function InfraColumn({ center, now }: { center: ControlCenter | null; now: number }) {
  const reg = center?.thresholds.state === "READY" ? center.thresholds.data : null;
  const alertRefs = alertRefMap(center);
  return (
    <section className={styles.column} aria-labelledby="col-infra">
      <PanelHead
        id="col-infra"
        title="인프라"
        meta="현재값과 임계 눈금"
        actions={
          <>
            <Link href="/admin/hosts" className={styles.stripLink}>서버·노드</Link>
            <GrafanaLink board="msp-infra" label="추이" />
          </>
        }
      />
      <div className={styles.scrollArea}>
        <div className={styles.stack}>
          {center === null ? null : (
            <SectionBody section={center.hosts} what="호스트 지표">
              {(hosts) => (
                <div className={styles.hostGrid}>
                  {hosts.map((h) => (
                    <HostCard key={h.id} host={h} reg={reg} now={now} alertRefs={alertRefs} board={false} />
                  ))}
                </div>
              )}
            </SectionBody>
          )}
          {center !== null && (
            <SectionBody section={center.readTier} what="조회 노드 ASG" compact>
              {(tier) => <AsgBand tier={tier} reg={reg} alertRefs={alertRefs} />}
            </SectionBody>
          )}
          {center !== null && (
            <SectionBody section={center.replication} what="DB 복제" compact>
              {(repl) => <ReplicationCard repl={repl} reg={reg} alertRefs={alertRefs} />}
            </SectionBody>
          )}
        </div>
      </div>
    </section>
  );
}
