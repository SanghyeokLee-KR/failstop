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
import { hostState, infraCounts, thresholdOf as th, type NodeState } from "@/lib/admin/opsHealth";
import { joinDots } from "@/lib/admin/text";
import GrafanaLink from "../GrafanaLink";
import { MeterRail } from "../instruments/MeterRail";
import { SimMark } from "../ui";
import { Ago, GaugeRow, lagWord, NotConnected, PanelHead, secWord, Shape } from "./parts";
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

/** 경보가 가리키는 자리. 근거 칩이 경보를 가리켜도 그 경보의 카드가 함께 켜진다 */
export function alertRefMap(center: ControlCenter | null): Map<string, OpsRef | null> {
  const m = new Map<string, OpsRef | null>();
  if (center?.alerts.state === "READY") for (const a of center.alerts.data) m.set(a.id, a.ref);
  return m;
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

export function HostCard({ host, reg, alertRefs, board = true }: {
  host: OpsHost;
  reg: ThresholdRegistry | null;
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
        <span className="num">{host.collectedAt === null ? "◌ 미수집" : <Ago at={host.collectedAt} prefix="수집 " />}</span>
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

function DbSide({ host, reg }: { host: OpsHost; reg: ThresholdRegistry | null }) {
  const s = hostState(host, reg);
  return (
    <div className={styles.dbSide} data-tone={s.tone === "success" ? undefined : s.tone}>
      <div className={styles.hostTop}>
        <span className={styles.hostName}>{host.id}</span>
        <StateWord s={s} />
      </div>
      <span className={styles.hostKind}>{host.role === "DB_PRIMARY" ? "Primary · 쓰기" : "Standby · 비동기 복제"}</span>
      <Gauges host={host} reg={reg} />
      <span className={`${styles.dbCollected} num`}>{host.collectedAt === null ? "◌ 미수집" : <Ago at={host.collectedAt} prefix="수집 " />}</span>
    </div>
  );
}

/**
 * DB 두 대를 한 묶음에 나란히 두고 사이에 복제 방향·지연·상태를 적는다. 비동기 복제라 지연 동안 쓰인
 * 기록은 Primary를 잃으면 함께 잃을 수 있다(ADR 0024). 승격은 사람이 한다
 */
export function DbPair({ primary, standby, repl, reg, alertRefs }: {
  primary: OpsHost;
  standby: OpsHost;
  repl: OpsSection<Replication>;
  reg: ThresholdRegistry | null;
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
        <span className={styles.hostKind}>{joinDots("PostgreSQL 17 · 비동기 복제 · 승격은 수동")}</span>
        {st && (
          <span className={styles.nodeState} data-tone={st.tone}>
            <Shape tone={st.tone}>{st.mark}</Shape>
            {st.word}
          </span>
        )}
      </div>
      <div className={styles.dbPairBody}>
        <DbSide host={primary} reg={reg} />
        <div className={styles.replLink} aria-label={r ? `복제 ${primary.id}에서 ${standby.id}로, 지연 ${r.lagSec === null ? "미수집" : lagWord(r.lagSec)}` : "복제 원천 연결 전"}>
          <span className={styles.replArrow} aria-hidden="true" />
          {r ? (
            <>
              <b className="num" data-tone={late ? "warning" : undefined} aria-hidden="true">
                {late && "▲ "}
                {r.lagSec === null ? "◌" : lagWord(r.lagSec)}
              </b>
              <span className="num" aria-hidden="true">
                {r.lagBytes === null ? "◌" : `${Math.round(r.lagBytes / 1024)}KB`}
              </span>
            </>
          ) : (
            <span aria-hidden="true">◌ 연결 전</span>
          )}
        </div>
        <DbSide host={standby} reg={reg} />
      </div>
      {r && (
        <div className={styles.lag}>
          <span>
            복제 지연 <b className="num">{r.lagSec === null ? "◌" : lagWord(r.lagSec)}</b>
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
  OFF: "중지",
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
  return ms === null ? "요청 없음" : secWord(ms);
}

/**
 * 온프레미스 AI 추론 서버(ADR 0038). 구축 전이고 연결 경로도 정하지 않았다. sim은 붙어서 판단 요청에 답한다고
 * 가정하므로 카드에 '시뮬레이션 가정'을 달고, '연결됨'과 '경로 결정 전'을 한 줄에 함께 적지 않는다(관리자 설계 4-4).
 * 적재 모델은 이름을 단정하지 않는다. AI 판단이 일어나면 대기 요청과 GPU가 잠깐 오른다
 */
export function AiServerCard({ section }: { section: OpsSection<AiServer> }) {
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
      {/* 가정 표식은 머리 줄 밖 제 줄에 둔다. 머리 줄에 끼우면 좁은 칸에서 상태 낱말이 잘린다 */}
      {a.assumed && (
        <span className={styles.hostMark}>
          <SimMark kind="assumed" />
        </span>
      )}
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
          {a.assumed ? (
            "가정 · 연결 경로 결정 전"
          ) : (
            <>
              <Shape tone={a.link === "OK" ? "success" : "warning"}>{a.link === "OK" ? "●" : "▲"}</Shape> {a.link === "OK" ? "연결됨" : "끊김"}
            </>
          )}
        </dd>
      </dl>
      <div className={styles.hostFoot}>
        <span>구축 전 · ADR 0038</span>
        <span className="num">{a.collectedAt === null ? "◌ 미수집" : <Ago at={a.collectedAt} prefix="수집 " />}</span>
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
export function InfraGroups({ center, board = false, wide = false }: { center: ControlCenter; board?: boolean; wide?: boolean }) {
  const reg = center.thresholds.state === "READY" ? center.thresholds.data : null;
  const alertRefs = alertRefMap(center);
  if (center.hosts.state === "NOT_CONNECTED") {
    return (
      <div className={styles.infraStack}>
        <NotConnected what="호스트 지표" source={center.hosts.source} />
        {center.readTier.state === "NOT_CONNECTED" && <NotConnected what="조회 노드 ASG" source={center.readTier.source} compact />}
        <AiServerCard section={center.aiServer} />
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
            <HostCard key={h.id} host={h} reg={reg} alertRefs={alertRefs} board={board} />
          ))}
        </div>
      </Group>
      {primary && standby && (
        <Group area="db" label="데이터베이스">
          <DbPair primary={primary} standby={standby} repl={center.replication} reg={reg} alertRefs={alertRefs} />
        </Group>
      )}
      <Group area="asg" label="조회 ASG" note="AWS 지표 정책이 대수를 정합니다">
        {center.readTier.state === "READY" ? (
          <AsgBand tier={center.readTier.data} reg={reg} alertRefs={alertRefs} />
        ) : (
          <NotConnected what="조회 노드 ASG" source={center.readTier.source} compact />
        )}
      </Group>
      <Group area="bot" label="관측">
        {bots.map((h) => (
          <HostCard key={h.id} host={h} reg={reg} alertRefs={alertRefs} board={board} />
        ))}
      </Group>
      <Group area="ai" label="온프레미스 · 연구소" note="구축 전">
        <AiServerCard section={center.aiServer} />
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
export default function InfraColumn({ center }: { center: ControlCenter | null }) {
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
      <div className={styles.scrollArea}>{center !== null && <InfraGroups center={center} />}</div>
    </section>
  );
}
