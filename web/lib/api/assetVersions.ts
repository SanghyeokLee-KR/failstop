/**
 * myAsset 잔고 판(version) 판정(API 설계 §4-C-1·§5-5 v2.58, ADR 0034 S6 설계 4-9·4-13).
 *
 * 게시 노드가 둘이면 같은 사용자의 KRW 잔고를 두 노드가 각자 읽어 보내고, 두 메시지가 브로커의 서로 다른 경로로 와서
 * 순서가 뒤집힐 수 있다. seq 는 노드마다 달라 순서를 말하지 못한다. 그래서 서버가 잔고와 같은 문장에서 읽은 판을 싣고
 * 스토어가 그것으로 판정한다. 판은 그 사용자·자산의 AVAILABLE·LOCKED 잔고 행 version 의 합이다.
 *
 * 판의 성질은 셋이다. DB 타임라인 안에서 줄지 않는다. 같으면 잔고와 잠금도 같다. 수동 DB 승격이나 복원 뒤에는 줄 수 있고
 * 같은 값이 다른 잔고를 가리킬 수도 있다. 세 번째 경우를 버리기만 하면 화면이 영영 맞지 않으므로 작은 판은 적용하지 않되
 * 계좌 재조회로 맞춘다.
 *
 * 원가 필드는 판에 들지 않는다. 원가 재구축은 balances 를 건드리지 않으므로 같은 판의 항목도 적용한다. 같은 판이면 잔고와
 * 잠금은 같은 값이라 바뀌는 것은 원가 필드뿐이다.
 */

import type { Asset } from "../types";

export interface AssetVerdict {
  /** 이 항목을 스토어에 반영한다 */
  apply: boolean;
  /** 판을 비교할 수 없거나 판이 줄었다. 계좌를 한 번 다시 읽는다 */
  refetch: boolean;
}

/**
 * 실시간 항목 하나의 판정(설계 4-13 표).
 * incoming 은 실시간 항목의 판, current 는 스토어가 그 통화로 가진 값이다(없으면 undefined).
 */
export function judgeAssetUpdate(incoming: number | undefined, current: { version?: number } | undefined): AssetVerdict {
  if (current === undefined) return { apply: true, refetch: false };
  const held = current.version;
  if (incoming === undefined) {
    // 판 없는 항목끼리는 종전 서버다. 판을 가진 값을 판 없는 항목으로 덮으면 순서를 모르는 채 되돌릴 수 있다
    return held === undefined ? { apply: true, refetch: false } : { apply: false, refetch: true };
  }
  if (held === undefined) return { apply: true, refetch: true };
  if (incoming >= held) return { apply: true, refetch: false };
  return { apply: false, refetch: true };
}

/**
 * 계좌 조회 응답을 지금 값과 합친다. 응답은 권위가 있지만 늦게 도착할 수 있다. requested 는 요청을 보낼 때 스토어가
 * 가진 통화별 판이다.
 *
 * 응답 판이 지금 값 이상이면 적용한다. 지금 값보다 작지만 요청 때 값 이상이면 요청 뒤에 더 새 실시간이 온 것이므로 그
 * 통화는 지금 값을 둔다. 요청 때 값보다도 작으면 DB 가 되돌아간 것이므로 응답을 적용한다. 판이 없는 응답(종전 서버)은
 * 종전처럼 통째로 바꾼다. 응답에 없는 통화는 요청 뒤 실시간으로 생긴 것만 남긴다.
 */
export function mergeAccounts(
  current: Asset[],
  fetched: Asset[],
  requested: ReadonlyMap<string, number | undefined>,
): Asset[] {
  if (fetched.some((a) => a.version === undefined)) return fetched;
  const now = new Map(current.map((a) => [a.currency, a]));
  const merged = fetched.map((f) => {
    const held = now.get(f.currency);
    if (held?.version === undefined) return f;
    if (f.version! >= held.version) return f;
    const asked = requested.get(f.currency);
    if (asked === undefined || f.version! >= asked) return held;
    return f;
  });
  const listed = new Set(fetched.map((a) => a.currency));
  for (const a of current) {
    if (listed.has(a.currency) || a.version === undefined) continue;
    if (!requested.has(a.currency)) merged.push(a);
  }
  return merged;
}
