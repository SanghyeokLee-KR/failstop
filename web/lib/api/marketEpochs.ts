/**
 * 마켓별 epoch 표(API 설계 §5-2 v2.57, ADR 0034 S6 설계 4-13).
 *
 * epoch 는 DB 타임라인 하나 안에서 모든 리스 행과 획득 이력을 통틀어 한 번씩만 쓰고 배정 순서대로 커진다. 그래서 같은
 * 마켓에서는 큰 epoch 가 뒤의 리더 기간이다. 파티션 리더(P3)에서는 마켓마다 게시하는 라이터가 달라 두 마켓의 메시지가
 * 서로 다른 epoch 를 싣는다. 다른 마켓끼리는 비교하지 않는다. 전역 값 하나로 비교하면 두 마켓 메시지가 번갈아 올 때마다
 * 모든 채널을 재동기화한다.
 *
 * 판정은 다섯 가지다.
 * first  처음 본 마켓. 기록하고 통과한다.
 * same   기준과 같다. 통과한다.
 * rise   기준보다 크다. 기준을 올리고 그 마켓만 재동기화한다.
 * stale  기준보다 작다. 옛 리더가 늦게 보낸 메시지라 버린다.
 * reset  작은 epoch 만 10초 넘게 이어졌고 그 사이 기준 이상의 메시지를 하나도 받지 않았다. DB 타임라인이 바뀐 것으로
 *        보고(복원이나 수동 승격) 그 epoch 를 새 기준으로 삼고 그 마켓을 재동기화한다.
 */

/** 작은 epoch 만 이어질 때 기준을 다시 잡기까지의 시간 */
export const STALE_EPOCH_RESET_MS = 10_000;

export type EpochVerdict = "first" | "same" | "rise" | "stale" | "reset";

interface Entry {
  epoch: number;
  /** 기준보다 작은 실시간이 처음 온 시각. 기준 이상의 메시지가 오면 지운다 */
  staleSince: number | null;
}

export class MarketEpochs {
  private readonly table = new Map<string, Entry>();

  constructor(private readonly clock: () => number = () => Date.now()) {}

  /** 그 마켓의 기준 epoch. 아직 본 적이 없으면 undefined 다 */
  current(market: string): number | undefined {
    return this.table.get(market)?.epoch;
  }

  /** 실시간 메시지의 epoch 를 판정한다 */
  observe(market: string, epoch: number): EpochVerdict {
    const e = this.table.get(market);
    if (!e) {
      this.table.set(market, { epoch, staleSince: null });
      return "first";
    }
    if (epoch === e.epoch) {
      e.staleSince = null;
      return "same";
    }
    if (epoch > e.epoch) {
      e.epoch = epoch;
      e.staleSince = null;
      return "rise";
    }
    const now = this.clock();
    if (e.staleSince === null) {
      e.staleSince = now;
      return "stale";
    }
    if (now - e.staleSince > STALE_EPOCH_RESET_MS) {
      e.epoch = epoch;
      e.staleSince = null;
      return "reset";
    }
    return "stale";
  }

  /**
   * REST 호가·티커 스냅샷의 epoch 를 판정한다. 작으면 버리고(stale), 크면 기준을 올린다(rise). 스냅샷은 10초 감시를
   * 시작하지 않는다. 기준을 다시 잡는 근거는 실시간 흐름이다. 같은 epoch 의 스냅샷은 서버가 아직 기준 기간을 낸다는
   * 뜻이라 진행 중인 감시를 끊는다.
   */
  snapshot(market: string, epoch: number): EpochVerdict {
    const e = this.table.get(market);
    if (!e) {
      this.table.set(market, { epoch, staleSince: null });
      return "first";
    }
    if (epoch === e.epoch) {
      e.staleSince = null;
      return "same";
    }
    if (epoch > e.epoch) {
      e.epoch = epoch;
      e.staleSince = null;
      return "rise";
    }
    return "stale";
  }
}
