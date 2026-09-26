/**
 * 서비스 흐름 시안의 시뮬레이터. 화면은 AWS 구성(v5)처럼 그리고, 그 역할을 로컬 Docker
 * exchange-chaos 스택(compose.yaml 이 포함하는 harness/docker-compose.chaos.yml)이 대신한다.
 * Docker 에서 먼저 되면 같은 화면을 AWS 로 옮긴다. 대응은 이렇다.
 *
 *   CloudFront  → 없음. 사용자가 목표 구성대로 켜진 노드로 그리기로 했다(2026-09-23). 상태 글자에 목표 구성이라고 적는다
 *   ALB         → chaos-lb (nginx. 쓰기는 1차·백업, 조회는 least_conn)
 *   app-1·2     → chaos-api-1·2. 매칭 리더를 다투는 두 노드이고 조회도 함께 받는다. 이름은 관제센터와 같다
 *   app-3       → chaos-api-3. 조회 전용 자동 증설 노드라 기본으로 꺼져 있다
 *   Auto Scaling → capacity_reconciler.py (호스트). 희망 대수를 따라 docker start/stop 한다. 구성도에서는
 *                 노드가 아니라 app-3 한 자리만 감싸는 그룹 상자로 그린다(asgView)
 *   DB 두 대    → chaos-pg(db-primary)와 chaos-pg-standby(db-standby). Standby 는 아직 compose 에 없어 2단계에서 붙인다
 *   bot-1       → chaos-bots(시장 봇)와 obs 프로필(Prometheus·Grafana). AWS 에서는 한 호스트다
 *   load-1      → read-load.py (호스트)
 *
 * chaos-toxiproxy 는 AWS 에 없는 Docker 전용 장애 주입기라 구성도에서 뺐다(2026-09-23 사용자 결정).
 * 앱이 어느 DB 를 보는지(endpoint)만 상태로 들고 연결선 이름표로 보인다. AWS 에서 이 전환은
 * switch-db-endpoint.py 가 하고, Docker 에서는 2단계에서 같은 모양으로 스크립트화한다.
 *
 * 앱 계층은 2~3대다(ADR 0030, 사용자 정정 2026-09-25). 쓰기는 매칭 리더(app-1·2)로 가고, 조회는 app-1·app-2 와
 * 켜져 있는 app-3 에 나뉜다. Auto Scaling 은 app-3 만 켜고 끈다. 리더는 AWS 가 임의로 종료하지 않게 그룹 밖이다.
 * Docker 대역도 같은 모양이라 매칭 노드를 멈추면 그 노드의 조회도 빠지고 남은 노드가 받는다. 매칭은 지금 코드의
 * 단일 리더(리스 경쟁)로 재생한다. 마켓별 리더(ADR 0034)는 구현 전이라 이 재생의 측정 기록이 없다.
 *
 * 사건의 순서와 간격은 로컬 Docker 측정 기록을 따르고 출처는 describeScenario 가 돌려준다.
 * 기록이 없는 구간(사람의 판단, 앱 DB 접속 전환)은 가정이라고 적는다. 사건 사이의 값은 지어낸
 * 것이라 화면이 늘 시뮬레이션임을 표시해야 한다. 같은 조작이 같은 그래프를 그리도록 난수 대신
 * 틱에서 정해지는 잡음을 쓴다.
 *
 * 재생하지 않는 동안 화면은 이 재생 세계가 아니라 관제 시뮬레이터의 한 판을 그린다(관제 값 모드,
 * 관리자 설계 4-4 sim 단일 원천). flowFromCenter 가 그 판을 같은 FlowState 꼴로 옮기고, 매칭 리더는
 * 재생의 단일 리더 대신 마켓 파티션 리더 둘(live.partitions)로 적는다.
 */

import { tradesPerSec } from "./opsSim";
import type { ControlCenter, IncidentEvent, OpsFlowTapeEntry, OpsHost } from "./types";

export type Health = "success" | "warning" | "danger";
/** 매칭 노드(app-1·2). 쓰기와 리더 리스를 다룬다 */
export type EngineId = "api1" | "api2";
/** 조회 전용 자동 증설 노드(app-3). Auto Scaling 그룹이 켜고 끈다. 매칭 리더 두 대도 조회를 받는다 */
export type ReadId = "app3";
export type AppId = EngineId | ReadId;
export type DbId = "pg1" | "pg2";
export type AppStatus = "running" | "stopped" | "killed" | "booting" | "draining";
export type DbStatus = "primary" | "standby" | "stopped" | "rejoining";
export type ScenarioId = "traffic" | "scale" | "leaderKill" | "restart" | "dbSwitch" | "rejoin";
/** 재생 대상. 앱 노드를 누르는 재생과 DB 를 누르는 재생이 있다 */
export type TargetId = AppId | DbId;

export const ENGINE_IDS: EngineId[] = ["api1", "api2"];
export const READ_IDS: ReadId[] = ["app3"];
export const APP_IDS: AppId[] = [...ENGINE_IDS, ...READ_IDS];
export const DB_IDS: DbId[] = ["pg1", "pg2"];
/** 화면 이름은 AWS 역할 이름이고 관제센터와 같다. 컨테이너 이름은 옆에 따로 적는다 */
export const APP_NAME: Record<AppId, string> = { api1: "app-1", api2: "app-2", app3: "app-3" };
/** 그 역할을 Docker 대역에서 맡은 컨테이너 */
export const APP_CONTAINER: Record<AppId, string> = {
  api1: "chaos-api-1",
  api2: "chaos-api-2",
  app3: "chaos-api-3",
};
export const APP_PORT: Record<AppId, number> = { api1: 18080, api2: 28080, app3: 38080 };
export const DB_NAME: Record<DbId, string> = { pg1: "db-primary", pg2: "db-standby" };
export const DB_CONTAINER: Record<DbId, string> = { pg1: "chaos-pg", pg2: "chaos-pg-standby" };

/** 리더 리스 TTL 과 갱신 주기(docker-compose.chaos.yml:100-101) */
export const LEASE_TTL = 6;
export const LEASE_TICK = 2;
/** 봇 기본 발행률(chaos.yml:224 CHAOS_BOTS_OPS 기본값) */
export const BASE_OPS = 10;
/** 봇 급증 목표. 같은 스택에서 이 값으로 체결 초당 18.1건을 잰 주행이 있다(하네스_검증설계.md:495) */
export const SURGE_OPS = 300;
/** app-3 켜기 권고 임계. admin-chaos 프로필 값이며 AWS 운영값 0.60 과 다르다(harness/README.md:48) */
export const SCALE_OUT_ADVICE = 0.25;
/** 첫 기동 스택의 세대. 대표 측정(stage9)이 새 스택에서 epoch 1 → 2 였다 */
export const START_EPOCH = 1;
/** 재편입 재생 배속. 측정 272.5초를 그대로 틀면 시연이 멈춘 것처럼 보인다 */
export const REJOIN_SPEED = 10;

export interface FlowEvent {
  /** 사건이 일어난 틱. 표본과 같은 축이라 그래프 표식이 표본 위에 정확히 놓인다 */
  at: number;
  health: Health;
  label: string;
}

export interface AppNode {
  status: AppStatus;
  /** LB 조회 업스트림(exchange_read)에 들어 있는가. app-1·2 는 살아 있는 동안 들고, app-3 은 조정기가 down 표식을 토글한다 */
  inRead: boolean;
}

/** 한 틱의 표본. null 은 그 순간 값을 낼 원천이 없었다는 뜻이고 0 으로 채우지 않는다 */
export interface FlowSample {
  at: number;
  /** 체결 건수/초. DB 체결 행 기준이라 쓰기 공백 동안의 0 은 결측이 아니라 실제 0 이다 */
  trades: number;
  botOps: number;
  /** 리더 리스 잔여 초. 보유자가 없으면 0 */
  lease: number;
  /** 앱 노드 CPU. 꺼진 컨테이너는 잴 것이 없어 미수집이다 */
  cpu: Record<AppId, number | null>;
  /** 앱 계층 조회 사용률(0..1). app-3 켜기·끄기의 기준이다 */
  util: number;
  /** 앱 계층 대수(2~3). 목표는 capacity_plan.desired_nodes, 등록은 LB 조회 업스트림에 든 수 */
  desired: number;
  registered: number;
  /** DB 복제 지연 KB. 복제가 끊겼으면 잴 상대가 없어 미수집이다 */
  replLagKb: number | null;
}

/** 관제 값 모드의 파티션 리더(ADR 0034). 재생은 단일 리더라 쓰지 않는다 */
export interface FlowPartition {
  id: string;
  holder: EngineId;
  epoch: number;
}

/**
 * 관제 값 모드에서만 있는 값. 관제 시뮬레이터의 판에서 옮긴다. 재생 세계에서는 null 이고 그때는 단일 리더(holder)와
 * 로컬 조정기의 권고 임계(SCALE_OUT_ADVICE)를 쓴다
 */
export interface FlowLive {
  partitions: FlowPartition[];
  /** 증설(app-3 켜기)·축소(app-3 끄기) 임계(0..1). 임계값 관리의 현재값 */
  scaleOut: number;
  scaleIn: number;
  min: number;
  max: number;
  botTarget: number;
  /** 조회 경로의 대략의 초당 요청. 선의 세기만 가른다 */
  readRate: number;
  /** 앱이 아닌 노드의 호스트 CPU(서버·노드 화면과 같은 값). 인스턴스 표의 CPU 칸이 쓴다 */
  hostCpu: { pg1: number | null; pg2: number | null; bot: number | null };
}

export interface FlowState {
  tick: number;
  scenario: ScenarioId | null;
  elapsed: number;
  step: number;
  /** 재생 대상. 리더 중단은 시작 때의 리더, 재기동·재편입은 고른 노드, DB 전환은 그때의 Primary */
  target: TargetId | null;
  /** DB 전환이 시작될 때의 리더. 새 Primary 에서 리스를 다시 잡을 노드다 */
  prevHolder: EngineId | null;
  botOps: number;
  /** 봇 목표치를 천천히 따라가는 실효 발행률. chaos-bots 재생성과 예열을 흉내 낸다 */
  opsEff: number;
  readVu: number;
  apps: Record<AppId, AppNode>;
  /** 리더 리스 보유자. 보유자가 쓰기를 못 해도 리스가 만료되기 전까지는 이름이 남고, 만료되면 비운다 */
  holder: EngineId | null;
  epoch: number;
  lease: number;
  desired: 2 | 3;
  db: Record<DbId, DbStatus>;
  /** 앱이 접속하는 DB. AWS 에서는 앱 설정의 DB 주소, Docker 에서는 2단계에서 같은 역할을 붙인다 */
  endpoint: DbId;
  replicating: boolean;
  util: number;
  utilTarget: number;
  cpuEff: Record<AppId, number>;
  adviceOut: boolean;
  events: FlowEvent[];
  live: FlowLive | null;
}

const otherEngine = (id: EngineId): EngineId => (id === "api1" ? "api2" : "api1");
const otherDb = (id: DbId): DbId => (id === "pg1" ? "pg2" : "pg1");
export const isEngine = (t: TargetId | NodeId | null | undefined): t is EngineId => t === "api1" || t === "api2";
export const isRead = (t: TargetId | NodeId | null | undefined): t is ReadId => t === "app3";
const isDb = (t: TargetId | null | undefined): t is DbId => t === "pg1" || t === "pg2";
const appTarget = (s: FlowState): EngineId => (isEngine(s.target) ? s.target : "api1");
const dbTarget = (s: FlowState): DbId => (isDb(s.target) ? s.target : "pg1");

/** 지금 Primary. 둘 다 아니면(전환 공백) null */
export function primaryDb(s: FlowState): DbId | null {
  return DB_IDS.find((d) => s.db[d] === "primary") ?? null;
}

/** 앱이 DB 에 쓸 수 있는가. 접속 대상이 살아 있는 Primary 여야 한다 */
function dbReachable(s: FlowState): boolean {
  return s.db[s.endpoint] === "primary";
}

interface Step {
  after: number;
  health: Health;
  label: (s: FlowState) => string;
  apply: (s: FlowState) => void;
}

const promote = (s: FlowState) => {
  s.holder = otherEngine(appTarget(s));
  s.epoch += 1;
  s.lease = LEASE_TTL;
};

const SCENARIOS: Record<ScenarioId, { steps: Step[]; length: number }> = {
  traffic: {
    length: 60,
    steps: [
      { after: 0, health: "warning", label: () => `봇 ${SURGE_OPS} OPS`, apply: (s) => { s.botOps = SURGE_OPS; } },
      { after: 40, health: "success", label: () => `봇 ${BASE_OPS} OPS 원복`, apply: (s) => { s.botOps = BASE_OPS; } },
    ],
  },
  // 2026-09-23 종단 리허설(하네스_검증설계.md:493, 497). 승인 시점(12초)만 시연용으로 정했다
  scale: {
    length: 100,
    steps: [
      { after: 0, health: "warning", label: () => "load-1 조회 부하 20 VU", apply: (s) => { s.readVu = 20; s.utilTarget = 0.58; } },
      { after: 12, health: "warning", label: () => "app-3 켜기 승인 · 목표 3대", apply: (s) => { s.desired = 3; } },
      { after: 13, health: "warning", label: () => "app-3 기동", apply: (s) => { s.apps.app3 = { status: "booting", inRead: false }; } },
      {
        // 기동부터 LB 등록까지 10.9초. 합류 직후는 예열 중이라 평균이 오히려 74% 로 올랐다
        after: 24,
        health: "success",
        label: () => "app-3 ALB 등록",
        apply: (s) => { s.apps.app3 = { status: "running", inRead: true }; s.utilTarget = 0.74; },
      },
      { after: 30, health: "success", label: () => "예열 끝 · 사용률 49%", apply: (s) => { s.utilTarget = 0.49; } },
      // 희망 대수 상향부터 관측 3대까지 24.0초
      { after: 36, health: "success", label: () => "관측 3대 · 계약 일치", apply: (s) => { s.utilTarget = 0.2; } },
      { after: 60, health: "warning", label: () => "app-3 끄기 승인 · 목표 2대", apply: (s) => { s.desired = 2; } },
      {
        after: 61,
        health: "warning",
        label: () => "ALB 제외 · 드레이닝 10초",
        apply: (s) => { s.apps.app3 = { status: "draining", inRead: false }; s.utilTarget = 0.27; },
      },
      { after: 71, health: "success", label: () => "app-3 중지", apply: (s) => { s.apps.app3 = { status: "stopped", inRead: false }; } },
      // 축소는 요청부터 관측 2대까지 18.3초. 부하를 유지한 채라 평균이 27% 로 다시 올랐다
      { after: 78, health: "success", label: () => "관측 2대 · 사용률 27%", apply: () => {} },
      { after: 88, health: "success", label: () => "조회 부하 종료", apply: (s) => { s.readVu = 0; s.utilTarget = 0.05; } },
    ],
  },
  // stage9 E4(2026-09-15). kill 0.2초, 종료 0.5초, 승격과 첫 체결 7.9초
  leaderKill: {
    length: 15,
    steps: [
      {
        after: 0,
        health: "danger",
        label: (s) => `${APP_NAME[appTarget(s)]} kill`,
        // 쓰기 업스트림(nginx) 설정에는 그대로 남아 쓰기 요청이 실패로 넘어간다
        apply: (s) => { s.apps[appTarget(s)] = { status: "killed", inRead: false }; },
      },
      {
        after: 8,
        health: "success",
        label: (s) => `${APP_NAME[otherEngine(appTarget(s))]} 승격 · epoch ${s.epoch + 1}`,
        apply: promote,
      },
    ],
  },
  // 냉기동 측정(chaos-runs/COLD-20260915-132439.json)에서 팔로워 부팅이 13.0초였다. kill 뒤 한 대만
  // 다시 켜는 경우는 따로 잰 적이 없어 가장 가까운 팔로워 값을 쓴다
  restart: {
    length: 15,
    steps: [
      {
        after: 0,
        health: "warning",
        label: (s) => `${APP_NAME[appTarget(s)]} 재기동`,
        apply: (s) => { s.apps[appTarget(s)] = { status: "booting", inRead: false }; },
      },
      {
        after: 13,
        health: "success",
        // 되살아난 노드는 리더 대기로 합류하고 조회 업스트림에도 다시 든다
        label: (s) => `${APP_NAME[appTarget(s)]} 리더 대기 합류`,
        apply: (s) => { s.apps[appTarget(s)] = { status: "running", inRead: true }; },
      },
    ],
  },
  // 로컬 Docker primary·standby 측정(harness/evidence/failover-rpo-20260912)은 승격 명령만 1.7~1.9초로
  // 쟀다. 사람이 알아차리고 판단하는 시간, 엔드포인트 전환, 리스 재획득은 잰 적이 없어 가정이다
  dbSwitch: {
    length: 30,
    steps: [
      {
        after: 0,
        health: "danger",
        label: (s) => `Primary ${DB_NAME[dbTarget(s)]} 중단`,
        apply: (s) => {
          s.db[dbTarget(s)] = "stopped";
          s.replicating = false;
        },
      },
      { after: 15, health: "warning", label: () => "운영자 승격 결정 · 가정", apply: () => {} },
      {
        after: 17,
        health: "success",
        label: (s) => `${DB_NAME[otherDb(dbTarget(s))]} 승격 · pg_promote`,
        apply: (s) => { s.db[otherDb(dbTarget(s))] = "primary"; },
      },
      {
        after: 19,
        health: "success",
        label: (s) => `앱 DB 접속 전환 → ${DB_NAME[otherDb(dbTarget(s))]}`,
        apply: (s) => { s.endpoint = otherDb(dbTarget(s)); },
      },
      {
        after: 21,
        health: "success",
        label: (s) => `${APP_NAME[s.prevHolder ?? "api1"]} 리스 획득 · epoch ${s.epoch + 1}`,
        apply: (s) => {
          const next = s.prevHolder && s.apps[s.prevHolder].status === "running" ? s.prevHolder : "api2";
          s.holder = next;
          s.epoch += 1;
          s.lease = LEASE_TTL;
        },
      },
    ],
  },
  // 같은 측정에서 pg_rewind 가 실패해 전량 베이스백업 264.717초, 스트리밍 재개까지 272.549초였다. 1/10 로 튼다
  rejoin: {
    length: 30,
    steps: [
      {
        after: 0,
        health: "warning",
        label: (s) => `${DB_NAME[dbTarget(s)]} 베이스백업 시작`,
        apply: (s) => { s.db[dbTarget(s)] = "rejoining"; },
      },
      { after: 26, health: "success", label: () => "베이스백업 완료 · 실측 264.7초", apply: () => {} },
      {
        after: 27,
        health: "success",
        label: (s) => `${DB_NAME[dbTarget(s)]} Standby 합류 · 스트리밍 재개`,
        apply: (s) => {
          s.db[dbTarget(s)] = "standby";
          s.replicating = true;
        },
      },
    ],
  },
};

/**
 * 재생 세계의 첫 상태. leader 는 리스를 먼저 잡은 노드이고 기본은 Flyway 를 먼저 돌리는 app-1 이다. 관제 값 모드에서
 * app-2 의 리더 중단을 고르면 app-2 가 리더인 세계에서 재생을 시작한다(두 파티션 리더 가운데 고른 쪽을 멈춘다)
 */
export function createFlow(leader: EngineId = "api1"): FlowState {
  return {
    tick: 0,
    scenario: null,
    elapsed: 0,
    step: 0,
    target: null,
    prevHolder: null,
    botOps: BASE_OPS,
    opsEff: BASE_OPS,
    readVu: 0,
    // 매칭 리더 두 대는 쓰기와 조회를 함께 받는다. 앱 계층 최소 2대가 이 두 대이고, app-3 은 자동 증설 자리라 꺼져 있다
    apps: {
      api1: { status: "running", inRead: true },
      api2: { status: "running", inRead: true },
      app3: { status: "stopped", inRead: false },
    },
    // chaos-api-1 이 Flyway 를 돌리고 먼저 리스를 잡는다(chaos.yml:87-141)
    holder: leader,
    epoch: START_EPOCH,
    lease: LEASE_TTL,
    desired: 2,
    db: { pg1: "primary", pg2: "standby" },
    endpoint: "pg1",
    replicating: true,
    util: 0.05,
    utilTarget: 0.05,
    cpuEff: { api1: 12, api2: 12, app3: 0 },
    adviceOut: false,
    events: [],
    live: null,
  };
}

export function isBusy(s: FlowState): boolean {
  return s.scenario !== null && s.elapsed <= SCENARIOS[s.scenario].length;
}

/** 이 매칭 노드가 쓰기 리더인가. 관제 값 모드는 파티션 하나라도 맡으면 리더다 */
export function isLeader(s: FlowState, id: EngineId): boolean {
  return s.live !== null ? s.live.partitions.some((p) => p.holder === id) : s.holder === id;
}

/** 이 노드가 맡은 파티션. 재생 세계와 파티션이 없는 노드는 빈 배열이다 */
export function partitionsOf(s: FlowState, id: EngineId): FlowPartition[] {
  return s.live === null ? [] : s.live.partitions.filter((p) => p.holder === id);
}

/** 리스 보유자가 살아 있고 DB 에 닿아서 쓰기를 받을 수 있는가 */
export function writable(s: FlowState): boolean {
  if (s.live !== null) return s.live.partitions.some((p) => s.apps[p.holder].status === "running") && dbReachable(s);
  return s.holder !== null && s.apps[s.holder].status === "running" && dbReachable(s);
}

export interface Availability {
  ok: boolean;
  /** 막힌 이유. 버튼 옆에 그대로 적는다 */
  reason?: string;
}

/** 시나리오를 지금 시작할 수 있는가. target 은 노드를 눌러 시작하는 시나리오에만 쓴다 */
export function availability(s: FlowState, id: ScenarioId, target?: TargetId): Availability {
  if (isBusy(s)) return { ok: false, reason: "다른 재생 진행 중" };
  switch (id) {
    case "traffic":
      return writable(s) ? { ok: true } : { ok: false, reason: "쓰기 리더 없음" };
    case "scale":
      if (s.apps.app3.status !== "stopped") return { ok: false, reason: "app-3 이미 실행 중" };
      return { ok: true };
    case "leaderKill": {
      if (!writable(s) || s.holder === null) return { ok: false, reason: "쓰기 리더 없음" };
      if (target !== undefined && target !== s.holder) return { ok: false, reason: "리더 노드에만 적용" };
      if (s.apps[otherEngine(s.holder)].status !== "running") {
        return { ok: false, reason: "리더 대기 노드 없음 · 전체 중단 위험" };
      }
      return { ok: true };
    }
    case "restart":
      if (!isEngine(target) || s.apps[target].status !== "killed") return { ok: false, reason: "중단된 매칭 노드에만 적용" };
      return { ok: true };
    case "dbSwitch": {
      const p = primaryDb(s);
      if (p === null) return { ok: false, reason: "Primary 없음" };
      if (target !== undefined && target !== p) return { ok: false, reason: "Primary 에만 적용" };
      if (s.db[otherDb(p)] !== "standby" || !s.replicating) return { ok: false, reason: "복제 중인 Standby 없음" };
      if (!writable(s)) return { ok: false, reason: "쓰기 리더 없음" };
      return { ok: true };
    }
    case "rejoin":
      if (!isDb(target) || s.db[target] !== "stopped") return { ok: false, reason: "중지된 DB 에만 적용" };
      if (primaryDb(s) === null) return { ok: false, reason: "베이스백업 받을 Primary 없음" };
      return { ok: true };
  }
}

export function startScenario(prev: FlowState, id: ScenarioId, target?: TargetId): FlowState {
  if (!availability(prev, id, target).ok) return prev;
  const s = structuredClone(prev);
  s.scenario = id;
  s.elapsed = 0;
  s.step = 0;
  s.prevHolder = s.holder;
  s.target =
    id === "restart" || id === "rejoin"
      ? (target ?? null)
      : id === "leaderKill"
        ? s.holder
        : id === "dbSwitch"
          ? primaryDb(s)
          : null;
  runDueSteps(s);
  return s;
}

function runDueSteps(s: FlowState): void {
  if (s.scenario === null) return;
  const steps = SCENARIOS[s.scenario].steps;
  while (s.step < steps.length && steps[s.step].after <= s.elapsed) {
    const step = steps[s.step];
    // 이름표는 적용 전에 뽑는다. 승격 사건은 올라가기 전 세대에 1 을 더해 적는다
    const label = step.label(s);
    step.apply(s);
    s.events.push({ at: s.tick, health: step.health, label });
    s.step += 1;
  }
}

/** 틱마다 달라지는 -1..1 잡음. 같은 틱과 채널이면 늘 같은 값 */
function noise(tick: number, channel: number): number {
  const x = Math.sin(tick * 12.9898 + channel * 78.233) * 43758.5453;
  return (x - Math.floor(x)) * 2 - 1;
}

/** 봇 발행률에 따른 체결률. 관제 시뮬레이터와 같은 곡선이다(opsSim.tradesPerSec) */
const tradesFor = tradesPerSec;

/**
 * 노드 CPU 의 목표값. 조회를 받는 노드는 앱 계층 조회 사용률을 따르고, 매칭 리더는 그 위에 쓰기 몫(봇 발행률)을
 * 더한다. 조회 몫이 사용률을 따라가야 Auto Scaling 머리의 사용률과 카드의 CPU 가 서로 다른 말을 하지 않는다
 */
function cpuTarget(s: FlowState, id: AppId): number {
  const n = s.apps[id];
  if (n.status === "stopped" || n.status === "killed") return 0;
  if (n.status === "booting") return 55;
  if (n.status === "draining") return 10;
  const readShare = n.inRead ? 3 + s.util * 95 : 5;
  if (isRead(id)) return readShare;
  const writeShare = s.holder === id && writable(s) ? s.opsEff * 0.11 : 0;
  return readShare + writeShare;
}

export function stepFlow(prev: FlowState): { state: FlowState; sample: FlowSample } {
  const s = structuredClone(prev);
  s.tick += 1;
  if (s.scenario !== null) {
    s.elapsed += 1;
    runDueSteps(s);
  }

  s.opsEff += (s.botOps - s.opsEff) / 3;
  s.util += (s.utilTarget - s.util) / 2;

  // 리스: 보유자가 살아 있고 DB 에 닿으면 2초마다 TTL 로 갱신한다. 아니면 그대로 줄어든다.
  // 이번 틱에 승격했다면 막 잡은 리스이니 깎지 않는다. 틱 홀짝에 따라 새 리더가 5초로 보이면 안 된다
  const promotedNow = s.epoch !== prev.epoch;
  const renewing = s.holder !== null && s.apps[s.holder].status === "running" && dbReachable(s);
  if (promotedNow || (renewing && s.tick % LEASE_TICK === 0)) s.lease = LEASE_TTL;
  else s.lease = Math.max(0, s.lease - 1);
  // 만료된 리스는 누구의 것도 아니다. 이름을 남겨 두면 승격 전 몇 초 동안 화면 곳곳이 쓰지 못하는
  // 노드를 리더로 그린다
  if (s.lease === 0 && !renewing && s.holder !== null) {
    s.holder = null;
    s.events.push({ at: s.tick, health: "danger", label: "리스 만료 · 쓰기 리더 없음" });
  }

  if (!s.adviceOut && s.util >= SCALE_OUT_ADVICE && s.desired === 2) {
    s.adviceOut = true;
    s.events.push({ at: s.tick, health: "warning", label: `증설 권고 · 사용률 ${Math.round(SCALE_OUT_ADVICE * 100)}% 초과` });
  } else if (s.adviceOut && s.desired === 3) {
    // 권고를 받아들여 목표가 올라갔다. 해제가 아니라 이행이라 사건을 따로 남기지 않는다
    s.adviceOut = false;
  } else if (s.adviceOut && s.util < SCALE_OUT_ADVICE * 0.8) {
    // 임계 바로 아래에서 켜졌다 꺼졌다 하지 않게 20% 여유를 둔다
    s.adviceOut = false;
    s.events.push({ at: s.tick, health: "success", label: "증설 권고 해제" });
  }

  const cpu = {} as Record<AppId, number | null>;
  for (const id of APP_IDS) {
    s.cpuEff[id] += (cpuTarget(s, id) - s.cpuEff[id]) / 3;
    const st = s.apps[id].status;
    cpu[id] =
      st === "stopped" || st === "killed"
        ? null
        : Math.max(0, Math.min(100, s.cpuEff[id] + noise(s.tick, 10 + APP_IDS.indexOf(id)) * 1.2));
  }

  const sample: FlowSample = {
    at: s.tick,
    trades: writable(s) ? Math.max(0, tradesFor(s.opsEff) + noise(s.tick, 1) * 0.4) : 0,
    botOps: s.opsEff,
    lease: s.lease,
    cpu,
    util: Math.max(0, Math.min(1, s.util + noise(s.tick, 3) * 0.01)),
    desired: s.desired,
    registered: APP_IDS.filter((a) => s.apps[a].inRead).length,
    // 쓰기가 흐를 때만 WAL 이 쌓여 지연이 생긴다. 값은 지어낸 것이다
    replLagKb: s.replicating ? Math.max(0, 1.5 + (writable(s) ? s.opsEff * 0.06 : 0) + noise(s.tick, 5) * 0.6) : null,
  };
  return { state: s, sample };
}

/** 차트 한 줄의 점. cut 은 바로 뒤에 공백이 온다는 표시다 */
export interface LinePoint {
  at: number;
  value: number | null;
  cut: boolean;
}

/**
 * 표본 열을 선 점으로 바꾼다. lightweight-charts 선 계열은 값 있는 점만 이어 그리므로 공백을
 * 그대로 넘기면 공백을 가로질러 직선을 긋는다. 노드가 꺼진 구간이 완만한 하강처럼 보인다.
 * 한 구간의 색은 앞 점에서 오므로 공백 직전 점에 cut 을 달아 그 구간만 투명하게 칠한다.
 */
export function linePoints(samples: FlowSample[], pick: (s: FlowSample) => number | null): LinePoint[] {
  return samples.map((s, i) => {
    const value = pick(s);
    const gapNext = i + 1 < samples.length && pick(samples[i + 1]) === null;
    return { at: s.at, value, cut: value !== null && gapNext };
  });
}

/* ===== 화면이 쓰는 파생 ===== */

export type NodeId = "users" | "cdn" | "alb" | AppId | DbId | "bot" | "obs" | "load";

/**
 * 노드 색 상태. 사용자가 정한 규칙이다(2026-09-23): 실행은 초록, 중지는 회색, 장애는 빨강.
 * 기동·드레이닝·재편입은 전환 중(주황), 이 대역에도 AWS 에도 없는 것은 미구축(회색)이다
 */
export type NodeState = "running" | "stopped" | "failure" | "transition" | "absent";

export interface NodeView {
  /** AWS 역할 이름 */
  name: string;
  /** Docker 에서 그 역할을 맡은 컨테이너나 호스트 프로세스. 없으면 null */
  container: string | null;
  role: string;
  /** 상태 한 줄. 형태 기호·라벨과 함께 쓰이므로 짧은 명사로 둔다 */
  status: string;
  health: Health;
  state: NodeState;
}

const HEALTH_OF: Record<NodeState, Health> = {
  running: "success",
  stopped: "success",
  failure: "danger",
  transition: "warning",
  absent: "success",
};

function view(name: string, container: string | null, role: string, status: string, state: NodeState): NodeView {
  return { name, container, role, status, state, health: HEALTH_OF[state] };
}

function appView(s: FlowState, id: AppId, cpuNow: number | null): NodeView {
  const n = s.apps[id];
  const parts = isEngine(id) ? partitionsOf(s, id) : [];
  const role = isRead(id)
    ? "조회 전용 · Auto Scaling"
    : parts.length > 0
      ? `매칭 리더 · ${parts.map((p) => `${p.id} epoch ${p.epoch}`).join(" · ")} · 조회`
      : s.holder === id
        ? `매칭 리더 · epoch ${s.epoch} · 조회`
        : n.status === "running" || n.status === "booting"
          ? "리더 대기 · 쓰기 백업 · 조회"
          : "리더 경쟁 제외";
  const cpu = cpuNow === null ? "CPU 미수집" : `CPU ${Math.round(cpuNow)}%`;
  const v = (status: string, state: NodeState) => view(APP_NAME[id], APP_CONTAINER[id], role, status, state);
  switch (n.status) {
    case "stopped":
      // app-3 은 멈춘 것이 아니라 자동 증설을 기다리며 꺼져 있다. 관제센터 앱 계층 칸과 같은 말을 쓴다
      return v(isRead(id) ? "꺼짐" : "중지", "stopped");
    case "killed":
      return v("중단 · exit 137", "failure");
    case "booting":
      return v("기동 중", "transition");
    case "draining":
      return v("드레이닝", "transition");
    case "running":
      // 앱 노드는 쓰기와 조회를 같은 Primary 에 보낸다. 접속한 DB 가 Primary 가 아니면 둘 다 멈춘다
      if (s.db[s.endpoint] !== "primary") return v("DB 접속 불가", "failure");
      return v(cpu, "running");
  }
}

function dbView(s: FlowState, id: DbId): NodeView {
  const st = s.db[id];
  // Standby 컨테이너는 아직 compose 에 없다. 2단계에서 붙인다는 사실을 이름 옆에 남긴다
  const container = id === "pg2" ? `${DB_CONTAINER[id]} · 2단계 추가` : DB_CONTAINER[id];
  switch (st) {
    case "primary":
      return view(DB_NAME[id], container, "Primary · 쓰기", s.endpoint === id ? "실행 중 · 앱 접속" : "실행 중 · 앱 미접속", "running");
    case "standby":
      return view(DB_NAME[id], container, "Standby · 비동기 복제", s.replicating ? "복제 수신 중" : "복제 끊김", s.replicating ? "running" : "failure");
    case "rejoining":
      return view(DB_NAME[id], container, "재편입 · 베이스백업", "베이스백업 중", "transition");
    case "stopped":
      return view(DB_NAME[id], container, "옛 Primary", "중지", s.endpoint === id ? "failure" : "stopped");
  }
}

export function nodeViews(s: FlowState, cpuNow: Record<AppId, number | null>): Record<NodeId, NodeView> {
  const w = writable(s);
  return {
    users: view("사용자", "localhost:58080", "웹 · 봇 주문", w ? "접속 가능" : "쓰기 불가", w ? "running" : "failure"),
    // 대역에는 CloudFront 가 없다. 사용자가 목표 구성대로 켜진 노드로 두기로 해서 초록으로 그리되, 상태 글자로
    // 실측이 아니라 목표 구성임을 밝힌다. 팝오버는 Docker 대역이 ALB 로 바로 들어간다는 사실을 적는다
    cdn: view("CloudFront", null, "엣지 · 정적 배포 · 캐시", "목표 구성", "running"),
    alb: view(
      "ALB",
      "chaos-lb",
      "쓰기 → 매칭 리더 · 조회 → 앱 계층",
      !w ? "쓰기 대상 없음" : s.live !== null ? `쓰기 → ${ENGINE_IDS.filter((e) => isLeader(s, e)).map((e) => APP_NAME[e]).join("·")}` : s.holder ? `쓰기 → ${APP_NAME[s.holder]}` : "쓰기 대상 없음",
      w ? "running" : "failure",
    ),
    api1: appView(s, "api1", cpuNow.api1),
    api2: appView(s, "api2", cpuNow.api2),
    app3: appView(s, "app3", cpuNow.app3),
    pg1: dbView(s, "pg1"),
    pg2: dbView(s, "pg2"),
    bot: view("bot-1 · 시장 봇", "chaos-bots", "주문 발행", w ? `${s.botOps} OPS` : `${s.botOps} OPS · 주문 실패`, "running"),
    // obs 는 선택 프로필이다. 앱 지표는 app-1·app-2·봇만 긁고, 컨테이너 CPU 는 chaos-cstats 가 전부 낸다
    obs: view("bot-1 · 관측", "chaos-prometheus · chaos-grafana", "obs 프로필 · 선택 기동", "수집 중", "running"),
    load: view("load-1", "read-load.py · 호스트", "외부 조회 부하", s.readVu > 0 ? `${s.readVu} VU` : "대기", s.readVu > 0 ? "running" : "stopped"),
  };
}

/**
 * Auto Scaling 그룹 상자의 머리. 노드가 아니라 app-3 자리를 감싸는 그룹이라 따로 돌려준다. 범위(min·max)와
 * 대수는 앱 계층 전체(app-1·app-2 고정과 app-3)로 센다. 그룹이 움직이는 것은 app-3 한 대다
 */
export interface AsgView {
  /** 앱 계층 최소·최대(2~3대) */
  min: number;
  max: number;
  desired: number;
  /** 조회 업스트림에 든 앱 노드 수 */
  registered: number;
  /** 목표와 등록이 다르거나 노드가 기동·드레이닝 중이면 전환 중이다 */
  state: "running" | "transition";
  /** 머리 한 줄. 짧은 명사로 둔다 */
  status: string;
}

export function asgView(s: FlowState): AsgView {
  const registered = APP_IDS.filter((a) => s.apps[a].inRead).length;
  // 그룹이 움직이는 것은 app-3 뿐이다. 매칭 리더가 멈춰 등록이 줄어든 것은 그룹의 조정이 아니라 그 카드의 장애다
  const moving = READ_IDS.some((a) => s.apps[a].status === "booting" || s.apps[a].status === "draining");
  const transition = moving || s.apps.app3.inRead !== (s.desired === 3);
  return {
    min: s.live?.min ?? 2,
    max: s.live?.max ?? 3,
    desired: s.desired,
    registered,
    state: transition ? "transition" : "running",
    status: transition ? `앱 ${registered} → ${s.desired}대` : `앱 ${registered}대`,
  };
}

/* ===== 관제 값 모드 ===== */

const ENGINE_OF: Record<string, EngineId> = { "app-1": "api1", "app-2": "api2" };
const READ_OF: Record<string, ReadId> = { "app-3": "app3" };

/** 관제 기록 가운데 흐름 차트의 사건 띠에 올리는 종류와 그 형태 색. 판단·확인·알림 같은 기록은 띠를 어지럽혀 뺀다 */
export const LIVE_EVENT_HEALTH: Partial<Record<IncidentEvent["kind"], Health>> = {
  OPENED: "warning",
  APPROVED: "success",
  ACTED: "success",
  AUTO_ACTED: "success",
  AUTO_REVERTED: "warning",
  REVERTED: "warning",
  RESOLVED: "success",
};

/** 관제 기록 한 줄을 사건 표식 글로 줄인다. 표식은 한 줄이라 앞 두 토막만 싣는다 */
export function liveEventLabel(text: string): string {
  return text.split(" · ").slice(0, 2).join(" · ");
}

/** 역할 하나의 호스트 CPU. 수집이 끊겼으면 잴 값이 없어 null이다 */
function hostCpuOf(hosts: OpsHost[], role: OpsHost["role"]): number | null {
  const h = hosts.find((x) => x.role === role);
  return h && h.collect !== "MISSING" ? h.cpuPct : null;
}

/**
 * 관제 시뮬레이터의 지표 관제 기록 한 박자를 표본으로 옮긴다. flowFromCenter가 판에서 만드는 표본과 같은 값이다. 리스는 관제 판이
 * 재지 않아 틱에서 정한다
 */
export function sampleFromTape(e: OpsFlowTapeEntry, tick: number): FlowSample {
  return {
    at: tick,
    trades: e.tradesPerSec,
    botOps: e.botOps,
    lease: LEASE_TTL - (tick % LEASE_TICK),
    cpu: { api1: e.cpu["app-1"], api2: e.cpu["app-2"], app3: e.cpu["app-3"] },
    util: e.avgCpuPct === null ? 0 : e.avgCpuPct / 100,
    desired: e.desired >= 3 ? 3 : 2,
    registered: e.serving,
    replLagKb: e.lagBytes === null ? null : e.lagBytes / 1024,
  };
}

/**
 * 관제 판을 흐름 상태와 표본으로 옮긴다. 노드 상태, 앱 계층(app-3), 임계, 매칭 리더, 봇 발행률, DB 복제가 모두 그 판의
 * 값이라 관제센터와 같은 숫자를 그린다. 판에 필요한 칸이 없으면(원천 연결 전) null 이고 화면은 재생 세계를 그린다.
 * 리스는 관제 판이 재지 않아 두 파티션 모두 2초마다 갱신하는 모양으로 둔다. tick 은 관제 값 주행의 틱이다
 */
export function flowFromCenter(c: ControlCenter, tick: number, events: FlowEvent[]): { state: FlowState; sample: FlowSample } | null {
  if (c.origin !== "SIM") return null;
  if (c.hosts.state !== "READY" || c.readTier.state !== "READY" || c.replication.state !== "READY") return null;
  if (c.thresholds.state !== "READY" || c.trading.state !== "READY") return null;
  const hosts = c.hosts.data;
  const tier = c.readTier.data;
  const repl = c.replication.data;
  const trading = c.trading.data;
  const th = (key: string, fallback: number) => (c.thresholds.state === "READY" ? c.thresholds.data.items.find((t) => t.key === key)?.value ?? fallback : fallback) / 100;

  const base = createFlow();
  const s: FlowState = { ...base, tick, events };
  const cpu = {} as Record<AppId, number | null>;
  for (const id of ENGINE_IDS) {
    const h = hosts.find((x) => ENGINE_OF[x.id] === id);
    s.apps[id] = { status: !h || h.collect === "MISSING" ? "killed" : "running", inRead: !!h && h.collect !== "MISSING" };
    cpu[id] = h && h.collect !== "MISSING" ? h.cpuPct : null;
  }
  for (const n of tier.nodes) {
    const id = READ_OF[n.id];
    if (!id) continue;
    s.apps[id] = {
      status: n.state === "IN_SERVICE" ? "running" : n.state === "PENDING" ? "booting" : n.state === "DRAINING" ? "draining" : "stopped",
      inRead: n.state === "IN_SERVICE",
    };
    cpu[id] = n.state === "OFF" ? null : n.cpuPct;
  }
  const partitions: FlowPartition[] = trading.leaders
    .filter((l) => ENGINE_OF[l.node] !== undefined)
    .map((l) => ({ id: l.partition, holder: ENGINE_OF[l.node], epoch: l.epoch }));
  // 조회를 받는 앱 노드 수. 관제 판의 앱 계층 집계(readTier.serving)와 같은 값이다
  const serving = tier.serving;
  const util = tier.avgCpuPct === null ? 0 : tier.avgCpuPct / 100;
  s.holder = null;
  s.epoch = Math.max(START_EPOCH, ...partitions.map((p) => p.epoch));
  // 리스는 2초마다 갱신된다. 잔여는 갱신 직후 TTL 에서 한 틱마다 1씩 준다
  s.lease = LEASE_TTL - (tick % LEASE_TICK);
  s.desired = tier.desired >= 3 ? 3 : 2;
  s.replicating = repl.stream !== "STOPPED";
  s.botOps = trading.botOps;
  s.opsEff = trading.botOps;
  s.util = util;
  s.utilTarget = util;
  s.adviceOut = util >= th("read.scale_out_cpu", 60);
  for (const id of APP_IDS) s.cpuEff[id] = cpu[id] ?? 0;
  s.live = {
    partitions,
    scaleOut: th("read.scale_out_cpu", 60),
    scaleIn: th("read.scale_in_cpu", 30),
    min: tier.min,
    max: tier.max,
    botTarget: trading.botTargetOps,
    readRate: Math.round((tier.avgCpuPct ?? 0) * serving * 1.2),
    hostCpu: {
      pg1: hostCpuOf(hosts, "DB_PRIMARY"),
      pg2: hostCpuOf(hosts, "DB_STANDBY"),
      bot: hostCpuOf(hosts, "BOT_OBS"),
    },
  };
  const sample: FlowSample = {
    at: tick,
    trades: trading.tradesPerSec,
    botOps: trading.botOps,
    lease: s.lease,
    cpu,
    util,
    desired: s.desired,
    registered: serving,
    replLagKb: repl.lagBytes === null || repl.stream === "STOPPED" ? null : repl.lagBytes / 1024,
  };
  return { state: s, sample };
}

/* ===== 연결선의 트래픽 ===== */

/** 구성도 연결선. 방향은 요청이나 데이터가 흐르는 쪽이다(수집선은 지표가 관측으로 간다) */
export type EdgeId =
  | "users-cdn"
  | "cdn-alb"
  | "bot-alb"
  | "load-alb"
  | "alb-api1"
  | "alb-api2"
  | "alb-read"
  | "app-db"
  | "read-db"
  | "repl"
  | "scrape-app"
  | "scrape-db";

export const EDGE_IDS: EdgeId[] = [
  "users-cdn", "cdn-alb", "bot-alb", "load-alb", "alb-api1", "alb-api2", "alb-read",
  "app-db", "read-db", "repl", "scrape-app", "scrape-db",
];

/**
 * 선의 상태. 사용자가 정한 규칙이다(2026-09-23): 트래픽이 흐르는 선은 초록, 끊긴 선은 빨강과
 * 끊김 표시. 건강하지만 지금 흐르는 것이 없으면 idle, 없거나 꺼진 쪽이면 off, 기동·드레이닝처럼
 * 넘어가는 중이면 transition 이다
 */
export type EdgeKind = "flow" | "idle" | "off" | "down" | "transition";
/** 흐름 세기 단계. 0 은 흐름 없음. 화면은 단계가 바뀔 때만 애니메이션을 고친다 */
export type EdgeTier = 0 | 1 | 2 | 3;

export interface EdgeFlow {
  kind: EdgeKind;
  tier: EdgeTier;
  /** 대략의 초당 건수. 단계를 가르는 입력이며 지어낸 값이다 */
  rate: number;
}

/** 단계 경계(초당 건수). 기본 봇 10 OPS 는 1단계, 급증 300 OPS 와 조회 20 VU 는 3단계에 든다 */
export const EDGE_TIER_STEPS = [20, 150] as const;
/**
 * 조회 작업자 하나가 내는 초당 요청. 같은 스택 20 VU 에서 초당 401건을 쟀다(read-load.py:13).
 * 그 값을 20 으로 나눈 근삿값이다
 */
const READ_PER_VU = 20;
/** 웹 사용자의 배경 요청. 시뮬레이션에 따로 없는 몫이라 약하게만 흐르게 둔다 */
const USER_RATE = 5;

export function edgeTier(rate: number): EdgeTier {
  if (rate <= 0) return 0;
  if (rate < EDGE_TIER_STEPS[0]) return 1;
  if (rate < EDGE_TIER_STEPS[1]) return 2;
  return 3;
}

const flow = (rate: number): EdgeFlow => (rate > 0 ? { kind: "flow", tier: edgeTier(rate), rate } : { kind: "idle", tier: 0, rate: 0 });
const fixed = (kind: Exclude<EdgeKind, "flow">): EdgeFlow => ({ kind, tier: 0, rate: 0 });

/**
 * 연결선마다 지금 무엇이 얼마나 흐르는지. 구성도는 이 값만 보고 선의 색과 흐름 세기를 정한다.
 * 쓰기는 매칭 노드 가운데 리스 보유자에게만 간다. 조회는 조회 업스트림에 든 앱 노드(app-1·app-2·켜진 app-3)에
 * 고루 나뉜다. 그래서 app-1·app-2 선은 쓰기와 조회 몫을 함께 나르고, 그룹으로 가는 조회선은 app-3 몫만 나른다
 */
export function edgeFlows(s: FlowState): Record<EdgeId, EdgeFlow> {
  const w = writable(s);
  const reachable = dbReachable(s);
  const writeRate = w ? s.opsEff : 0;
  const readRate = s.live !== null ? s.live.readRate : s.readVu * READ_PER_VU;
  const inRead = (id: AppId) => s.apps[id].inRead && s.apps[id].status === "running";
  const serving = APP_IDS.filter(inRead).length;
  /** 한 노드가 받는 조회 몫. least_conn 이라 고루 나뉜다고 본다 */
  const readShare = (id: AppId) => (inRead(id) && serving > 0 ? (readRate + USER_RATE) / serving : 0);
  // 관제 값 모드는 쓰기를 마켓 파티션 수만큼 나눠 각 리더로 보낸다. 재생은 단일 리더가 모두 받는다
  const share = (id: EngineId) =>
    s.live !== null ? (s.live.partitions.length === 0 ? 0 : partitionsOf(s, id).length / s.live.partitions.length) : s.holder === id ? 1 : 0;

  const albTo = (id: EngineId): EdgeFlow => {
    const st = s.apps[id].status;
    if (st === "killed") return fixed("down");
    if (st === "stopped") return fixed("off");
    if (st === "booting" || st === "draining") return fixed("transition");
    if (!reachable) return fixed("down");
    return flow(writeRate * share(id) + readShare(id));
  };
  // app-3 이 꺼져 있으면 그룹으로 가는 조회선도 꺼짐이고, 켜지고 내리는 동안은 전환 중이다
  const app3 = s.apps.app3.status;
  const albRead: EdgeFlow =
    app3 === "stopped"
      ? fixed("off")
      : app3 === "killed" || !reachable
        ? fixed("down")
        : app3 === "booting" || app3 === "draining"
          ? fixed("transition")
          : flow(readShare("app3"));
  // 조회 SQL 은 캐시에 걸리지 않은 몫만 DB 에 닿는다. 값은 지어낸 비율이다
  const sqlOf = (rate: number) => rate * 0.3;
  const engineRead = readShare("api1") + readShare("api2");

  const primary = primaryDb(s);
  const standbyState = primary === null ? null : s.db[otherDb(primary)];
  const repl: EdgeFlow =
    s.replicating && primary !== null
      ? // WAL 은 쓰기가 있을 때 쌓인다. 쓰기가 없어도 연결은 살아 있어 idle 로 둔다
        flow(w ? tradesFor(s.opsEff) * 4 : 0)
      : standbyState === "rejoining"
        ? fixed("transition")
        : fixed("down");

  return {
    // 목표 구성대로 사용자 요청은 CloudFront 를 지나 ALB 로 간다(사용자 결정). Docker 대역에서는 실제로 ALB 에 바로 붙는다
    "users-cdn": flow(USER_RATE),
    "cdn-alb": flow(USER_RATE),
    "bot-alb": flow(s.opsEff),
    "load-alb": s.readVu > 0 ? flow(readRate) : fixed("off"),
    "alb-api1": albTo("api1"),
    "alb-api2": albTo("api2"),
    "alb-read": albRead,
    // 매칭 칸의 SQL 은 리더의 쓰기와 두 대가 받은 조회 몫이다. 둘 다 없어도 연결이 살아 있으면 유휴다
    "app-db": reachable ? flow(writeRate + sqlOf(engineRead)) : fixed("down"),
    // 그룹의 SQL 은 app-3 조회 몫이다. app-3 이 꺼져 있으면 선도 꺼짐이다
    "read-db": albRead.kind === "flow" ? flow(sqlOf(readShare("app3"))) : fixed(albRead.kind),
    repl,
    // 관측 수집은 일정한 약한 흐름이다. 끌어올 DB 가 없으면 끊긴다
    "scrape-app": flow(1),
    "scrape-db": primary !== null ? flow(1) : fixed("down"),
  };
}

/* ===== 재생 설명(대화상자) ===== */

export type Risk = "낮음" | "중간" | "높음";

export interface ScenarioView {
  title: string;
  /** 대상 한 줄. AWS 이름, 컨테이너 이름, 지금 역할 */
  target: string;
  risk: Risk;
  tone: "warning" | "danger";
  impacts: string[];
  observe: string[];
  /** 측정 출처와 그 기록의 수치 */
  evidenceSource: string;
  evidence: string[];
  /** 같은 일을 실제로 일으키는 호스트 명령이나 콘솔 경로 */
  host: string[];
  /** 재생 길이(초) */
  length: number;
}

export function describeScenario(s: FlowState, id: ScenarioId, target?: TargetId): ScenarioView {
  const holder = s.holder ?? "api1";
  const standby = otherEngine(holder);
  const length = SCENARIOS[id].length;
  const app = (a: AppId) => `${APP_NAME[a]} (${APP_CONTAINER[a]})`;
  switch (id) {
    case "traffic":
      return {
        title: "봇 발행률 급증",
        target: "bot-1 시장 봇 (chaos-bots) · 쓰기 경로",
        risk: "낮음",
        tone: "warning",
        impacts: [
          `봇 목표 ${BASE_OPS} → ${SURGE_OPS} OPS, 40초 유지`,
          "주문은 쓰기 경로라 조회 사용률은 거의 불변 · 리더 CPU 만 오름",
          "app-3 켜기 권고 없음",
        ],
        observe: ["체결/초", "앱 노드 CPU", "조회 사용률"],
        evidenceSource: "docs/reference/contracts/하네스_검증설계.md:495",
        evidence: ["봇 300 OPS 에서 체결 초당 18.1건", "조회 평균 사용률 12% 미만 · 증설 권고 없음"],
        host: ["봇 플릿 화면 발행률 300 · PUT /admin/v1/sim/rate"],
        length,
      };
    case "scale":
      return {
        title: "조회 부하와 app-3 켜기",
        target: `${app("app3")} · 조회 전용 · 중지 상태`,
        risk: "낮음",
        tone: "warning",
        impacts: [
          "load-1 조회 부하 20 VU 로 사용률 권고 임계 25% 초과",
          "운영자 승인 뒤 조정기가 app-3 기동과 ALB 등록 · 조회가 세 노드로 나뉨",
          "부하 유지 중 app-3 끄기, 드레이닝 10초 뒤 중지 · 앱 계층 2대로 복귀",
        ],
        observe: ["조회 사용률", "앱 계층 목표·등록", "앱 노드 CPU"],
        evidenceSource:
          "docs/reference/contracts/하네스_검증설계.md:493 (09-23 종단 리허설), :497 (09-23 3노드 실측)",
        evidence: [
          "리허설 · 평균 사용률 58% 에서 증설 권고",
          "리허설 · 희망 대수 상향부터 관측 3대까지 24.0초",
          "리허설 · 합류 직후 74%, 다음 표본 49%",
          "리허설 · 축소 요청부터 관측 2대까지 18.3초 · 드레이닝 10초 포함",
          "리허설 · 2대 복귀 뒤 평균 27%",
          "3노드 실측 · 컨테이너 기동부터 LB 등록까지 10.9초",
        ],
        host: [
          "python harness/scripts/read-load.py --vus 20 --sec 300",
          "docker compose --env-file harness/.env.chaos --profile scale create chaos-api-3",
          "python harness/asg/capacity_reconciler.py --stack chaos --lb-conf harness/nginx/nginx.conf --drain 10 --log <로그경로>",
          "용량·부하 화면 app-3 켜기 · POST /admin/v1/capacity/scale-out",
          "용량·부하 화면 app-3 끄기 · POST /admin/v1/capacity/scale-in",
        ],
        length,
      };
    case "leaderKill":
      return {
        title: "매칭 리더 중단",
        target: `${app(holder)} · 매칭 리더 · epoch ${s.epoch}`,
        risk: "높음",
        tone: "danger",
        impacts: [
          `${APP_NAME[holder]} SIGKILL · exit 137`,
          `리스 만료(TTL ${LEASE_TTL}초)와 승격까지 쓰기 중단 · 실측 7.9초`,
          `${APP_NAME[standby]} 승격 · epoch ${s.epoch + 1}`,
          `${APP_NAME[holder]} 는 재기동 전까지 중단 유지 · docker kill 은 restart 정책 무시`,
        ],
        observe: ["체결/초", "리더 리스 잔여", "앱 노드 CPU"],
        evidenceSource:
          "harness/evidence/adr0022-stage9-chaos-20260915 (E4), harness/evidence/chaos-runs/E4-*.json (09-13~09-23, 8회)",
        evidence: [
          "stage9 · kill 뒤 0.5초 컨테이너 종료 · exit 137",
          "stage9 · 7.9초 승격 · epoch 1 → 2 · 첫 체결 7.925초",
          "stage9 · 유실 0 · 중복 0",
          "chaos-runs E4 8회 · RTO 5.9~7.9초",
        ],
        host: ["pwsh -File harness/scripts/e4-leader-kill.ps1"],
        length,
      };
    case "restart": {
      const t: EngineId = isEngine(target) ? target : "api1";
      return {
        title: "중단 노드 재기동",
        target: `${app(t)} · 중단`,
        risk: "낮음",
        tone: "warning",
        impacts: [`${APP_NAME[t]} 기동 뒤 리더 대기로 합류`, "리더는 바뀌지 않음"],
        observe: ["앱 노드 CPU"],
        evidenceSource: "harness/evidence/chaos-runs/COLD-20260915-132439.json",
        evidence: ["두 노드 냉기동 · 리더 부팅 7.7초, 팔로워 13.0초", "kill 뒤 한 대 재기동은 미측정 · 팔로워 값 사용"],
        host: [`docker start ${APP_CONTAINER[t]}`],
        length,
      };
    }
    case "dbSwitch": {
      const p = primaryDb(s) ?? "pg1";
      const q = otherDb(p);
      return {
        title: "Primary 중단과 수동 승격",
        target: `${DB_NAME[p]} (${DB_CONTAINER[p]}) · Primary`,
        risk: "높음",
        tone: "danger",
        impacts: [
          `${DB_NAME[p]} 중지 · 쓰기 즉시 중단`,
          "리스를 DB 에서 갱신하지 못해 리스 만료 뒤 쓰기 리더 없음",
          `운영자 판단 뒤 ${DB_NAME[q]} pg_promote · 자동 페일오버 없음`,
          `앱 DB 접속을 ${DB_NAME[q]} 로 전환 · AWS 는 switch-db-endpoint.py`,
          "리더가 리스를 다시 잡으면 체결 재개",
          "비동기 복제라 마지막 몇 건 유실 가능",
        ],
        observe: ["체결/초", "리더 리스 잔여", "DB 복제 지연"],
        evidenceSource: "harness/evidence/failover-rpo-20260912/README.md (로컬 Docker Primary·Standby)",
        evidence: [
          "승격 명령 1.697초 · 1.886초 · 사람의 감지와 판단 제외",
          "정상 복제 중 급사 유실 0/88 · Standby 8초 지연 뒤 36/122",
          "운영자 판단 15초 · 접속 전환 2초 · 리스 재획득 2초는 가정",
          "Standby 컨테이너는 2단계에서 추가 · standby_up.sh 는 동기 복제라 AWS 처럼 비동기로 바꿔 쓸 예정",
        ],
        host: [
          `docker stop ${DB_CONTAINER[p]}`,
          `docker exec ${DB_CONTAINER[q]} psql -U exchange -d exchange -c "SELECT pg_promote(true,60)"`,
          `앱 DB 접속 대상 → ${DB_CONTAINER[q]}:5432 · Docker 는 2단계에서 스크립트화`,
        ],
        length,
      };
    }
    case "rejoin": {
      const t: DbId = isDb(target) ? target : "pg1";
      return {
        title: "옛 Primary 재편입",
        target: `${DB_NAME[t]} (${DB_CONTAINER[t]}) · 중지`,
        risk: "중간",
        tone: "warning",
        impacts: [
          "옛 데이터는 버리고 새 Primary 에서 전량 베이스백업",
          "끝나면 Standby 로 합류해 스트리밍 복제 재개",
          `재생은 ${REJOIN_SPEED}배속`,
        ],
        observe: ["DB 복제 지연"],
        evidenceSource: "harness/evidence/failover-rpo-20260912/README.md",
        evidence: ["pg_rewind 실패", "전량 베이스백업 264.717초 · 스트리밍 재개까지 272.549초"],
        host: ["pg_basebackup -h <새 Primary> -D <데이터 경로> -R -X stream -C -S standby_1 -c fast · 2단계에서 스크립트화"],
        length,
      };
    }
  }
}

/** 재생 전체 길이. 헤더의 진행 표시가 쓴다 */
export function scenarioLength(id: ScenarioId): number {
  return SCENARIOS[id].length;
}
