"use client";

/**
 * 조치 기록 조회(관리자 설계 4-10-4). 조치 기록, 네트워크·보안의 관리 접근 기록, 변경·작업의 창 안 조치가 같은 조회를 쓴다.
 * 조치 기록은 관제 판에 싣지 않는다. 감사 기록을 가진 관리 어댑터가 읽는 조회라 화면이 들어올 때 한 번 읽고 새로고침으로 다시
 * 읽는다. sim은 관제 판의 기록이 바뀐 때(signal) 다시 읽어 창 안의 조치와 판이 어긋나지 않게 한다. http는 감사 로그를 커서로
 * 나눠 읽으므로 '더 불러오기'가 이전 쪽을 이어 붙인다. 새로 읽으면 이어 붙인 쪽은 버린다(경계가 바뀐다).
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import type { ActionFeed, ActionRecord } from "@/lib/admin/types";
import { useAdminResource, type AdminResource } from "../useAdminResource";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

export interface ActionFeedView {
  res: AdminResource<ActionFeed>;
  /** 받아 온 행(최신이 앞). 처음 읽기 전이면 null */
  rows: ActionRecord[] | null;
  feed: ActionFeed | null;
  /** 더 읽을 이전 쪽이 있는가 */
  hasMore: boolean;
  loadingMore: boolean;
  more: () => void;
}

interface Older {
  base: ActionFeed;
  rows: ActionRecord[];
  cursor: string | null;
}

export function useActionFeed(signal: unknown = null): ActionFeedView {
  const load = useCallback(() => getAdminApi().getActionRecords(null), []);
  const res = useAdminResource<ActionFeed>(load, { isSessionError });
  const [older, setOlder] = useState<Older | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const reload = res.reload;

  // 처음 그린 값은 읽기 신호가 아니다. 진입 조회는 useAdminResource가 이미 냈다
  const lastSignal = useRef(signal);
  useEffect(() => {
    if (Object.is(lastSignal.current, signal)) return;
    lastSignal.current = signal;
    reload();
  }, [signal, reload]);

  const base = res.data;
  const extra = older !== null && older.base === base ? older : null;
  const rows = useMemo(() => {
    if (base === null) return null;
    if (extra === null) return base.rows;
    const seen = new Set(base.rows.map((r) => r.id));
    return [...base.rows, ...extra.rows.filter((r) => !seen.has(r.id))];
  }, [base, extra]);
  const cursor = extra !== null ? extra.cursor : base?.nextCursor ?? null;

  const more = () => {
    if (base === null || cursor === null || loadingMore) return;
    setLoadingMore(true);
    getAdminApi()
      .getActionRecords(cursor)
      .then((page) => setOlder({ base, rows: [...(extra?.rows ?? []), ...page.rows], cursor: page.nextCursor }))
      .catch(() => {
        // 이전 쪽을 못 읽어도 받은 쪽은 그대로 둔다. 단추가 남아 다시 누를 수 있다
      })
      .finally(() => setLoadingMore(false));
  };

  return { res, rows, feed: base, hasMore: cursor !== null, loadingMore, more };
}
