/**
 * 시연 대본. 네 가지 상황을 단계로 펴고 각 단계의 충족을 라이브 값에서만 읽는다.
 *
 * 왜 대본을 데이터로 두는가. 종전 카오스 카탈로그는 아홉 건의 주입 버튼이었고 화면이
 * 그 버튼만 그렸다. 누르면 무슨 일이 일어나는지, 무엇을 보고 성공이라 할지는 화면 밖
 * 문서에 있었다. 시연 중에 그 둘을 사람이 머리로 이어야 했고 그래서 매번 대본을 다시 읽었다.
 * 여기서는 단계마다 "무엇을 하고, 무엇을 보고, 무엇이면 넘어가는가"를 한 줄로 묶는다.
 *
 * 세 가지를 지킨다.
 *
 * 1. 충족 판정은 라이브 값에서만 낸다. 버튼을 눌렀다는 사실로 통과를 만들지 않는다.
 *    조작이 접수돼도 실체가 따라오지 않은 구간이 있고, 그 구간을 통과로 칠하면
 *    시연이 일어나지 않은 일을 일어났다고 말한다.
 * 2. 콘솔이 못 하는 일은 명령으로 적는다. 리더를 죽이거나 DB를 승격하는 일은 관리 API에
 *    없다. 버튼 모양으로 그려 두면 누를 수 있다고 오해한다. 명령 문자열로 적고
 *    결과만 라이브로 감지한다.
 * 3. 원천이 없는 축은 원천 없음으로 적는다. 관리 API가 호스트 지표를 내지 않아
 *    디스크·메모리 압박은 판정할 수 없다. 그것을 통과나 미달로 적지 않는다.
 */

import type { GrafanaBoard } from "./boards";
import type { CapacityState, HaRecovery, HaStatus, SimStatus } from "./types";

/** 단계 판정에 쓰는 라이브 값 묶음. 읽지 못한 축은 null이고 0으로 채우지 않는다 */
export interface DrillSignals {
  cap: CapacityState | null;
  sim: SimStatus | null;
  ha: HaStatus | null;
  rec: HaRecovery | null;
}

/**
 * 단계 상태.
 *
 * MET       조건을 지금 라이브 값에서 확인했다
 * PENDING   원천은 읽었고 아직 조건에 닿지 않았다
 * MISSING   원천은 있는데 이번에 못 읽었다. 다시 읽으면 값이 온다
 * BLOCKED   앞 단계가 아직 안 끝났다. 이 단계의 조건은 묻지 않는다
 * NO_SOURCE 이 환경에 수집기가 없다. 다시 읽어도 오지 않는다
 *
 * BLOCKED 가 왜 필요한가. 단계마다 조건을 독립으로 물으면 뒤 단계가 먼저 충족으로 뜬다.
 * 부하를 넣기 전에는 사용률이 낮으므로 "사용률 하락"이 처음부터 참이고, 대수를 늘리기
 * 전에는 "축소 완료"도 참이다. 둘 다 사실이지만 시연의 진행은 아니다. 앞 단계가 끝나기
 * 전에는 뒤 단계를 판정하지 않는다고 적는 쪽이 정직하다.
 */
export type DrillState = "MET" | "PENDING" | "MISSING" | "BLOCKED" | "NO_SOURCE";

export interface DrillCheck {
  state: DrillState;
  /** 판정의 근거를 값으로 적는다. 문장이 아니라 읽은 수치다 */
  readout: string;
}

/** 콘솔이 직접 실행할 수 있는 조작. 관리 API에 있는 것만 둔다 */
export type DrillAct =
  | { kind: "OPS"; rate: number; label: string }
  | { kind: "SCALE_OUT"; label: string }
  | { kind: "SCALE_IN"; label: string };

/**
 * 사전 조건 한 줄. 명령이 붙으면 화면이 복사 칩으로 낸다.
 *
 * 조건만 적고 명령을 문서 밖에 두면 시연자가 화면과 문서를 번갈아 보게 된다. 정작
 * 그 두 명령을 빠뜨리면 첫 단계부터 막히는데, 막힌 이유가 화면에 없다.
 */
export interface DrillPrep {
  text: string;
  /** 호스트에서 칠 명령. 콘솔은 실행하지 않는다 */
  command?: string;
}

/** 앞 단계가 지날 때 읽은 값. 단계 id에서. 뒤 단계가 세대나 사용률을 이 값과 대조한다 */
export type DrillSeen = Record<string, DrillSignals | undefined>;

export interface DrillStep {
  id: string;
  /** 명사형 종결. 이 단계에서 무엇이 성립해야 하는가 */
  title: string;
  /** 왜 이 단계가 있는가. 한두 문장 */
  detail: string;
  /** 콘솔 조작. 없으면 사람이 다른 자리에서 한다 */
  act?: DrillAct;
  /**
   * 호스트에서 쳐야 하는 명령. 콘솔은 실행하지 않고 결과만 감지한다.
   * 대상이 관측에 달린 명령은 함수로 두고, 앞 단계가 대상을 보지 못했으면 null을 낸다.
   */
  command?: string | ((seen: DrillSeen) => string | null);
  /** 추이를 볼 자리 */
  board?: GrafanaBoard;
  check(s: DrillSignals, seen: DrillSeen): DrillCheck;
}

/** 단계를 지난 시각과 그때 읽은 값 */
export interface DrillRecord {
  at: string;
  seen: DrillSignals;
}

/** 주행 한 번. since 는 주행을 켠 시각이고 이보다 앞선 관측과 기록은 이 주행의 증거가 아니다 */
export interface DrillRun {
  since: string;
  steps: Record<string, DrillRecord>;
}

/** 이 대본이 실제로 읽는 원천. 화면이 필요 없는 왕복을 내지 않게 한다 */
export type DrillSignalKey = "cap" | "sim" | "ha" | "rec";

export interface DrillDef {
  id: string;
  title: string;
  /** 한 줄 요지. 목록에서 이것만 읽고 고른다 */
  lead: string;
  /** 이 시연이 증명하려는 것 */
  claim: string;
  /** 사전 조건. 없으면 첫 단계부터 막힌다 */
  prep?: DrillPrep[];
  /**
   * 읽을 원천. 선언과 실제 사용이 어긋나면 화면이 값을 못 읽고 영구 미수집이 된다.
   * tests/drills.test.ts 가 선언한 것만 준 판정과 전부 준 판정을 대조해 어긋남을 잡는다.
   */
  needs: DrillSignalKey[];
  steps: DrillStep[];
}

/* ===== 판정 보조 ===== */

const missing = (what: string): DrillCheck => ({ state: "MISSING", readout: `${what} 미수집` });

function capCheck(s: DrillSignals, fn: (c: CapacityState) => DrillCheck): DrillCheck {
  return s.cap ? fn(s.cap) : missing("용량");
}

function simCheck(s: DrillSignals, fn: (c: SimStatus) => DrillCheck): DrillCheck {
  return s.sim ? fn(s.sim) : missing("봇 상태");
}

const met = (readout: string): DrillCheck => ({ state: "MET", readout });
const pending = (readout: string): DrillCheck => ({ state: "PENDING", readout });

/** 사용률을 퍼센트 문자열로. 못 읽은 값은 문자열로도 0이 되지 않는다 */
function pct(ratio: number | null): string {
  return ratio === null ? "미수집" : `${Math.round(ratio * 100)}%`;
}

/**
 * 그 표본에서 리더로 응답한 노드. 한 대가 아니면 null이다.
 * 리스 보유자는 프로세스마다 새로 받는 식별자라 노드 이름은 용량 관측에서 읽는다.
 */
function leaderOf(s: DrillSignals | undefined): string | null {
  const leaders = s?.cap?.nodes.filter((n) => n.reachable && n.leader) ?? [];
  return leaders.length === 1 ? leaders[0].id : null;
}

/* ===== 1. 트래픽 임계와 조회 증설 ===== */

/**
 * 부하 입력이 k6가 아니라 봇 발행률인 이유. 이 프로젝트의 상시 트래픽 원천이 시장 봇이고,
 * 봇 OPS는 관리 API로 바꿀 수 있어 시연자가 화면 안에서 부하를 만들 수 있다. k6는 별도
 * 프로세스를 띄워야 해서 시연 동선이 콘솔 밖으로 나간다.
 */
const SCALE_OPS = 300;
/** 서버 advice가 임계를 소유한다. 화면이 0.60을 복제하면 서버가 값을 조일 때 갈린다 */
const adviceSaysOut = (c: CapacityState) => c.advice.some((a) => a.action === "SCALE_OUT");

/**
 * 그 표본에서 조회를 받고 사용률이 읽힌 노드. 서버가 조회 평균에 넣는 노드와 같은 조건이다.
 * 평균만 보면 몇 대가 나눠 받은 값인지 알 수 없어서 분산 판정은 이 목록을 함께 대조한다.
 */
function readers(c: CapacityState): string[] {
  return c.nodes.filter((n) => n.reachable && n.servesReads && n.cpuRatio !== null).map((n) => n.id);
}

const DRILL_SCALE: DrillDef = {
  id: "scale",
  title: "트래픽 임계와 조회 증설",
  lead: "봇 발행률로 앱 CPU를 밀어 조회 노드 한 대 증설",
  claim: "조회는 대수를 늘려 용량이 는다. 주문은 늘지 않는다. 그 차이를 같은 화면에서 본다.",
  needs: ["cap", "sim"],
  prep: [
    {
      text: "증설 노드 생성. 한 번 만들어 두면 조절기가 그 컨테이너를 켜고 끄는 대상으로 삼음",
      command: "docker compose --env-file harness/.env.chaos --profile scale create chaos-api-3",
    },
    {
      text: "조절기 기동. 콘솔은 희망 대수만 변경, 컨테이너 기동은 조절기 소관",
      command:
        "python harness/asg/capacity_reconciler.py --stack chaos --lb-conf harness/nginx/nginx.conf --log recon.jsonl",
    },
  ],
  steps: [
    {
      id: "baseline",
      title: "기준 대수 확인",
      detail: "계약 대수와 관측 대수가 같은 상태에서 시작한다. 어긋난 채로 시작하면 다음 단계의 증가가 증설 때문인지 밀린 조정 때문인지 가릴 수 없다.",
      check: (s) =>
        capCheck(s, (c) =>
          c.reconciled
            ? met(`계약 ${c.plan.desired}대 · 관측 ${c.observedTotal}대`)
            : pending(`계약 ${c.plan.desired}대 · 관측 ${c.observedTotal}대`),
        ),
    },
    {
      id: "load",
      title: "발행률 상향",
      detail: `봇 목표를 초당 ${SCALE_OPS}건으로 올린다. 주문이 늘어 주문 노드의 CPU가 오른다. 조회 계층은 이것만으로 움직이지 않는다. 봇이 내는 주문은 쓰기 경로이고 봇이 보는 호가·티커도 노드 로컬 순번이라 쓰기 경로에 남기 때문이다.`,
      act: { kind: "OPS", rate: SCALE_OPS, label: `초당 ${SCALE_OPS}건 적용` },
      board: "msp-service",
      check: (s) =>
        simCheck(s, (m) =>
          m.rate === null
            ? { state: "MISSING", readout: "봇 노드 목표 미보고" }
            : m.rate >= SCALE_OPS
              ? met(`목표 ${m.rate} · 실측 ${m.currentOps ?? "미수집"}`)
              : pending(`목표 ${m.rate} · 실측 ${m.currentOps ?? "미수집"}`),
        ),
    },
    {
      id: "readload",
      title: "조회 부하 주입",
      detail: "로드밸런서가 조회 업스트림으로 보내는 것은 DB에서 완결되는 마켓·체결·캔들뿐이다. 조회 계층을 밀려면 그 경로로 넣어야 한다. 이 스택의 천장은 20 작업자 근처이고 더 올리면 수용 제어가 먼저 걸려 처리량이 오히려 준다.",
      command: "python harness/scripts/read-load.py --vus 20 --sec 300",
      board: "msp-service",
      check: (s) =>
        capCheck(s, (c) =>
          c.readAvgCpuRatio === null
            ? missing("조회 평균 사용률")
            : c.readAvgCpuRatio > c.thresholds.scaleInCpuRatio
              ? met(`평균 ${pct(c.readAvgCpuRatio)}`)
              : pending(`평균 ${pct(c.readAvgCpuRatio)} · 유휴`),
        ),
    },
    {
      id: "breach",
      title: "임계 도달",
      detail: "조회 노드의 평균 사용률이 증설 임계에 닿아 서버가 증설을 권고한다. 임계값은 서버가 소유하고 환경마다 다르다. 로컬은 이 스택이 닿을 수 있는 값으로 내려 두었고 AWS 운영값과 구분한다.",
      board: "msp-service",
      check: (s) =>
        capCheck(s, (c) =>
          c.readAvgCpuRatio === null
            ? missing("조회 평균 사용률")
            : adviceSaysOut(c)
              ? met(`평균 ${pct(c.readAvgCpuRatio)} · 증설 권고`)
              : pending(`평균 ${pct(c.readAvgCpuRatio)} · 권고 없음`),
        ),
    },
    {
      id: "desired",
      title: "희망 대수 상향",
      detail: "콘솔은 희망 대수만 바꾼다. 컨테이너를 세우는 것은 조절기다. 둘을 갈라 두어야 조절기가 멈췄을 때 그 사실이 화면에 드러난다.",
      act: { kind: "SCALE_OUT", label: "한 대 추가" },
      check: (s) => capCheck(s, (c) => (c.plan.desired > c.plan.min ? met(`희망 ${c.plan.desired}대`) : pending(`희망 ${c.plan.desired}대`))),
    },
    {
      id: "joined",
      title: "증설 노드 등록",
      detail: "새 노드가 조회를 받기 시작하면 관측 대수가 희망 대수를 따라잡는다. 이 단계가 곧 조절기 생존의 증거다.",
      board: "msp-infra",
      check: (s) =>
        capCheck(s, (c) =>
          c.reconciled && c.observedTotal > c.plan.min
            ? met(`관측 ${c.observedTotal}대 · 계약 일치`)
            : pending(`관측 ${c.observedTotal}대 · 계약 ${c.plan.desired}대`),
        ),
    },
    {
      id: "spread",
      title: "사용률 하락",
      detail: "조회 부하가 남은 채 늘린 노드가 조회를 받기 시작하고 평균 사용률이 증설 권고가 뜬 때보다 내려가야 분산이다. 부하 주입이 끝나 내려간 사용률은 유휴이고, 조회 노드 수가 그대로인 채 내려간 사용률은 워밍이나 표본 흔들림일 수 있어 둘 다 치지 않는다. 최대 대수에서는 서버가 증설을 권고하지 않으므로 권고가 사라진 것만으로도 분산이라 하지 않는다. 부하 주입이 끝났으면 다시 넣고, 노드가 늘었는데 내려가지 않으면 로드밸런서 배선을 먼저 본다.",
      board: "msp-service",
      check: (s, seen) =>
        capCheck(s, (c) => {
          if (c.readAvgCpuRatio === null) return missing("조회 평균 사용률");
          // 권고가 처음 뜬 표본이 증설 전 기준이다. 서버는 최대 대수 미만에서만 증설을 권고한다
          const before = seen.breach?.cap ?? null;
          const base = before?.readAvgCpuRatio ?? null;
          if (before === null || base === null) return pending(`평균 ${pct(c.readAvgCpuRatio)} · 증설 전 기준 미기록`);
          const trend = `기준 ${pct(base)} → 평균 ${pct(c.readAvgCpuRatio)}`;
          // 콘솔은 요청률을 읽지 않아 부하가 증설 전과 같은지는 확인하지 못한다. 대신 읽을 수 있는
          // 두 가지를 요구한다. 부하가 남아 있는지는 조회 부하 단계와 같은 축소 임계로 본다
          if (c.readAvgCpuRatio <= c.thresholds.scaleInCpuRatio) return pending(`${trend} · 유휴`);
          // 늘린 노드가 조회를 받아야 나눠 받은 것이다. 같은 노드들의 사용률만 내려간 것은
          // 재기동 직후 워밍이 끝났거나 표본이 흔들린 것일 수 있다
          const was = readers(before).length;
          const now = readers(c).length;
          const nodes = `조회 노드 ${was}대 → ${now}대`;
          if (now <= was) return pending(`${trend} · ${nodes}`);
          // 화면에 적는 정수 퍼센트로 비교한다. 소수점 아래 흔들림을 하락으로 읽지 않는다
          return Math.round(c.readAvgCpuRatio * 100) < Math.round(base * 100)
            ? met(`${trend} · ${nodes}`)
            : pending(`${trend} · 하락 없음`);
        }),
    },
    {
      id: "restore",
      title: "부하 원복과 축소",
      detail: "발행률을 되돌리고 노드를 뺀다. 축소는 로드밸런서에서 먼저 빼고 진행 중인 요청을 흘려보낸 뒤 내린다.",
      act: { kind: "SCALE_IN", label: "한 대 축소" },
      check: (s) =>
        capCheck(s, (c) =>
          c.plan.desired === c.plan.min && c.reconciled
            ? met(`희망 ${c.plan.desired}대 · 관측 ${c.observedTotal}대`)
            : pending(`희망 ${c.plan.desired}대 · 관측 ${c.observedTotal}대`),
        ),
    },
    {
      id: "reconciler",
      title: "조절기 정지",
      detail: "축소가 끝나면 조절기를 띄운 터미널에서 Ctrl+C로 끝낸다. 조절기가 돈 채로 리더 전환을 하면 리더가 빠진 만큼 대수가 모자란다고 읽고 노드를 켠다. 죽인 노드가 되살아나거나 증설 노드가 붙어 전환 실험의 구성이 바뀌므로 두 시연을 겹치지 않는다.",
      check: () => ({ state: "NO_SOURCE", readout: "조절기 프로세스는 콘솔이 읽지 않음" }),
    },
  ],
};

/* ===== 2. 앱 리더 전환 ===== */

const DRILL_LEADER: DrillDef = {
  id: "leader",
  title: "앱 리더 전환",
  lead: "주문 노드 중단과 남은 노드의 승격 확인",
  claim: "쓰기는 한 노드만 한다. 그 노드를 잃으면 다른 노드가 선출돼 거래가 재개된다.",
  needs: ["cap", "ha", "rec"],
  prep: [
    { text: "노드 두 대 기동. 리더 후보가 하나면 승격 대상 없음" },
    { text: "조절기 정지. 돌고 있으면 리더가 빠진 대수를 채우려 노드를 켜서 전환 실험의 구성이 바뀜" },
  ],
  steps: [
    {
      id: "before",
      title: "현재 리더 확인",
      detail: "전환 전의 리더 노드와 선출 세대를 적어 둔다. 뒤 단계가 그 노드의 중단과 복귀를 이 이름으로 보고, 세대가 오르지 않으면 전환이 일어나지 않은 것으로 본다.",
      check: (s) => {
        const ha = s.ha;
        if (!ha) return missing("리더 상태");
        return capCheck(s, (c) => {
          const leaders = c.nodes.filter((n) => n.reachable && n.leader);
          return leaders.length === 1
            ? met(`리더 ${leaders[0].id} · 세대 ${ha.epoch}`)
            : pending(`리더 ${leaders.length}대 관측 · 세대 ${ha.epoch}`);
        });
      },
    },
    {
      id: "kill",
      title: "리더 중단",
      detail: "관리 API에 노드를 내리는 조작이 없다. 장애 주입은 콘솔의 일이 아니라 호스트의 일이다. 스크립트를 쓰는 이유는 복구 시간을 재서 판정 표에 남기기 때문이다. docker kill만 치면 아래 판정 단계에 읽을 행이 생기지 않는다. 앞 단계에서 적은 리더가 응답을 멈춰야 충족이고, 팔로워만 멈춘 것은 리더 중단이 아니다.",
      command: "pwsh -File harness/scripts/e4-leader-kill.ps1",
      check: (s, seen) =>
        capCheck(s, (c) => {
          const old = leaderOf(seen.before);
          if (old === null) return pending("기준 리더 미기록");
          return c.nodes.find((n) => n.id === old)?.reachable === false
            ? met(`${old} 미응답`)
            : pending(`${old} 응답 중`);
        }),
    },
    {
      id: "promote",
      title: "새 리더 선출",
      detail: "남은 노드 중 하나가 리스를 잡아 주문을 받기 시작하고 선출 세대가 오른다. 두 노드가 동시에 리더로 보이면 그것이 곧 결함이다.",
      board: "proof-recovery",
      check: (s, seen) => {
        const ha = s.ha;
        if (!ha) return missing("리더 상태");
        return capCheck(s, (c) => {
          const old = leaderOf(seen.before);
          const base = seen.before?.ha?.epoch;
          if (old === null || base === undefined) return pending("기준 세대 미기록");
          const leaders = c.nodes.filter((n) => n.reachable && n.leader);
          if (leaders.length > 1) return pending(`리더 ${leaders.length}대 동시 관측`);
          if (leaders.length === 0) return pending(`리더 없음 · 세대 ${ha.epoch}. 선출 대기`);
          if (leaders[0].id === old) return pending(`옛 리더 ${old} 그대로 · 세대 ${ha.epoch}`);
          return ha.epoch > base
            ? met(`리더 ${leaders[0].id} · 세대 ${base} → ${ha.epoch}`)
            : pending(`리더 ${leaders[0].id} · 세대 ${ha.epoch} 그대로`);
        });
      },
    },
    {
      id: "verdict",
      title: "복구 판정",
      detail: "복구 시간이 목표 안에 들었는지 서버가 판정한다. 판정은 주행을 켠 뒤 적재된 행만 센다. 앞선 실험의 행까지 세면 이번 전환을 보지 않고도 통과가 뜬다. 판정 대상이 없으면 통과가 아니라 미판정이다.",
      board: "proof-recovery",
      check: (s) => {
        if (!s.rec) return missing("복구 이력");
        if (s.rec.verdictScope !== "since") return pending("주행 창 미적용 · 전체 이력 판정");
        if (s.rec.failoverVerdict === null || s.rec.failoverVerdict === "UNKNOWN") {
          return pending(`복구 시간 ${s.rec.lastRtoMs === null ? "미측정" : `${(s.rec.lastRtoMs / 1000).toFixed(1)}초`} · 미판정`);
        }
        return s.rec.failoverVerdict === "PASS"
          ? met(`복구 시간 ${s.rec.lastRtoMs === null ? "미측정" : `${(s.rec.lastRtoMs / 1000).toFixed(1)}초`} · 통과`)
          : { state: "PENDING", readout: "페일오버 판정 실패" };
      },
    },
    {
      id: "rejoin",
      title: "옛 리더 복귀",
      detail: "내린 노드를 다시 올려도 리더로 돌아가지 않는다. 돌아가면 선출이 두 번 일어나 거래가 두 번 끊긴다. 명령의 노드 이름은 첫 단계에서 관측한 리더이고, 관측이 없으면 명령을 만들지 않는다.",
      // 로컬 스택은 용량 노드 id가 compose 컨테이너 이름과 같다(application.yml admin-chaos)
      command: (seen) => {
        const old = leaderOf(seen.before);
        return old === null ? null : `docker start ${old}`;
      },
      check: (s, seen) => {
        const ha = s.ha;
        if (!ha) return missing("리더 상태");
        return capCheck(s, (c) => {
          const old = leaderOf(seen.before);
          const promoted = seen.promote?.ha?.epoch;
          if (old === null || promoted === undefined) return pending("옛 리더 미기록");
          if (c.nodes.find((n) => n.id === old)?.reachable !== true) return pending(`${old} 미응답`);
          const now = leaderOf(s);
          if (now === old) return pending(`옛 리더 ${old} 리더 재획득`);
          if (now === null) return pending(`${old} 응답 · 리더 단독 아님`);
          return ha.epoch === promoted
            ? met(`${old} 복귀 · 리더 ${now} 유지 · 세대 ${ha.epoch}`)
            : pending(`세대 ${promoted} → ${ha.epoch} · 재선출`);
        });
      },
    },
  ],
};

/* ===== 3. DB 수동 복구 ===== */

/**
 * 앱 리더 전환과 갈라 둔다. 둘은 전환 주체가 다르다. 앱은 리스가 스스로 옮기고
 * DB는 사람이 승격시킨다. 한 화면에 묶으면 "장애가 나면 알아서 넘어간다"로 읽히는데
 * DB에서는 그것이 사실이 아니다. 비동기 복제라 승격 시점에 유실 구간이 있을 수 있다.
 *
 * 이 대본의 모든 단계가 원천 없음인 이유를 분명히 둔다. DB 실습은 별도 프로젝트
 * (failstop-ops-lab)에서 돌고 이 콘솔이 보는 스택이 아니다. 여기서 관측 대수나 리더를
 * 읽어 DB 복구의 진행으로 적으면 다른 스택의 값을 이 시연의 증거로 쓰는 것이 된다.
 * 절차와 명령은 화면에 두고 판정은 실습이 남기는 증거 파일에서 읽는다.
 */
const LAB_EVIDENCE = "artifacts/operations-20260918/db-recovery-drill.json";

const DRILL_DB: DrillDef = {
  id: "db",
  title: "DB 수동 복구",
  lead: "주 DB 상실 시 대기 DB의 수동 승격 절차",
  claim: "DB 전환은 자동이 아니다. 구 주 DB를 쓰기 불가로 만든 뒤에 새 주 DB를 연다.",
  needs: [],
  prep: [
    { text: "별도 실습 스택. DB 실습은 failstop-ops-lab 프로젝트 소관이며 이 콘솔이 보는 스택 밖" },
    { text: "서버 bootJar. 실습이 현재 빌드를 그대로 기동", command: "cd server && ./gradlew.bat :app:bootJar" },
    { text: `판정 원본. 실행 결과는 ${LAB_EVIDENCE} 에 기록` },
  ],
  steps: [
    {
      id: "build",
      title: "실습 스택 준비",
      detail: "주 DB와 대기 DB, 앱을 격리 프로젝트로 세운다. 개발 DB나 AWS DB의 주소를 받지 않으므로 잘못된 대상을 승격할 여지가 없다.",
      command: "python deploy/lab/run.py build",
      check: () => ({ state: "NO_SOURCE", readout: "별도 프로젝트. 이 콘솔이 보는 스택이 아님" }),
    },
    {
      id: "drill",
      title: "전환 주행",
      detail: "회원 가입과 실제 주문으로 체결을 만든 뒤 쓰기를 멈추고 대기 DB가 마지막 WAL까지 받은 것을 확인한다. 옛 주 DB의 정지를 확인한 다음에 승격한다. 순서를 뒤집으면 두 DB가 서로 다른 쓰기를 받고 그 뒤에는 어느 쪽이 맞는지 정할 방법이 없다.",
      command: "python deploy/lab/run.py drill",
      board: "msp-db-replication",
      check: () => ({ state: "NO_SOURCE", readout: "별도 프로젝트. 이 콘솔이 보는 스택이 아님" }),
    },
    {
      id: "status",
      title: "판정 확인",
      detail: "거래·명령·이벤트·주문·원장이 모두 있는지, 전환 전후 건수가 같은지, 응답한 주문의 멱등 키가 각각 한 번 남았는지 본다. 조건부 검사의 통과만으로 모든 복구 경로를 실행했다고 읽지 않는다.",
      command: "python deploy/lab/run.py status",
      board: "proof-integrity",
      check: () => ({ state: "NO_SOURCE", readout: `판정은 ${LAB_EVIDENCE} 에 있음` }),
    },
    {
      id: "limit",
      title: "증명 범위 확인",
      detail: "이 주행은 쓰기를 멈추고 복제를 따라잡게 한 통제된 전환이다. 갑작스러운 주 DB 소실의 유실 구간이나 여러 호스트·가용영역 장애를 증명하지 않는다. 기록된 정상화 시간에는 운영자의 장애 판단 시간이 없다.",
      check: () => ({ state: "NO_SOURCE", readout: "범위 서술. 측정값이 아님" }),
    },
    {
      id: "stop",
      title: "실습 정리",
      detail: "증거와 백업을 확인한 뒤 멈춘다. 실패하면 자원을 남겨 원인을 확인할 수 있게 하므로 자동으로 옛 주 DB를 다시 켜지 않는다.",
      command: "python deploy/lab/run.py stop",
      check: () => ({ state: "NO_SOURCE", readout: "별도 프로젝트. 이 콘솔이 보는 스택이 아님" }),
    },
  ],
};

/* ===== 4. 디스크·메모리 압박 ===== */

/**
 * 원천이 없는 시연을 목록에서 지우지 않는 이유. 지우면 "이 축은 검증하지 않았다"는 사실도
 * 함께 사라진다. 남겨 두고 왜 판정할 수 없는지 화면이 말하게 한다. 관리 API가 호스트 지표를
 * 내지 않아 디스크와 메모리는 학교 AWS를 포함한 어느 환경에서도 원천 없음이다.
 */
const DRILL_PRESSURE: DrillDef = {
  id: "pressure",
  title: "디스크·메모리 압박",
  lead: "관리 API에 호스트 지표가 없어 콘솔 판정 불가",
  claim: "이 축은 학교 AWS 호스트에서 판정한다. 콘솔과 로컬 결과를 그 판정으로 바꾸어 적지 않는다.",
  needs: ["cap"],
  prep: [{ text: "호스트 지표. 관리 API가 내지 않아 콘솔이 읽을 경로 없음" }],
  steps: [
    {
      id: "disk",
      title: "디스크 사용률",
      detail: "호스트 파일시스템 사용률과 inode 여유를 본다. 관리 API가 호스트 지표를 내지 않아 환경과 무관하게 값이 오지 않는다.",
      check: () => ({ state: "NO_SOURCE", readout: "관리 API에 호스트 지표 없음" }),
    },
    {
      id: "memory",
      title: "메모리 여유",
      detail: "컨테이너 메모리 한도 대비 사용량으로 근사할 수 있으나 관측 스택이 그 수집기를 고객 범위에서 뺐다. 근사값을 호스트 지표로 적지 않는다.",
      check: () => ({ state: "NO_SOURCE", readout: "컨테이너 지표를 호스트 지표로 대체하지 않음" }),
    },
    {
      id: "heap",
      title: "앱 힙 여유",
      detail: "이 축만 원천이 있다. 노드별 힙 사용량은 용량 화면이 액추에이터에서 직접 읽는다.",
      board: "msp-service",
      check: (s) =>
        capCheck(s, (c) => {
          const usable = c.nodes.filter((n) => n.reachable && n.heapUsedBytes !== null && n.heapMaxBytes);
          if (usable.length === 0) return missing("힙 사용량");
          const worst = Math.max(...usable.map((n) => n.heapUsedBytes! / n.heapMaxBytes!));
          return worst < 0.9
            ? met(`최대 사용 ${Math.round(worst * 100)}%`)
            : pending(`최대 사용 ${Math.round(worst * 100)}%`);
        }),
    },
  ],
};

export const DRILLS: DrillDef[] = [DRILL_SCALE, DRILL_LEADER, DRILL_DB, DRILL_PRESSURE];

export function findDrill(id: string): DrillDef | undefined {
  return DRILLS.find((d) => d.id === id);
}

/** 대본 하나의 판정. 표시용 상태와 지금 볼 단계를 함께 낸다 */
export interface DrillEvaluation {
  /** 화면에 그릴 상태. 게이트에 막힌 단계는 BLOCKED 로 덮인다 */
  checks: DrillCheck[];
  /** 게이트를 걷어낸 라이브 판정. 진단용이고 화면은 쓰지 않는다 */
  raw: DrillCheck[];
  /** 단계별 호스트 명령. 대상을 아직 관측하지 못했으면 null */
  commands: (string | null)[];
  /** 이번 주행 기록으로 지난 단계와 그 시각. 화면의 시각 도장이다 */
  metAt: Record<string, string>;
  /** 이번 관측으로 새로 지난 단계. 주행 중인 화면이 기록에 더한다 */
  fresh: Record<string, DrillRecord>;
  /** 판정 가능한 단계 수. 원천 없는 단계는 분모에서 뺀다 */
  judgeable: number;
  total: number;
  /** 지금 볼 단계 index. 남은 것이 원천 없음뿐이면 null */
  currentIndex: number | null;
  /** 1부터 세는 현재 단계 번호. 끝났으면 total */
  position: number;
}

/**
 * 순서대로 판정한다. 앞 단계를 지나야 뒤 단계를 묻는다.
 *
 * 지났다의 정의가 둘인 이유. 지금 충족했거나, 이번 주행에서 충족한 적이 있거나다.
 * 발행률을 올렸다가 되돌리면 그 단계는 다시 미충족이 되는데 그것이 사실이고, 그렇다고
 * 진행을 되감으면 축소 단계에서 대본이 첫 칸으로 돌아간다.
 *
 * 기록은 게이트를 지난 단계에만, 주행을 켠 뒤이면서 앞 단계 기록보다 늦게 요청한 관측에서만
 * 생긴다. 판정할 때도
 * 주행 시작보다 앞서거나 앞 단계 기록보다 앞선 기록은 지남으로 치지 않는다. 그 순서가
 * 깨진 기록은 뒤 단계가 먼저 참이었던 순간을 적은 것이지 시연의 진행이 아니다.
 */
export function evaluateDrill(
  def: DrillDef,
  signals: DrillSignals,
  run: DrillRun | null = null,
  /** 이 신호를 요청한 시각. 주행 시작보다 앞서면 기록하지 않는다 */
  observedAt: string | null = null,
): DrillEvaluation {
  // 새 기록에 찍을 시각. 주행 밖이거나 주행 전에 요청한 관측이면 null이다
  const stamp = run !== null && observedAt !== null && observedAt >= run.since ? observedAt : null;
  const seen: DrillSeen = {};
  const raw: DrillCheck[] = [];
  const checks: DrillCheck[] = [];
  const commands: (string | null)[] = [];
  const metAt: Record<string, string> = {};
  const fresh: Record<string, DrillRecord> = {};
  // 다음 기록이 넘어야 할 시각. 주행 시작에서 출발해 지난 단계의 기록 시각으로 오른다
  let floor = run?.since ?? "";
  let open = true;
  let currentIndex: number | null = null;
  def.steps.forEach((step, i) => {
    const c = step.check(signals, seen);
    raw.push(c);
    commands.push(typeof step.command === "function" ? step.command(seen) : step.command ?? null);
    if (c.state === "NO_SOURCE") {
      // 판정 대상이 아니라 막지도 않는다. 원천이 없는 축에 진행이 걸리면 대본이 멈춘다
      checks.push(c);
      return;
    }
    if (!open) {
      checks.push({ state: "BLOCKED", readout: c.readout });
      return;
    }
    checks.push(c);
    const kept = run?.steps[step.id];
    if (kept !== undefined && kept.at >= floor) {
      seen[step.id] = kept.seen;
      metAt[step.id] = kept.at;
      floor = kept.at;
      return;
    }
    if (c.state === "MET") {
      seen[step.id] = signals;
      // 앞 단계 기록보다 앞선 관측은 적지 않는다. 적으면 다음 판정이 그 기록을 순서 위반으로
      // 버리고 같은 단계를 다시 새 기록으로 내므로 화면이 기록을 더하는 일을 멈추지 못한다
      if (stamp !== null && stamp >= floor) {
        fresh[step.id] = { at: stamp, seen: signals };
        metAt[step.id] = stamp;
        floor = stamp;
      }
      return;
    }
    open = false;
    currentIndex = i;
  });
  const judgeable = raw.filter((c) => c.state !== "NO_SOURCE").length;
  const total = def.steps.length;
  return {
    checks,
    raw,
    commands,
    metAt,
    fresh,
    judgeable,
    total,
    currentIndex,
    position: currentIndex === null ? total : currentIndex + 1,
  };
}
