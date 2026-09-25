"use client";

/**
 * 인프라 판독(관리자 설계 4-4). 역할 묶음으로 나누고 카드마다 정체를 적는다. 앱 계층(늘 켜진 매칭 리더 app-1·app-2와
 * 부하가 오르면 켜지는 조회 전용 app-3, 2~3대), 데이터베이스(Primary·Standby와 그 사이의 복제), 관측(bot-1),
 * 온프레미스(ai-1)다(ADR 0030, 사용자 정정 2026-09-25). 게이지의 눈금은 임계값 표의 현재값이라 표를 바꾸면 눈금이 함께
 * 움직인다(§12-9). 추이는 Grafana로 보낸다.
 */

import Link from "next/link";
import type { CSSProperties, ReactNode } from "react";
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
import { useOpsDetail } from "@/lib/admin/detailStore";
import { appTierHot, hostState, infraCounts, thresholdOf as th, type NodeState } from "@/lib/admin/opsHealth";
import { joinDots } from "@/lib/admin/text";
import GrafanaLink from "../GrafanaLink";
import { useChangeFlags } from "../useChangeFlags";
import { MeterRail } from "../instruments/MeterRail";
import { SimMark } from "../ui";
import { Ago, FlashNum, GaugeRow, lagWord, PanelHead, SectionGap, secWord, Shape } from "./parts";
import styles from "./control.module.css";
import StatusShape from "../StatusShape";

/** 카드 둘째 줄의 정체. 서비스와 역할을 적는다 */
export const HOST_KIND: Record<OpsHostRole, string> = {
  APP: "Spring Boot · 매칭 리더 · 조회",
  DB_PRIMARY: "PostgreSQL 17 · Primary",
  DB_STANDBY: "PostgreSQL 17 · Standby",
  BOT_OBS: "시장 봇 · 관측",
};
/** bot-1이 시장 봇과 함께 싣는 관측 서비스 */
const BOT_SERVICES = "Prometheus · Grafana · Alertmanager · 보고";

/** 링 펄스를 켜 두는 시간. --dur-pulse 900ms 두 번이다(디자인 시스템 §12-4) */
const PULSE_MS = 1800;

/** 카드 머리의 노드 이름. 누르면 그 노드의 상세 층이 열린다(디자인 시스템 §12-13의 1) */
export function NodeName({ id, className }: { id: string; className?: string }) {
  return (
    <button type="button" className={`${styles.hostName} ${styles.nodeLink}${className ? ` ${className}` : ""}`} aria-haspopup="dialog" title={`${id} 상세`} onClick={() => useOpsDetail.getState().openNode(id)}>
      {id}
    </button>
  );
}

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

export function HostCard({ host, reg, alertRefs, board = true, tierHot = false }: {
  host: OpsHost;
  reg: ThresholdRegistry | null;
  alertRefs: Map<string, OpsRef | null>;
  board?: boolean;
  /** 앱 계층 과부하. 앱 노드는 제 게이지가 임계 안이어도 '조회 부하 높음' 주의다 */
  tierHot?: boolean;
}) {
  const focus = useOpsFocus((s) => s.ref);
  const s = hostState(host, reg, tierHot);
  const creditTh = th(reg, "host.cpu_credit_alert");
  const lowCredit = host.cpuCredits !== null && creditTh !== null && host.cpuCredits < creditTh;
  const lit = refMatches(focus, { kind: "HOST", id: host.id }, alertRefs);
  const pulse = useChangeFlags({ tone: s.tone }, PULSE_MS).tone;
  return (
    <article
      className={styles.host}
      data-tone={s.tone === "success" ? undefined : s.tone}
      data-focus={lit ? "" : undefined}
      data-pulse={pulse ? "" : undefined}
      aria-label={`${host.id} · ${HOST_KIND[host.role]} · ${s.word}`}
    >
      <div className={styles.hostTop}>
        <NodeName id={host.id} />
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

/** DB 묶음의 한쪽. 호스트를 가리키는 근거는 묶음 전체가 아니라 이 칸만 켠다(§12-9 근거 칩) */
function DbSide({ host, reg, lit }: { host: OpsHost; reg: ThresholdRegistry | null; lit: boolean }) {
  const s = hostState(host, reg);
  return (
    <div className={styles.dbSide} data-tone={s.tone === "success" ? undefined : s.tone} data-focus={lit ? "" : undefined}>
      <div className={styles.hostTop}>
        <NodeName id={host.id} />
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
  const lit = refMatches(focus, { kind: "REPLICATION" }, alertRefs);
  const litPrimary = refMatches(focus, { kind: "HOST", id: primary.id }, alertRefs);
  const litStandby = refMatches(focus, { kind: "HOST", id: standby.id }, alertRefs);
  const worst = [hostState(primary, reg).tone, hostState(standby, reg).tone];
  const tone = worst.includes("danger") || st?.tone === "danger" ? "danger" : worst.includes("warning") || late || st?.tone === "warning" ? "warning" : undefined;
  const pulse = useChangeFlags({ tone: tone ?? "ok" }, PULSE_MS).tone;
  return (
    <article className={styles.dbPair} data-tone={tone} data-focus={lit ? "" : undefined} data-pulse={pulse ? "" : undefined} aria-label="데이터베이스 · PostgreSQL 17 Primary·Standby">
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
        <DbSide host={primary} reg={reg} lit={litPrimary} />
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
            <span aria-hidden="true"><StatusShape kind="dashed" /> 연결 전</span>
          )}
        </div>
        <DbSide host={standby} reg={reg} lit={litStandby} />
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
  OFF: "꺼짐",
};

/**
 * 앱 계층의 자동 증설 칸(ADR 0030, 사용자 정정 2026-09-25). 'Auto Scaling' 표기는 조회 전용 app-3 자리에만 붙인다.
 * 머리는 앱 계층 전체 대수(조회를 받는 노드/희망, 최소 2 · 최대 3)이고, 막대는 조회를 받는 앱 노드의 평균 CPU와
 * 축소·증설 임계다. 매칭 리더 app-1·app-2는 AWS가 임의로 종료·교체하지 않게 ASG 밖에 둔다
 */
export function AsgBand({ tier, reg, alertRefs }: { tier: ReadTier; reg: ThresholdRegistry | null; alertRefs: Map<string, OpsRef | null> }) {
  const focus = useOpsFocus((s) => s.ref);
  const out = th(reg, "read.scale_out_cpu");
  const inn = th(reg, "read.scale_in_cpu");
  const avg = tier.avgCpuPct;
  const hot = avg !== null && out !== null && avg >= out;
  const lit = refMatches(focus, { kind: "READ_TIER" }, alertRefs);
  const serving = tier.serving;
  const moving = tier.nodes.some((n) => n.state === "PENDING" || n.state === "DRAINING");
  // 조정 중(기동·드레이닝)도 틀의 상태 전이다. 링 펄스가 대수가 움직이기 시작한 순간과 끝난 순간을 알린다
  const pulse = useChangeFlags({ tone: hot ? "hot" : moving ? "moving" : "ok" }, PULSE_MS).tone;
  // 대수 플래시의 색은 바뀐 뒤의 틀 상태다. 조정 중이면 주의, 다 섰으면 정상이다
  const countTone = moving ? "warning" : "success";
  return (
    <section className={styles.asg} data-tone={hot || moving ? "warning" : undefined} data-focus={lit ? "" : undefined} data-pulse={pulse ? "" : undefined} aria-label="앱 계층 자동 증설 · Auto Scaling app-3">
      <div className={styles.asgHead}>
        <span className={styles.asgKind}>
          <b>Auto Scaling</b> · 조회 전용 · 0~1대
        </span>
        <span className={`${styles.asgCount} num`}>
          {/* 조회를 받는 앱 노드/희망 대수. 숫자 조각 사이에 틈을 두지 않는다 */}
          <span className={styles.asgCountNum}>
            <FlashNum value={serving} tone={countTone} />/<FlashNum value={tier.desired} tone={countTone} />대
          </span>
          <span className={styles.asgBounds}>
            앱 계층 · 최소 {tier.min} · 최대 {tier.max}
          </span>
        </span>
      </div>
      <div className={styles.asgNodes}>
        {tier.nodes.map((n) => (
          <button
            key={n.id}
            type="button"
            className={styles.asgNode}
            data-state={n.state}
            aria-haspopup="dialog"
            title={`${n.id} 상세`}
            onClick={() => useOpsDetail.getState().openNode(n.id)}
          >
            <Shape tone={n.state === "IN_SERVICE" ? "success" : n.state === "OFF" ? "off" : "warning"}>
              {n.state === "IN_SERVICE" ? "●" : n.state === "OFF" ? "○" : "▲"}
            </Shape>
            <span className={styles.asgNodeId}>{n.id}</span>
            <span className={`${styles.asgNodeVal} num`}>{n.state === "IN_SERVICE" ? `${n.cpuPct ?? "◌"}%` : NODE_WORD[n.state]}</span>
          </button>
        ))}
      </div>
      <div className={styles.asgRail}>
        <span className={styles.asgAvg}>
          앱 평균 CPU{" "}
          <b className="num" data-tone={hot ? "warning" : undefined}>
            {hot && <span aria-hidden="true"><StatusShape kind="triangle" /> </span>}
            {avg === null ? "◌" : `${avg}%`}
          </b>
        </span>
        <span className={styles.asgMeter}>
          <MeterRail value={avg ?? 0} max={100} threshold={out ?? undefined} tone={hot ? "warning" : undefined}
            marks={inn === null ? undefined : [{ at: inn, label: `축소 ${inn}%` }]} />
          <span className={styles.asgTicks} aria-hidden="true">
            {inn !== null && (
              <span className={styles.asgTick} data-side="in" style={{ "--tick-at": inn } as CSSProperties}>
                축소 {inn}
              </span>
            )}
            {out !== null && (
              <span className={styles.asgTick} data-side="out" style={{ "--tick-at": out } as CSSProperties}>
                증설 {out}
              </span>
            )}
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
  if (section.state !== "READY") return <SectionGap section={section} what="AI 서버" compact />;
  const a = section.data;
  const s: NodeState = a.link === "DOWN"
    ? { tone: "warning", shape: "▲", word: "연결 끊김" }
    : a.busy
      ? { tone: "success", shape: "●", word: "판단 요청 처리 중" }
      : { tone: "success", shape: "●", word: "대기" };
  return (
    <article className={styles.host} data-tone={s.tone === "success" ? undefined : s.tone} aria-label={`${a.id} · ${a.hardware} · 로컬 LLM · ${s.word}`}>
      <div className={styles.hostTop}>
        <NodeName id={a.id} />
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
        {/* 장비 메모리를 모르면 막대의 분모가 없어 막대를 비우고, 아는 값(적재 모델 메모리)은 아래 줄에 적는다 */}
        <GaugeRow
          label="MEM"
          name="통합 메모리"
          value={a.memTotalGb === null ? null : a.memUsedGb}
          max={a.memTotalGb ?? undefined}
          unit="GB"
          threshold={null}
          suffix={a.memTotalGb === null ? "" : `/${a.memTotalGb}`}
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
        {a.memTotalGb === null && a.memUsedGb !== null && (
          <>
            <dt>모델 메모리</dt>
            <dd className="num">{a.memUsedGb}GB</dd>
          </>
        )}
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
        <span>{a.assumed ? "구축 전 · ADR 0038" : "ADR 0038"}</span>
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
 * 역할 묶음 전체. 관제센터 인프라 칸과 서버·노드 화면이 같이 쓴다. 칸이 좁으면 위에서부터 앱 계층, DB, 관측과
 * 온프레미스(한 줄) 순서이고, 넓은 화면(wide)에서는 두 줄로 편다. 문서 순서는 좁은 칸의 읽는 순서다
 */
export function InfraGroups({ center, board = false, wide = false }: { center: ControlCenter; board?: boolean; wide?: boolean }) {
  const reg = center.thresholds.state === "READY" ? center.thresholds.data : null;
  const alertRefs = alertRefMap(center);
  if (center.hosts.state !== "READY") {
    return (
      <div className={styles.infraStack}>
        <SectionGap section={center.hosts} what="호스트 지표" />
        {center.readTier.state !== "READY" && <SectionGap section={center.readTier} what="앱 계층 자동 증설" compact />}
        <AiServerCard section={center.aiServer} />
      </div>
    );
  }
  const hosts = center.hosts.data;
  const apps = hosts.filter((h) => h.role === "APP");
  const primary = hosts.find((h) => h.role === "DB_PRIMARY");
  const standby = hosts.find((h) => h.role === "DB_STANDBY");
  const bots = hosts.filter((h) => h.role === "BOT_OBS");
  const tierHot = appTierHot(center);
  return (
    <div className={styles.infraGroups} data-wide={wide ? "" : undefined}>
      {/* 앱 계층 2~3대 한 묶음이다. 고정 두 대(매칭 리더이면서 조회)와 자동 증설 칸(app-3)을 함께 둔다 */}
      <Group area="app" label="앱 계층" note="2~3대 · 매칭 리더 두 대와 조회 전용 app-3">
        <div className={styles.appTier}>
          {apps.map((h) => (
            <HostCard key={h.id} host={h} reg={reg} alertRefs={alertRefs} board={board} tierHot={tierHot} />
          ))}
          {center.readTier.state === "READY" ? (
            <AsgBand tier={center.readTier.data} reg={reg} alertRefs={alertRefs} />
          ) : (
            <SectionGap section={center.readTier} what="앱 계층 자동 증설" compact />
          )}
        </div>
      </Group>
      {primary && standby && (
        <Group area="db" label="데이터베이스">
          <DbPair primary={primary} standby={standby} repl={center.replication} reg={reg} alertRefs={alertRefs} />
        </Group>
      )}
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
        <Shape tone="success">●</Shape> 정상{" "}
        <b className="num">
          <FlashNum value={c.ok} tone="success" />
        </b>
      </span>
      <span data-tone={c.warn > 0 ? "warning" : undefined}>
        <Shape tone={c.warn > 0 ? "warning" : "off"}>▲</Shape> 주의{" "}
        <b className="num">
          <FlashNum value={c.warn} tone={(prev, next) => (next > prev ? "warning" : next === 0 ? "success" : null)} />
        </b>
      </span>
      <span data-tone={c.down > 0 ? "danger" : undefined}>
        <Shape tone={c.down > 0 ? "danger" : "off"}>■</Shape> 장애{" "}
        <b className="num">
          <FlashNum value={c.down} tone={(prev, next) => (next > prev ? "danger" : next === 0 ? "success" : null)} />
        </b>
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
