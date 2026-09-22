/**
 * lib/admin/catalog.ts. 정적 카탈로그(봇 시나리오 프리셋, 불변식 검사 항목).
 * 관측 값과 무관한 상수라 sim·http 구현이 공유한다(§2-2 정적 데이터 제시).
 */

import type { BotType, ScenarioName } from "./types";

/** 시나리오 프리셋 정의(§6-A v2.31 배수표 미러). 서버 SimScenario enum과 값이 같아야 한다 */
export interface ScenarioDef {
  name: ScenarioName;
  /** 한국어 평이어 1차 표기(§4-2 표시층). 원어는 name·title 채널이 전달한다 */
  label: string;
  opsMult: number;
  /** 테이커 경로 도착률 배수(§2-1 "TAKER λ 10배"). opsMult와 같다. 한쪽만 올리면 배수가
      그대로 안 나온다. TREND·NOISE도 TakerBot 프리셋이라 같은 배수를 받는다 */
  lambdaMult: number;
  scale: Record<BotType, number>;
  /** 분수 파라미터는 문자열 표기(float 금지 계약 상속) */
  targetBias: string;
  autoBalance: boolean;
  strength: string;
  /**
   * 오조작 거리를 만드는 구분. surge는 균형 제어를 끄고 방향을 직접 밀어 스스로 되돌아오지
   * 않는다. 화면에서 calm 무리와 물리적으로 갈라 놓는다(정지 버튼과 같은 규약).
   */
  intensity: "calm" | "surge";
  note: string;
}

/* spike·thundering-herd의 대수 배수가 1.0인 것은 하네스 §2-1이 두 국면을 동일 플릿·전 봇으로
   규정해서다. 크기는 OPS 상한과 TAKER 도착률 두 축이 함께 낸다(§6-A v2.31). */
export const SCENARIO_CATALOG: ScenarioDef[] = [
  {
    name: "steady", label: "평상", opsMult: 1.0, lambdaMult: 1.0,
    scale: { MARKET_MAKER: 1.0, TAKER: 1.0, TREND: 1.0, NOISE: 1.0 },
    targetBias: "0.00", autoBalance: true, strength: "0.5", intensity: "calm",
    note: "기준선 복귀. 다른 국면에서 되돌아오는 경로",
  },
  {
    name: "volatile", label: "변동 확대", opsMult: 1.5, lambdaMult: 1.5,
    scale: { MARKET_MAKER: 0.6, TAKER: 1.3, TREND: 1.5, NOISE: 1.2 },
    targetBias: "0.00", autoBalance: true, strength: "0.15", intensity: "calm",
    note: "호가를 얇게 해 같은 주문이 더 큰 이동을 만든다. 방향은 없다",
  },
  {
    name: "ramp", label: "완만한 상승", opsMult: 2.0, lambdaMult: 2.0,
    scale: { MARKET_MAKER: 1.0, TAKER: 1.4, TREND: 2.0, NOISE: 1.0 },
    targetBias: "0.35", autoBalance: true, strength: "0.3", intensity: "calm",
    note: "균형 제어가 붙어 있어 완만한 상승",
  },
  {
    name: "spike", label: "급등 폭주", opsMult: 10.0, lambdaMult: 10.0,
    scale: { MARKET_MAKER: 1.0, TAKER: 1.0, TREND: 1.0, NOISE: 1.0 },
    targetBias: "0.60", autoBalance: false, strength: "0.5", intensity: "surge",
    note: "균형 제어를 끄고 목표 방향을 직접 넣는다. 대수는 그대로",
  },
  {
    /* spike 의 방향 대칭. 부하 축(OPS·λ·대수)이 spike 와 같고 bias 부호만 반대다.
       부하와 방향은 직교하는 축이라 백프레셔 관찰은 어느 방향에서든 같은 것을 잰다.
       두는 이유는 재현이다. 거래소 사고는 상승보다 급락에서 나는데(투매·일방 호가·손절 연쇄)
       프리셋이 전부 양전이면 콘솔이 한쪽 국면만 재현할 수 있다. spike 바로 뒤에 놓아
       두 국면이 같은 부하의 앞뒷면이라는 것을 배치로 보인다 */
    name: "selloff", label: "급락 투매", opsMult: 10.0, lambdaMult: 10.0,
    scale: { MARKET_MAKER: 1.0, TAKER: 1.0, TREND: 1.0, NOISE: 1.0 },
    targetBias: "-0.60", autoBalance: false, strength: "0.5", intensity: "surge",
    note: "급등 폭주의 방향 대칭. 부하는 같고 방향만 반대",
  },
  {
    name: "thundering-herd", label: "일제 쏠림", opsMult: 12.0, lambdaMult: 12.0,
    scale: { MARKET_MAKER: 1.0, TAKER: 1.0, TREND: 1.0, NOISE: 1.0 },
    targetBias: "0.70", autoBalance: false, strength: "0.5", intensity: "surge",
    note: "전 봇이 한 방향으로 몰리는 순간 버스트",
  },
];

export function scenarioDef(name: ScenarioName): ScenarioDef {
  const found = SCENARIO_CATALOG.find((s) => s.name === name);
  if (!found) throw new Error(`알 수 없는 시나리오 프리셋: ${name}`);
  return found;
}

/**
 * label은 릴레이 셀 폭 예산 내 단문이고, 도메인 원어는 gloss로 강등해 title 채널이 전달한다.
 * name은 서버 invariant_checks.check_name과 정확히 같아야 한다. 다르면 라벨 조인이 전건
 * 폴백해 화면이 한국어 대신 C 코드를 그대로 띄운다(검증설계 §4).
 */
export const INVARIANT_DEFS: { name: string; label: string; gloss?: string }[] = [
  { name: "C01_ASSET_CONSERVATION", label: "자산 보존", gloss: "자산별 원장 합 0" },
  { name: "C02_BAL_VS_LEDGER", label: "잔고 대조", gloss: "balances 대 원장 집계" },
  { name: "C03_TXN_UNSEALED", label: "미봉인 거래", gloss: "ledger_transactions 미완결" },
  { name: "C05_CMD_GAPLESS", label: "명령 순번 연속", gloss: "command_journal gapless" },
  { name: "C06_EVT_GAPLESS", label: "이벤트 순번 연속", gloss: "event_journal gapless" },
  { name: "C07_DUP_ORDER", label: "중복 주문 없음", gloss: "client_order_id 멱등" },
  { name: "C07P_DUP_ORDER_JOURNAL", label: "저널 중복 주문 없음", gloss: "주문 생성 커맨드 유일" },
  { name: "C08_DUP_FILL", label: "중복 체결 없음", gloss: "trade_id 유일" },
  { name: "C09_TRADE_SEQ_EVENT_MATCH", label: "체결 순번 정합", gloss: "trade_id 대 event_seq" },
  { name: "C10_ORDER_FILL_AGG", label: "체결 합 정합", gloss: "SUM(volume) <= 주문 수량" },
  { name: "C11_TRADE_ORPHAN", label: "고아 체결 없음", gloss: "주문 없는 trades" },
  { name: "C12_EVENT_ORPHAN", label: "고아 이벤트 없음", gloss: "명령 없는 event_journal" },
  { name: "C13A_ORDER_MATERIALIZED", label: "주문 물질화", gloss: "저널 대 orders" },
  { name: "C13B_ORDER_JOURNALED", label: "주문 저널화", gloss: "orders 대 저널" },
  { name: "C14_LOCKED_RESERVATION", label: "잠금 잔고 정합", gloss: "locked_amount 대 미체결" },
  { name: "C15_DEFAULT_PARTITION", label: "기본 파티션 공백", gloss: "파티션 누락 적재 0" },
  { name: "C16_ACK_JOURNALED", label: "ACK 저널화", gloss: "외부 ACK 대 명령 저널" },
  { name: "C17_REPLAY_DETERMINISM", label: "재생 결정론", gloss: "recovery_runs matched" },
  { name: "C18_FILL_PRICE_MAKER", label: "체결가 정합", gloss: "체결가 = maker 지정가" },
  { name: "C19_ORDERBOOK_NONCROSS", label: "호가 비교차", gloss: "bestBid < bestAsk" },
  { name: "C20_ORDER_FEE_MATERIALIZED", label: "수수료 물질화", gloss: "체결 대 수수료 원장" },
  { name: "C21_EVENT_EMISSION_REGISTRY", label: "방출 레지스트리", gloss: "이벤트 이중 방출 차단" },
  { name: "C22_FENCING_MONOTONIC", label: "fencing 단조", gloss: "epoch 역행 커밋 0" },
  { name: "C23_COUNTER_SEQ_CONSISTENCY", label: "채번 카운터 정합", gloss: "journal_counters 대 실적재" },
  { name: "C24_POSITION_COST_QTY", label: "보유 수량 정합", gloss: "포지션 대 잔고" },
  { name: "C25_POSITION_COST_SANITY", label: "원가 건전성", gloss: "평단가 범위" },
];
