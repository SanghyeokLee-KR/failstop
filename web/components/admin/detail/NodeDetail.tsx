"use client";

/**
 * 노드 상세(모달 880x640, 디자인 시스템 §12-13의 1, 관리자 설계 4-9). 한 노드의 정체, 자원, 서비스, 로그 요약,
 * 조치 기록을 탭으로 보고 ←·→로 이웃 노드로 옮긴다. 읽는 자리라 실행 단추가 없다. 조치는 조치 카드와 승인
 * 절차에서만 하고, 추이는 Grafana로 보낸다.
 */

import { useEffect, useState } from "react";
import Link from "next/link";
import { ASSET_SOURCE } from "@/lib/admin/assets";
import { bytesWord, walGap } from "@/lib/admin/dbFacts";
import { appTierHot, hostState, thresholdOf as th, type NodeState } from "@/lib/admin/opsHealth";
import { nodeAlerts, nodeEvents, nodeList, nodeServices, type NodeRef } from "@/lib/admin/opsDetail";
import { joinDots } from "@/lib/admin/text";
import { mdhm, spanWord } from "@/lib/admin/timeText";
import type { ControlCenter, HostAsset, ReadNode, ThresholdRegistry } from "@/lib/admin/types";
import GrafanaLink, { grafanaConfigured } from "../GrafanaLink";
import { AdminIcon } from "../icons";
import { MeterRail } from "../instruments/MeterRail";
import { Modal } from "../layer/Layer";
import StatusShape, { GLYPH_SHAPE } from "../StatusShape";
import { Segmented, SimMark, StateChip } from "../ui";
import { HOST_KIND } from "../control/Infra";
import { Ago, GaugeRow, SEVERITY_SHAPE, useClock } from "../control/parts";
import { EventRow } from "../control/Timeline";
import controlStyles from "../control/control.module.css";
import styles from "./detail.module.css";

type Tab = "overview" | "resources" | "services" | "logs" | "actions";
const TABS: { value: Tab; label: string }[] = [
  { value: "overview", label: "개요" },
  { value: "resources", label: "자원" },
  { value: "services", label: "서비스" },
  { value: "logs", label: "로그 요약" },
  { value: "actions", label: "조치 기록" },
];

const READ_STATE: Record<ReadNode["state"], NodeState> = {
  IN_SERVICE: { tone: "success", shape: "●", word: "운영 중" },
  PENDING: { tone: "warning", shape: "▲", word: "기동 중" },
  DRAINING: { tone: "warning", shape: "▲", word: "드레이닝" },
  OFF: { tone: "off", shape: "○", word: "꺼짐 · 자동 증설 대기" },
};

function stateOf(ref: NodeRef, reg: ThresholdRegistry | null, tierHot: boolean): NodeState {
  if (ref.kind === "host") return hostState(ref.host, reg, tierHot);
  if (ref.kind === "read") return READ_STATE[ref.node.state];
  if (ref.server.link === "DOWN") return { tone: "warning", shape: "▲", word: "연결 끊김" };
  return { tone: "success", shape: "●", word: ref.server.busy ? "판단 요청 처리 중" : "대기" };
}

function roleLine(ref: NodeRef): string {
  if (ref.kind === "host") {
    const part = ref.host.partition ? ` · ${ref.host.partition.id}` : "";
    return `${HOST_KIND[ref.host.role]}${part}`;
  }
  if (ref.kind === "read") return "Spring Boot · 조회 전용 · 앱 계층 Auto Scaling(0~1대)";
  return `${ref.server.hardware} · 로컬 LLM(${ref.server.runtime}) · 온프레미스`;
}

function chipTone(s: NodeState) {
  return s.tone === "off" ? "off" : s.tone;
}

/** 노드의 자산 칸. 호스트와 켜진 app-3만 있고, ai-1(구축 전)과 꺼진 app-3는 없다 */
function assetOf(ref: NodeRef): HostAsset | null {
  if (ref.kind === "host") return ref.host.asset ?? null;
  if (ref.kind === "read") return ref.node.asset ?? null;
  return null;
}

/** 자산 칸 하나. 원천이 없으면(http) '◌'과 원천 이름이다 */
/** 가동 시간. 자산 표와 같은 분 박자를 읽어 두 자리가 같은 값을 적는다 */
function Uptime({ at }: { at: string }) {
  const now = useClock(60_000);
  return <>{spanWord(now - Date.parse(at))}</>;
}

function AssetValue({ asset, k, children }: { asset: HostAsset; k: keyof HostAsset; children: React.ReactNode }) {
  if (asset[k] === null) {
    return (
      <span className={styles.missingValue} title={`원천 연결 전 · ${ASSET_SOURCE[k]}`}>
        ◌ 원천 연결 전
      </span>
    );
  }
  return <>{children}</>;
}

/** 큰 판독값과 임계 눈금 막대 하나. 값이 임계를 넘으면 테두리·값·채움이 상태색이다(§12-14 미터 채움 규칙) */
function Meter({ name, value, unit, threshold, danger, max = 100, note }: {
  name: string;
  value: number | null;
  unit: string;
  threshold: number | null;
  danger?: number;
  max?: number;
  note: string;
}) {
  const tone = value === null || threshold === null ? undefined : danger !== undefined && value >= danger ? "danger" : value >= threshold ? "warning" : undefined;
  return (
    <div className={styles.meter} data-tone={tone}>
      <div className={styles.meterTop}>
        <span className={styles.meterName}>{name}</span>
        <span className={`${styles.meterValue} num`} data-tone={tone}>
          {tone && <StatusShape kind={tone === "danger" ? "square" : "triangle"} />}
          {value === null ? "◌" : value}
          {value !== null && <span className={styles.meterUnit}>{unit}</span>}
        </span>
      </div>
      <MeterRail value={value ?? 0} max={max} threshold={threshold ?? undefined} tone={tone} />
      <div className={styles.meterFoot}>
        <span>{note}</span>
        {threshold !== null && (
          <span className="num">
            경보 {threshold}
            {unit}
          </span>
        )}
      </div>
    </div>
  );
}

function Resources({ node, reg }: { node: NodeRef; reg: ThresholdRegistry | null }) {
  if (node.kind === "ai") {
    const a = node.server;
    return (
      <div className={styles.meters}>
        <Meter name="GPU 사용률" value={a.gpuPct} unit="%" threshold={null} note="판단 요청이 걸린 동안 오릅니다" />
        {/* 장비 메모리를 모르면(http 원천) 막대 대신 적재 모델 메모리만 적는다 */}
        <Meter name="통합 메모리" value={a.memTotalGb === null ? null : a.memUsedGb} unit={a.memTotalGb === null ? "GB" : `/${a.memTotalGb}GB`} max={a.memTotalGb ?? undefined} threshold={null}
          note={a.memTotalGb === null ? `장비 메모리 원천 연결 전 · 적재 모델 ${a.memUsedGb === null ? "미확인" : `${a.memUsedGb}GB`}` : "적재 모델과 캐시"} />
        <Meter name="추론 지연 p95" value={a.p95Ms === null ? null : Math.round(a.p95Ms / 100) / 10} unit="초" max={5} threshold={null} note={a.p50Ms === null ? "요청 없음" : `p50 ${Math.round(a.p50Ms / 100) / 10}초`} />
        <Meter name="대기 요청" value={a.queue} unit="건" max={4} threshold={null} note={`처리량 ${a.tokensPerSec ?? 0} 토큰/초`} />
      </div>
    );
  }
  if (node.kind === "read") {
    return (
      <>
        <div className={styles.meters}>
          <Meter name="CPU 사용률" value={node.node.cpuPct} unit="%" threshold={th(reg, "read.scale_out_cpu")} note="증설 임계가 눈금입니다" />
        </div>
        <p className={styles.note}>app-3는 Auto Scaling이 켜고 끄는 조회 전용 노드라 관제 판이 CPU만 모읍니다. 메모리·디스크는 Grafana 03 AWS·서버에서 봅니다.</p>
      </>
    );
  }
  const h = node.host;
  const diskFree = th(reg, "host.disk_free_alert");
  const creditTh = th(reg, "host.cpu_credit_alert");
  return (
    <div className={styles.meters}>
      <Meter name="CPU 사용률" value={h.cpuPct} unit="%" threshold={th(reg, "host.cpu_alert")} note="호스트 CPU" />
      <Meter name="메모리 사용률" value={h.memPct} unit="%" threshold={th(reg, "host.mem_alert")} note="호스트 메모리" />
      <Meter
        name="디스크 사용률"
        value={h.diskUsedPct}
        unit="%"
        threshold={diskFree === null ? null : 100 - diskFree}
        danger={diskFree === null ? undefined : 100 - diskFree / 2}
        note={diskFree === null ? "루트 디스크" : `여유 ${diskFree}% 미만이면 경보`}
      />
      <div className={styles.meter} data-tone={h.cpuCredits !== null && creditTh !== null && h.cpuCredits < creditTh ? "warning" : undefined}>
        <div className={styles.meterTop}>
          <span className={styles.meterName}>CPU 크레딧</span>
          <span className={`${styles.meterValue} num`}>{h.cpuCredits ?? "◌"}</span>
        </div>
        <div className={styles.meterFoot}>
          <span>t3 버스트 크레딧 잔량</span>
          {creditTh !== null && <span className="num">경보 {creditTh} 미만</span>}
        </div>
      </div>
    </div>
  );
}

export default function NodeDetail({ center, id, sim, onMove, onClose, onOpenIncident }: {
  center: ControlCenter;
  id: string;
  sim: boolean;
  onMove: (id: string) => void;
  onClose: () => void;
  onOpenIncident: (id: string) => void;
}) {
  const [tab, setTab] = useState<Tab>("overview");
  const list = nodeList(center);
  const at = list.findIndex((n) => n.id === id);
  const node = at >= 0 ? list[at] : null;
  const reg = center.thresholds.state === "READY" ? center.thresholds.data : null;
  const prev = at > 0 ? list[at - 1] : null;
  const next = at >= 0 && at < list.length - 1 ? list[at + 1] : null;

  // 탭 목록과 입력 밖에서 ←·→가 이웃 노드로 옮긴다. 탭 목록 안의 화살표는 탭이 먼저 받는다(Segmented)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
      const t = e.target as HTMLElement | null;
      if (t?.closest("[role=tablist], input, textarea, select, pre")) return;
      const to = e.key === "ArrowLeft" ? prev : next;
      if (!to) return;
      e.preventDefault();
      onMove(to.id);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [prev, next, onMove]);

  if (!node) {
    return (
      <Modal name="node" width={880} height={320} title="노드를 찾지 못했습니다" onClose={onClose}>
        <p className={styles.note}>이 판에 {id} 노드가 없습니다. 주소가 오래됐거나 원천이 연결 전입니다.</p>
      </Modal>
    );
  }
  const s = stateOf(node, reg, appTierHot(center));
  const alerts = center.alerts.state === "READY" ? nodeAlerts(center.alerts.data, node) : [];
  const log = center.incidents.state === "READY" ? center.incidents.data : null;
  const ev = log ? nodeEvents(log, node) : { system: [], actions: [] };
  const openInc = log?.incidents.find((i) => i.resolvedAt === null && ev.system.some((e) => e.incidentId === i.id)) ?? null;
  const services = nodeServices(node);
  const asset = assetOf(node);
  // 실패한 서비스 원천(node_systemd_unit_state)이 없으면 서비스 상태를 적지 않는다. sim은 노드 상태에서 가른 가정이다
  const unitsKnown = node.kind === "ai" || (asset !== null && asset.failedUnits !== null) || (node.kind === "read" && node.node.state === "OFF");
  const dbStats = center.database.state === "READY" ? center.database.data : null;
  const serviceState = (unit: string): NodeState => {
    if (asset?.failedUnits?.includes(unit)) return { tone: "danger", shape: "■", word: "실패" };
    if (node.kind === "read") {
      if (node.node.state === "OFF") return { tone: "off", shape: "○", word: "중지" };
      if (node.node.state === "PENDING") return { tone: "warning", shape: "▲", word: "기동 중" };
      if (node.node.state === "DRAINING" && unit === "exchange-app") return { tone: "warning", shape: "▲", word: "요청 흘려보내는 중" };
    }
    if (node.kind === "ai") return { tone: "success", shape: "●", word: "실행 중 · 가정" };
    if (unit === "failstop-db-backup" || unit === "exchange-bot-capacity") return { tone: "off", shape: "○", word: "타이머 대기" };
    return { tone: "success", shape: "●", word: "실행 중" };
  };
  const panelId = "node-detail-panel";

  return (
    <Modal
      name="node"
      width={880}
      height={640}
      initialFocus="[role=tab][aria-selected=true]"
      title={node.id}
      badge={<StateChip tone={chipTone(s)} label={s.word} />}
      sub={
        <>
          <span>{roleLine(node)}</span>
          {node.kind === "host" && (
            <span className="num">
              {node.host.instanceType} · {node.host.az}
            </span>
          )}
          {node.kind === "read" && (
            <span className="num">
              {node.node.instanceType} · {node.node.az}
            </span>
          )}
          {sim && <SimMark kind={node.kind === "ai" ? "assumed" : "sim"} />}
        </>
      }
      tools={
        <span className={styles.nav}>
          <button type="button" className={styles.navBtn} disabled={!prev} aria-label={prev ? `이전 노드 ${prev.id}` : "이전 노드 없음"} onClick={() => prev && onMove(prev.id)}>
            <AdminIcon name="chevronLeft" size={16} />
          </button>
          <span className={`${styles.navPos} num`} aria-label={`${list.length}대 가운데 ${at + 1}번째`}>
            {at + 1} / {list.length}
          </span>
          <button type="button" className={styles.navBtn} disabled={!next} aria-label={next ? `다음 노드 ${next.id}` : "다음 노드 없음"} onClick={() => next && onMove(next.id)}>
            <AdminIcon name="chevronRight" size={16} />
          </button>
        </span>
      }
      bar={<Segmented items={TABS} value={tab} onChange={setTab} ariaLabel="노드 상세" panelId={panelId} size="sm" />}
      footer={
        <>
          <span className={styles.note} style={{ marginRight: "auto" }}>
            {grafanaConfigured() ? (
              <GrafanaLink board="msp-infra" label={`03 AWS·서버에서 ${node.id} 추이`} />
            ) : (
              "추이는 Grafana 03 AWS·서버에서 봅니다(주소 미설정)"
            )}
          </span>
          {node.kind === "read" && (
            <Link href="/admin/capacity" className={controlStyles.stripLink} onClick={onClose}>
              대수는 용량·부하에서 바꿉니다
            </Link>
          )}
        </>
      }
      onClose={onClose}
    >
      <div id={panelId} role="tabpanel" aria-labelledby={`${panelId}-tab-${tab}`}>
        {tab === "overview" && (
          <div className={styles.twoCol}>
            <section className={styles.section} aria-label="정체">
              <span className={styles.sectionTitle}>정체</span>
              <dl className={styles.kv}>
                <dt>역할</dt>
                <dd>{roleLine(node)}</dd>
                {node.kind === "host" && node.host.partition && (
                  <>
                    <dt>맡은 마켓</dt>
                    <dd>
                      <b>{node.host.partition.id}</b> {joinDots(node.host.partition.markets.join(" · "))}
                    </dd>
                  </>
                )}
                <dt>규격 · AZ</dt>
                <dd className="num">
                  {node.kind === "host" ? `${node.host.instanceType} · ${node.host.az}` : node.kind === "read" ? `${node.node.instanceType} · ${node.node.az}` : `${node.server.hardware} · 연구소`}
                </dd>
                <dt>수집</dt>
                <dd className="num">
                  {node.kind === "host" ? (
                    node.host.collectedAt === null ? "◌ 미수집" : <Ago at={node.host.collectedAt} prefix="마지막 수집 " />
                  ) : node.kind === "ai" ? (
                    "가정 · 연결 경로 결정 전"
                  ) : node.node.state === "OFF" ? (
                    "꺼짐 · 수집 대상 아님"
                  ) : (
                    "앱 계층 평균에 듭니다"
                  )}
                </dd>
                {node.kind === "host" && (
                  <>
                    <dt>CPU 크레딧</dt>
                    <dd className="num">{node.host.cpuCredits ?? "◌"}</dd>
                  </>
                )}
                {node.kind === "ai" && (
                  <>
                    <dt>적재 모델</dt>
                    <dd>{node.server.model}</dd>
                  </>
                )}
                {asset && (
                  <>
                    <dt>OS · 커널</dt>
                    <dd>
                      <AssetValue asset={asset} k="os">
                        {asset.os}
                        {asset.kernel ? ` · 커널 ${asset.kernel}` : ""}
                      </AssetValue>
                    </dd>
                    <dt>가동 시간</dt>
                    <dd className="num">
                      <AssetValue asset={asset} k="bootedAt">
                        {asset.bootedAt ? (
                          <>
                            <Uptime at={asset.bootedAt} /> · {mdhm(asset.bootedAt)} 부팅
                          </>
                        ) : (
                          ""
                        )}
                      </AssetValue>
                    </dd>
                    <dt>보안 업데이트</dt>
                    <dd className="num">
                      <AssetValue asset={asset} k="securityUpdates">
                        {asset.securityUpdates === 0 ? "대기 없음" : `${asset.securityUpdates}건 대기`}
                      </AssetValue>
                    </dd>
                    <dt>inode</dt>
                    <dd className="num">
                      <AssetValue asset={asset} k="inodeUsedPct">
                        {asset.inodeUsedPct}% 사용
                      </AssetValue>
                    </dd>
                    <dt>실패한 서비스</dt>
                    <dd>
                      <AssetValue asset={asset} k="failedUnits">
                        {asset.failedUnits && asset.failedUnits.length > 0 ? asset.failedUnits.join(", ") : "없음"}
                      </AssetValue>
                    </dd>
                  </>
                )}
                {node.kind === "host" && (node.host.role === "DB_PRIMARY" || node.host.role === "DB_STANDBY") && (
                  <>
                    <dt>DB 통계</dt>
                    <dd className="num">
                      {dbStats === null ? (
                        <span className={styles.missingValue}>◌ 원천 연결 전</span>
                      ) : node.host.role === "DB_PRIMARY" ? (
                        `연결 ${dbStats.connections?.used ?? "◌"}/${dbStats.connections?.max ?? "◌"} · ${dbStats.tps ?? "◌"} TPS · WAL ${dbStats.wal?.primaryLsn ?? "◌"}`
                      ) : (
                        `재생 ${dbStats.wal?.standbyReplayLsn ?? "◌"} · 뒤처짐 ${walGap(dbStats) === null ? "◌" : bytesWord(walGap(dbStats) as number)}`
                      )}
                    </dd>
                  </>
                )}
              </dl>
            </section>
            <section className={styles.section} aria-label="지금 부하와 경보">
              {node.kind === "host" && (
                <>
                  <span className={styles.sectionTitle}>지금 부하</span>
                  <div className={controlStyles.gauges}>
                    <GaugeRow label="CPU" name="CPU 사용률" value={node.host.cpuPct} threshold={th(reg, "host.cpu_alert")} />
                    <GaugeRow label="MEM" name="메모리 사용률" value={node.host.memPct} threshold={th(reg, "host.mem_alert")} />
                    <GaugeRow
                      label="DISK"
                      name="디스크 사용률"
                      value={node.host.diskUsedPct}
                      threshold={th(reg, "host.disk_free_alert") === null ? null : 100 - (th(reg, "host.disk_free_alert") as number)}
                    />
                  </div>
                </>
              )}
              {node.kind === "read" && (
                <>
                  <span className={styles.sectionTitle}>지금 부하</span>
                  <div className={controlStyles.gauges}>
                    <GaugeRow label="CPU" name="CPU 사용률" value={node.node.cpuPct} threshold={th(reg, "read.scale_out_cpu")} />
                  </div>
                </>
              )}
              {node.kind === "ai" && (
                <>
                  <span className={styles.sectionTitle}>지금 부하</span>
                  <div className={controlStyles.gauges}>
                    <GaugeRow label="GPU" name="GPU 사용률" value={node.server.gpuPct} threshold={null} />
                    <GaugeRow label="MEM" name="통합 메모리" value={node.server.memTotalGb === null ? null : node.server.memUsedGb} max={node.server.memTotalGb ?? undefined} unit="GB" threshold={null} suffix={node.server.memTotalGb === null ? "" : `/${node.server.memTotalGb}`} />
                  </div>
                </>
              )}
              <div className={styles.sectionHead} style={{ marginTop: "var(--space-3)" }}>
                <span className={styles.sectionTitle}>지금 걸린 경보</span>
                <span className={`${styles.sectionMeta} num`}>{alerts.length}건</span>
              </div>
              {alerts.length === 0 ? (
                <div className={styles.empty}>이 노드에 걸린 경보가 없습니다</div>
              ) : (
                <ul className={styles.rows}>
                  {alerts.map((a) => (
                    <li key={a.id} className={styles.row}>
                      <span className={`${styles.rowTime} num`}>
                        <Ago at={a.since} />
                      </span>
                      <span className={styles.rowText}>
                        <StatusShape kind={GLYPH_SHAPE[SEVERITY_SHAPE[a.severity]]} tone={a.severity === "critical" ? "danger" : a.severity} className={controlStyles.shape} />
                        <b>{a.name}</b> · {a.detail}
                      </span>
                      <span />
                    </li>
                  ))}
                </ul>
              )}
              {openInc && (
                <button type="button" className={styles.linkBtn} onClick={() => onOpenIncident(openInc.id)}>
                  진행 중인 사건 · {openInc.title} 열기
                </button>
              )}
            </section>
          </div>
        )}
        {tab === "resources" && <Resources node={node} reg={reg} />}
        {tab === "services" && (
          <section className={styles.section} aria-label="서비스">
            <table className={styles.table}>
              <thead>
                <tr>
                  <th style={{ width: "32%" }}>서비스</th>
                  <th>하는 일</th>
                  <th style={{ width: "28%" }}>상태</th>
                </tr>
              </thead>
              <tbody>
                {services.map((sv) => {
                  const st = serviceState(sv.unit);
                  return (
                    <tr key={sv.unit}>
                      <td>
                        <span className={styles.unit}>{sv.unit}</span>
                        <span className="sr-only">.service</span>
                      </td>
                      <td>{sv.role}</td>
                      <td>
                        {unitsKnown ? (
                          <StateChip tone={chipTone(st)} label={st.word} />
                        ) : (
                          <span className={styles.missingValue} title={`원천 연결 전 · ${ASSET_SOURCE.failedUnits}`}>
                            ◌ 원천 연결 전
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <p className={styles.note}>
              {sim
                ? "서비스 이름은 node_exporter systemd 수집기의 수집 대상입니다. 상태는 노드 상태에서 가른 시뮬레이션 가정이고, 실측(node_systemd_unit_state)은 2단계에서 붙입니다."
                : "서비스 상태 원천(node_exporter의 node_systemd_unit_state)은 2단계에서 붙입니다."}
            </p>
          </section>
        )}
        {tab === "logs" && (
          <>
            <section className={styles.section} aria-label="관제 시스템 기록">
              <div className={styles.sectionHead}>
                <span className={styles.sectionTitle}>관제가 남긴 시스템 기록</span>
                <span className={`${styles.sectionMeta} num`}>{ev.system.length}건</span>
              </div>
              {ev.system.length === 0 ? (
                <div className={styles.empty}>이 노드에 대한 경보 발생·해제 기록이 없습니다</div>
              ) : (
                <ol className={controlStyles.events}>
                  {ev.system.slice(0, 12).map((e) => (
                    <EventRow key={e.id} e={e} />
                  ))}
                </ol>
              )}
            </section>
            <section className={styles.section} aria-label="journald 원문 요약">
              <div className={controlStyles.notConnected} data-compact="">
                <span className={controlStyles.notConnectedTitle}>
                  <span aria-hidden="true"><StatusShape kind="dashed" /> </span>journald 원문 요약 원천 연결 전
                </span>
                <span className={controlStyles.notConnectedSource}>
                  노드의 journald 로그를 모아 서비스별 오류·경고 수와 대표 문장을 적는 자리입니다 · 2단계. 원문 로그를 지어내지 않습니다
                </span>
              </div>
            </section>
          </>
        )}
        {tab === "actions" && (
          <section className={styles.section} aria-label="조치 기록">
            <div className={styles.sectionHead}>
              <span className={styles.sectionTitle}>운영자와 AI가 한 일</span>
              <span className={`${styles.sectionMeta} num`}>{ev.actions.length}건</span>
            </div>
            {ev.actions.length === 0 ? (
              <div className={styles.empty}>이 노드에 한 조치 기록이 없습니다</div>
            ) : (
              <ol className={controlStyles.events}>
                {ev.actions.slice(0, 16).map((e) => (
                  <EventRow key={e.id} e={e} />
                ))}
              </ol>
            )}
            <p className={styles.note}>조치는 조치 카드와 승인 절차에서만 합니다. 이 창에는 실행 단추가 없습니다.</p>
          </section>
        )}
      </div>
    </Modal>
  );
}
