/**
 * 처리 대기 화면(/admin/back)이 세 큐의 건수를 읽는 판정. 화면 밖에 두어 sim·http 어댑터
 * 어느 쪽으로도 같은 판정을 시험한다.
 *
 * 큐마다 요구 역할이 다르다. 출금·문의는 조회 권한으로 읽지만 신고는 MEMBER_MOD가 있어야 한다.
 * 한 큐의 거절이 다른 큐의 건수까지 지우면 신고 권한이 없는 운영자는 출금·문의 대기도
 * 볼 수 없다. 그래서 큐를 따로 읽고 따로 판정한다.
 */

import { AdminError, type AdminApi } from "./api";
import { QUEUE_COUNT_CAP } from "./paging";
import type { AdminPage } from "./types";

/**
 * 큐 하나를 읽은 결과. 세어 본 만큼의 건수이고, 상한을 채웠으면 그 이상이라는 사실을 함께 든다.
 * 권한 거절과 읽기 실패는 0건과 다른 상태로 둔다. 화면이 둘을 0으로 적으면 대기가 없는 것처럼 보인다.
 */
export type QueueCount =
  | { kind: "ok"; loaded: number; hasMore: boolean }
  | { kind: "forbidden" }
  | { kind: "failed" };

export interface QueueCounts {
  transfers: QueueCount;
  inquiries: QueueCount;
  reports: QueueCount;
}

export const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";
const isForbidden = (e: unknown) => e instanceof AdminError && e.code === "admin_forbidden";

export function readQueue(r: PromiseSettledResult<AdminPage<unknown>>): QueueCount {
  if (r.status === "fulfilled") return { kind: "ok", loaded: r.value.data.length, hasMore: r.value.hasMore };
  return isForbidden(r.reason) ? { kind: "forbidden" } : { kind: "failed" };
}

/**
 * 호출을 약속 안에서 시작해 결과를 모은다.
 *
 * sim 어댑터의 큐 메서드는 async가 아니고 권한 검사를 동기로 던진다. 배열 리터럴에서 바로
 * 부르면 allSettled에 닿기 전에 예외가 나서 load 전체가 거절되고, 신고 권한 하나가 없어도
 * 화면 전체가 실패로 바뀐다. then 안에서 부르면 동기 예외도 그 큐 하나의 거절로 받는다.
 */
function settleEach<T>(calls: (() => Promise<T>)[]): Promise<PromiseSettledResult<T>[]> {
  return Promise.allSettled(calls.map((call) => Promise.resolve().then(call)));
}

type QueueApi = Pick<AdminApi, "getTransfers" | "getInquiries" | "getCommunityReports">;

/**
 * 세 큐의 대기 건수를 읽는다. 서버에 개수 API가 없어 첫 쪽 길이로 센다.
 *
 * 세션 만료는 화면 전체의 사정이라 큐 하나의 실패로 흡수하지 않고 올린다. 셋 다 권한과
 * 무관하게 실패했으면 보일 부분이 없으므로 화면 전체의 실패로 올려 재시도를 한 곳에 둔다.
 * 실패 중에 권한 거절이 하나라도 섞이면 올리지 않는다. 거절된 큐는 다시 읽어도 볼 수 없으므로
 * 화면 전체를 재시도 상태로 두지 않고 큐별 타일에 사유를 나눠 적는다.
 */
export async function loadQueueCounts(api: QueueApi): Promise<QueueCounts> {
  const opts = { limit: QUEUE_COUNT_CAP };
  const settled = await settleEach<AdminPage<unknown>>([
    () => api.getTransfers("REQUESTED", opts),
    () => api.getInquiries("OPEN", opts),
    () => api.getCommunityReports("PENDING", opts),
  ]);
  const rejected = settled.flatMap((r) => (r.status === "rejected" ? [r.reason as unknown] : []));
  const session = rejected.find(isSessionError);
  if (session !== undefined) throw session;
  if (rejected.length === settled.length && !rejected.some(isForbidden)) throw rejected[0];
  const [t, i, r] = settled;
  return { transfers: readQueue(t), inquiries: readQueue(i), reports: readQueue(r) };
}
