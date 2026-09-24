"use client";

/**
 * 인프라 판독(관리자 설계 4-4). 역할 묶음으로 나누고 카드마다 정체를 적는다. 앱(매칭 리더 두 대와 맡은
 * 마켓 묶음), 데이터베이스(Primary·Standby와 그 사이의 복제), 조회 ASG, 관측(bot-1), 온프레미스(ai-1)다.
 * 게이지의 눈금은 임계값 표의 현재값이라 표를 바꾸면 눈금이 함께 움직인다(§12-9). 추이는 Grafana로 보낸다.
 */

import Link from "next/link";
import type { ReactNode } from "react";
import type {
  AiServer,
  ControlCenter,
  OpsHost,
  OpsHostRole,
  OpsRef,
  OpsSection,
  ReadTier,
  Replication,
  ThresholdKey,
  ThresholdRegistry,
} from "@/lib/admin/types";
import { refMatches, useOpsFocus } from "@/lib/admin/opsCenter";
import GrafanaLink from "../GrafanaLink";
import { MeterRail } from "../instruments/MeterRail";
import { ago, GaugeRow, NotConnected, PanelHead, Shape } from "./parts";
import styles from "./control.module.css";

/** 카드 둘째 줄의 정체. 서비스와 역할을 적는다 */
export const HOST_KIND: Record<OpsHostRole, string> = {
  APP: "Spring Boot · 매칭 리더",
  DB_PRIMARY: "PostgreSQL 17 · Primary",
  DB_STANDBY: "PostgreSQL 17 · Standby",
  BOT_OBS: "시장 봇 · 관측",
};
/** bot-1이 시장 봇과 함께 싣는 관측 서비스 */
const BOT_SERVICES = "Prometheus · Grafana · Alertmanager · 보고";

type Tone = "warning" | "danger" | undefined;

export interface NodeState {
  tone: "success" | "warning" | "danger" | "off";
  shape: string;
  word: string;
}

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

/** 카드의 상태 문구. 운영 중, 임계치 초과, 수집 끊김 세 가지로 적는다 */
export function hostState(h: OpsHost, reg: ThresholdRegistry | null): NodeState {
  if (h.collect === "MISSING") return { tone: "off", shape: "◌", word: "수집 끊김" };
  if (h.collect === "STALE") return { tone: "warning", shape: "▲", word: "수집 끊김" };
  const t = hostTone(h, reg);
  if (t === "danger") return { tone: "danger", shape: "■", word: "임계치 초과" };
  if (t === "warning") return { tone: "warning", shape: "▲", word: "임계치 초과" };
  return { tone: "success", shape: "●", word: "운영 중" };
}

/** 경보가 가리키는 자리. 근거 칩이 경보를 가리켜도 그 경보의 카드가 함께 켜진다 */
export function alertRefMap(center: ControlCenter | null): Map<string, OpsRef | null> {
  const m = new Map<string, OpsRef | null>();
  if (center?.alerts.state === "READY") for (const a of center.alerts.data) m.set(a.id, a.ref);
  return m;
}

/** 인프라 칸 머리의 집계. 켜 둔 노드만 세고, 기동·드레이닝처럼 넘어가는 중인 노드는 주의로 센다 */
export function infraCounts(c: ControlCenter): { ok: number; warn: number; down: number } | null {
  if (c.hosts.state !== "READY") return null;
  const reg = c.thresholds.state === "READY" ? c.thresholds.data : null;
  const out = { ok: 0, warn: 0, down: 0 };
  for (const h of c.hosts.data) {
    const s = hostState(h, reg).tone;
    if (s === "success") out.ok += 1;
    else if (s === "danger") out.down += 1;
    else out.warn += 1;
  }
  if (c.readTier.state === "READY") {
    const out2 = th(reg, "read.scale_out_cpu");
    const hot = c.readTier.data.avgCpuPct !== null && out2 !== null && c.readTier.data.avgCpuPct >= out2;
    for (const n of c.readTier.data.nodes) {
      if (n.state === "OFF") continue;
      if (n.state === "IN_SERVICE" && !hot) out.ok += 1;
      else out.warn += 1;
    }
  }
  if (c.aiServer.state === "READY") {
    if (c.aiServer.data.link === "OK") out.ok += 1;
    else out.warn += 1;
  }
  return out;
}

function Gauges({ host, reg }: { host: OpsHost; reg: ThresholdRegistry | null }) {
  const diskFreeTh = th(reg, "host.disk_free_alert");
  return (
    <div className={styles.gauges}>
      <GaugeRow label="CPU" name="CPU 사용률" value={host.cpuPct} threshold={th(reg, "host.cpu_alert")} />
      <GaugeRow label="MEM" name="메모리 사용률" value={host.memPct} threshold={th(reg, "host.mem_alert")} />
      <GaugeRow
        label="DISK"
        name="디스크 사용률"
        value={host.diskUsedPct}
        threshold={diskFreeTh === null ? null : 100 - diskFreeTh}
        danger={diskFreeTh === null ? undefined : 100 - diskFreeTh / 2}
      />
    </div>
  );
}

function StateWord({ s }: { s: NodeState }) {
  return (
    <span className={styles.nodeState} data-tone={s.tone}>
      <Shape tone={s.tone}>{s.shape}</Shape>
      {s.word}
    </span>
  );
}

export function HostCard({ host, reg, now, alertRefs, board = true }: {
  host: OpsHost;
  reg: ThresholdRegistry | null;
  now: number;
  alertRefs: Map<string, OpsRef | null>;
  board?: boolean;
}) {
  const focus = useOpsFocus((s) => s.ref);
  const s = hostState(host, reg);
  const creditTh = th(reg, "host.cpu_credit_alert");
  const lowCredit = host.cpuCredits !== null && creditTh !== null && host.cpuCredits < creditTh;
  const lit = refMatches(focus, { kind: "HOST", id: host.id }, alertRefs);
  return (
    <article
      className={styles.host}
      data-tone={s.tone === "success" ? undefined : s.tone}
      data-focus={lit ? "" : undefined}
      aria-label={`${host.id} · ${HOST_KIND[host.role]} · ${s.word}`}
    >
      <div className={styles.hostTop}>
        <span className={styles.hostName}>{host.id}</span>
        <StateWord s={s} />
      </div>
      <span className={styles.hostKind}>{HOST_KIND[host.role]}</span>
      {host.partition && (
        <span className={styles.partition} title={`${host.partition.id} · ${host.partition.markets.join(" · ")}`}>
          <b>{host.partition.id}</b>
          {host.partition.markets.join(" · ")}
        </span>
      )}
      {host.role === "BOT_OBS" && <span className={styles.partition}>{BOT_SERVICES}</span>}
      <Gauges host={host} reg={reg} />
      <div className={styles.hostFoot}>
        <span title={`${host.instanceType} · ${host.az}`}>
          크레딧 <b className="num" data-tone={lowCredit ? "info" : undefined}>{host.cpuCredits ?? "◌"}</b>
          {lowCredit && <span> · 경보 {creditTh} 미만</span>}
        </span>
        <span className="num">{host.collectedAt === null ? "◌ 미수집" : `수집 ${ago(host.collectedAt, now)}`}</span>
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

const STREAM: Record<Replication["stream"], { word: string; tone: "success" | "warning" | "danger"; mark: string }> = {
  STREAMING: { word: "스트리밍", tone: "success", mark: "●" },
  CATCHUP: { word: "따라잡는 중", tone: "warning", mark: "▲" },
  STOPPED: { word: "끊김", tone: "danger", mark: "■" },
};

function DbSide({ host, reg, now }: { host: OpsHost; reg: ThresholdRegistry | null; now: number }) {
  const s = hostState(host, reg);
  return (
    <div className={styles.dbSide} data-tone={s.tone === "success" ? undefined : s.tone}>
      <div className={styles.hostTop}>
        <span className={styles.hostName}>{host.id}</span>
        <StateWord s={s} />
      </div>
      <span className={styles.hostKind}>{host.role === "DB_PRIMARY" ? "Primary · 쓰기" : "Standby · 비동기 복제"}</span>
      <Gauges host={host} reg={reg} />
      <span className={`${styles.dbCollected} num`}>{host.collectedAt === null ? "◌ 미수집" : `수집 ${ago(host.collectedAt, now)}`}</span>
    </div>
  );
}

/**
 * DB 두 대를 한 묶음에 나란히 두고 사이에 복제 방향·지연·상태를 적는다. 비동기 복제라 지연 동안 쓰인
 * 기록은 Primary를 잃으면 함께 잃을 수 있다(ADR 0024). 승격은 사람이 한다
 */
export function DbPair({ primary, standby, repl, reg, now, alertRefs }: {
  primary: OpsHost;
  standby: OpsHost;
  repl: OpsSection<Replication>;
  reg: ThresholdRegistry | null;
  now: number;
  alertRefs: Map<string, OpsRef | null>;
}) {
  const focus = useOpsFocus((s) => s.ref);
  const r = repl.state === "READY" ? repl.data : null;
  const lagTh = th(reg, "db.replication_lag_alert");
  const late = r !== null && r.lagSec !== null && lagTh !== null && r.lagSec >= lagTh;
  const st = r ? STREAM[r.stream] : null;
  const lit =
    refMatches(focus, { kind: "REPLICATION" }, alertRefs) ||
    refMatches(focus, { kind: "HOST", id: primary.id }, alertRefs) ||
    refMatches(focus, { kind: "HOST", id: standby.id }, alertRefs);
  const worst = [hostState(primary, reg).tone, hostState(standby, reg).tone];
  const tone = worst.includes("danger") || st?.tone === "danger" ? "danger" : worst.includes("warning") || late || st?.tone === "warning" ? "warning" : undefined;
  return (
    <article className={styles.dbPair} data-tone={tone} data-focus={lit ? "" : undefined} aria-label="데이터베이스 · PostgreSQL 17 Primary·Standby">
      <div className={styles.dbPairHead}>
        <span className={styles.hostKind}>PostgreSQL 17 · 비동기 복제 · 승격은 수동</span>
        {st && (
          <span className={styles.nodeState} data-tone={st.tone}>
            <Shape tone={st.tone}>{st.mark}</Shape>
            {st.word}
          </span>
        )}
      </div>
      <div className={styles.dbPairBody}>
        <DbSide host={primary} reg={reg} now={now} />
        <div className={styles.replLink} aria-label={r ? `복제 ${primary.id}에서 ${standby.id}로, 지연 ${r.lagSec ?? "미수집"}초` : "복제 원천 연결 전"}>
          <span className={styles.replArrow} aria-hidden="true" />
          {r ? (
            <>
              <b className="num" data-tone={late ? "warning" : undefined} aria-hidden="true">
                {late && "▲ "}
                {r.lagSec === null ? "◌" : `${r.lagSec}초`}
              </b>
              <span className="num" aria-hidden="true">
                {r.lagBytes === null ? "◌" : `${Math.round(r.lagBytes / 1024)}KB`}
              </span>
            </>
          ) : (
            <span aria-hidden="true">◌ 연결 전</span>
          )}
        </div>
        <DbSide host={standby} reg={reg} now={now} />
      </div>
      {r && (
        <div className={styles.lag}>
          <span>
            복제 지연 <b className="num">{r.lagSec === null ? "◌" : `${r.lagSec}초`}</b>
            {lagTh !== null && <> · 경보 {lagTh}초</>}
          </span>
          <span className={styles.lagRail}>
            <MeterRail value={r.lagSec ?? 0} max={lagTh === null ? 60 : lagTh * 2} threshold={lagTh ?? undefined} tone={late ? "warning" : undefined} />
          </span>
        </div>
      )}
    </article>
  );
}

const NODE_WORD: Record<ReadTier["nodes"][number]["state"], string> = {
  IN_SERVICE: "",
  PENDING: "기동 중",
  DRAINING: "드레이닝",
  OFF: "꺼짐",
};

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
        <span className={styles.hostKind}>Spring Boot · 조회 전용</span>
        <span className={`${styles.asgCount} num`}>
          {serving}/{tier.desired}대
          <span className={styles.asgBounds}>
            최소 {tier.min} · 최대 {tier.max}
          </span>
        </span>
      </div>
      <div className={styles.asgNodes}>
        {tier.nodes.map((n) => (
          <span key={n.id} className={styles.asgNode} data-state={n.state}>
            <Shape tone={n.state === "IN_SERVICE" ? "success" : n.state === "OFF" ? "off" : "warning"}>
              {n.state === "IN_SERVICE" ? "●" : n.state === "OFF" ? "○" : "▲"}
            </Shape>
            <span className={styles.asgNodeId}>{n.id}</span>
            <span className={`${styles.asgNodeVal} num`}>{n.state === "IN_SERVICE" ? `${n.cpuPct ?? "◌"}%` : NODE_WORD[n.state]}</span>
          </span>
        ))}
      </div>
      <div className={styles.asgRail}>
        <span className={styles.asgAvg}>
          평균 CPU{" "}
          <b className="num" data-tone={hot ? "warning" : undefined}>
            {hot && <span aria-hidden="true">▲ </span>}
            {avg === null ? "◌" : `${avg}%`}
          </b>
        </span>
        <span className={styles.asgMeter}>
          <MeterRail value={avg ?? 0} max={100} threshold={out ?? undefined} tone={hot ? "warning" : undefined}
            marks={inn === null ? undefined : [{ at: inn, label: `축소 ${inn}%` }]} />
          <span className={styles.asgTicks} aria-hidden="true">
            {inn !== null && <span className={styles.asgTick} style={{ left: `${inn}%` }}>축소 {inn}</span>}
            {out !== null && <span className={styles.asgTick} style={{ left: `${out}%` }}>증설 {out}</span>}
          </span>
        </span>
      </div>
    </section>
  );
}

function fmtMs(ms: number | null): string {
  if (ms === null) return "요청 없음";
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}초` : `${ms}ms`;
}

/**
 * 온프레미스 AI 추론 서버(ADR 0038). 구축 전이라 sim에서는 값이 시뮬레이션이고 적재 모델은 이름을
 * 단정하지 않는다. AI 판단이 일어나면 대기 요청과 GPU가 잠깐 오른다
 */
export function AiServerCard({ section, now, sim }: { section: OpsSection<AiServer>; now: number; sim: boolean }) {
  if (section.state === "NOT_CONNECTED") return <NotConnected what="AI 서버" source={section.source} compact />;
  const a = section.data;
  const s: NodeState = a.link === "DOWN"
    ? { tone: "warning", shape: "▲", word: "연결 끊김" }
    : a.busy
      ? { tone: "success", shape: "●", word: "판단 요청 처리 중" }
      : { tone: "success", shape: "●", word: "대기" };
  return (
    <article className={styles.host} data-tone={s.tone === "success" ? undefined : s.tone} aria-label={`${a.id} · ${a.hardware} · 로컬 LLM · ${s.word}`}>
      <div className={styles.hostTop}>
        <span className={styles.hostName}>{a.id}</span>
        <StateWord s={s} />
      </div>
      <span className={styles.hostKind}>
        {a.hardware} · 로컬 LLM({a.runtime})
      </span>
      <div className={styles.gauges}>
        <GaugeRow label="GPU" name="GPU 사용률" value={a.gpuPct} threshold={null} />
        <GaugeRow
          label="MEM"
          name="통합 메모리"
          value={a.memUsedGb}
          max={a.memTotalGb}
          unit="GB"
          threshold={null}
          suffix={`/${a.memTotalGb}`}
        />
      </div>
      <dl className={styles.aiSrv}>
        <dt>추론 지연</dt>
        <dd className="num">{a.p50Ms === null && a.p95Ms === null ? "요청 없음" : `p50 ${fmtMs(a.p50Ms)} · p95 ${fmtMs(a.p95Ms)}`}</dd>
        <dt>처리량</dt>
        <dd className="num">{a.tokensPerSec === null ? "◌" : `${a.tokensPerSec} 토큰/초`}</dd>
        <dt>대기 요청</dt>
        <dd className="num">{a.queue ?? "◌"}</dd>
        <dt>적재 모델</dt>
        <dd>{a.model}</dd>
        <dt>관제 연결</dt>
        <dd>
          <Shape tone={a.link === "OK" ? "success" : "warning"}>{a.link === "OK" ? "●" : "▲"}</Shape> {a.link === "OK" ? "연결됨" : "끊김"}
          {" · 경로 결정 전"}
        </dd>
      </dl>
      <div className={styles.hostFoot}>
        <span>{sim ? "구축 전 · 값은 시뮬레이션" : "구축 전"}</span>
        <span className="num">{a.collectedAt === null ? "◌ 미수집" : `수집 ${ago(a.collectedAt, now)}`}</span>
      </div>
    </article>
  );
}

function Group({ area, label, note, children }: { area: string; label: string; note?: string; children: ReactNode }) {
  return (
    <div className={styles.infraGroup} data-area={area}>
      <div className={styles.infraGroupHead}>
        <span className={styles.infraGroupLabel}>{label}</span>
        {note && <span className={styles.infraGroupNote}>{note}</span>}
      </div>
      {children}
    </div>
  );
}

/**
 * 역할 묶음 전체. 관제센터 인프라 칸과 서버·노드 화면이 같이 쓴다. 칸이 좁으면 위에서부터 앱, DB, 조회 ASG,
 * 관측과 온프레미스(한 줄) 순서이고, 넓은 화면(wide)에서는 두 줄로 편다. 문서 순서는 좁은 칸의 읽는 순서다
 */
export function InfraGroups({ center, now, board = false, wide = false }: { center: ControlCenter; now: number; board?: boolean; wide?: boolean }) {
  const reg = center.thresholds.state === "READY" ? center.thresholds.data : null;
  const alertRefs = alertRefMap(center);
  const sim = center.origin === "SIM";
  if (center.hosts.state === "NOT_CONNECTED") {
    return (
      <div className={styles.infraStack}>
        <NotConnected what="호스트 지표" source={center.hosts.source} />
        {center.readTier.state === "NOT_CONNECTED" && <NotConnected what="조회 노드 ASG" source={center.readTier.source} compact />}
        <AiServerCard section={center.aiServer} now={now} sim={sim} />
      </div>
    );
  }
  const hosts = center.hosts.data;
  const apps = hosts.filter((h) => h.role === "APP");
  const primary = hosts.find((h) => h.role === "DB_PRIMARY");
  const standby = hosts.find((h) => h.role === "DB_STANDBY");
  const bots = hosts.filter((h) => h.role === "BOT_OBS");
  return (
    <div className={styles.infraGroups} data-wide={wide ? "" : undefined}>
      <Group area="app" label="앱" note="마켓별 매칭 리더">
        <div className={styles.hostGrid}>
          {apps.map((h) => (
            <HostCard key={h.id} host={h} reg={reg} now={now} alertRefs={alertRefs} board={board} />
          ))}
        </div>
      </Group>
      {primary && standby && (
        <Group area="db" label="데이터베이스">
          <DbPair primary={primary} standby={standby} repl={center.replication} reg={reg} now={now} alertRefs={alertRefs} />
        </Group>
      )}
      <Group area="asg" label="조회 ASG" note="AWS 지표 정책이 대수를 정함">
        {center.readTier.state === "READY" ? (
          <AsgBand tier={center.readTier.data} reg={reg} alertRefs={alertRefs} />
        ) : (
          <NotConnected what="조회 노드 ASG" source={center.readTier.source} compact />
        )}
      </Group>
      <Group area="bot" label="관측">
        {bots.map((h) => (
          <HostCard key={h.id} host={h} reg={reg} now={now} alertRefs={alertRefs} board={board} />
        ))}
      </Group>
      <Group area="ai" label="온프레미스 · 연구소">
        <AiServerCard section={center.aiServer} now={now} sim={sim} />
      </Group>
    </div>
  );
}

function Counts({ c }: { c: { ok: number; warn: number; down: number } }) {
  return (
    <span className={styles.counts}>
      <span>
        <Shape tone="success">●</Shape> 정상 <b className="num">{c.ok}</b>
      </span>
      <span data-tone={c.warn > 0 ? "warning" : undefined}>
        <Shape tone={c.warn > 0 ? "warning" : "off"}>▲</Shape> 주의 <b className="num">{c.warn}</b>
      </span>
      <span data-tone={c.down > 0 ? "danger" : undefined}>
        <Shape tone={c.down > 0 ? "danger" : "off"}>■</Shape> 장애 <b className="num">{c.down}</b>
      </span>
    </span>
  );
}

/** 관제센터 왼쪽 기둥 */
export default function InfraColumn({ center, now }: { center: ControlCenter | null; now: number }) {
  const counts = center ? infraCounts(center) : null;
  return (
    <section className={styles.column} aria-labelledby="col-infra">
      <PanelHead
        id="col-infra"
        title="인프라"
        meta={counts ? <Counts c={counts} /> : undefined}
        actions={
          <>
            <Link href="/admin/hosts" className={styles.stripLink}>
              서버·노드
            </Link>
            <GrafanaLink board="msp-infra" label="추이" />
          </>
        }
      />
      <div className={styles.scrollArea}>{center !== null && <InfraGroups center={center} now={now} />}</div>
    </section>
  );
}
