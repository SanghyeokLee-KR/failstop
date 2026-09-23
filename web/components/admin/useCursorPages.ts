"use client";

/**
 * 커서 목록의 쪽 이동. 여섯 화면에 같은 코드가 여섯 벌 있던 것을 하나로 모은다.
 *
 * 커서 규약은 앞으로만 발급한다(API §2-6). 그래서 i번째 칸이 i쪽을 여는 커서인 배열을
 * 들고 다니며, 첫 쪽은 null이다. 뒤로 가려면 그 배열을 되짚는다.
 *
 * 조건이 바뀌면 쪽 경계가 새로 생긴다. 들고 있던 커서는 이전 조건의 것이라 버려야 한다.
 * 종전에는 화면마다 필터 변경 핸들러가 그것을 손으로 했고, 그 셋 중 하나를 빠뜨리면
 * 다른 조건의 커서로 조회가 나간다. 여기서는 key가 바뀌는 것을 하나의 신호로 삼는다.
 *
 * 상태와 쪽을 한 효과로 묶는 이유. 둘로 나누면 쪽이 이미 0일 때 조건만 바뀌는 경우
 * setPage(0)이 무동작이라 재조회가 걸리지 않고, 이전 조건의 결과가 새 탭 아래 남는다.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import type { AdminPage } from "@/lib/admin/types";
import { useAdminResource, type AdminResource, type Cadence } from "./useAdminResource";

export interface CursorPages<T> {
  /** 0부터 센다. 표시는 +1 */
  page: number;
  rows: T[];
  hasMore: boolean;
  /** Pager의 onMove에 그대로 넘긴다 */
  move: (target: number) => void;
  /** 리소스 상태. 화면이 로딩·오류·지연을 그린다 */
  res: AdminResource<AdminPage<T>>;
}

export function useCursorPages<T>(
  /** 한 쪽을 읽는다. 커서만 받고 한도는 소비처가 닫아 둔다 */
  load: (cursor: string | null) => Promise<AdminPage<T>>,
  opts: {
    /**
     * 현재 조건. 이 값이 바뀌면 커서를 버리고 첫 쪽부터 다시 센다.
     * 필터가 없는 목록은 생략한다.
     */
    key?: string;
    cadence?: Cadence;
    isSessionError?: (e: unknown) => boolean;
  } = {},
): CursorPages<T> {
  const key = opts.key ?? "";
  const [page, setPage] = useState(0);
  const cursors = useRef<(string | null)[]>([null]);
  const lastKey = useRef(key);
  // 매 렌더 새로 만들어지는 클로저를 러너 의존에서 떼어 낸다. 실행 시점의 것만 쓴다
  const loadRef = useRef(load);
  loadRef.current = load;

  const runner = useCallback(async () => {
    const r = await loadRef.current(cursors.current[page] ?? null);
    cursors.current[page + 1] = r.nextCursor;
    return r;
  }, [page]);

  const res = useAdminResource<AdminPage<T>>(runner, {
    cadence: opts.cadence,
    isSessionError: opts.isSessionError,
  });

  // 마지막으로 조회를 낸 조건과 쪽. 진입 조회는 useAdminResource가 이미 내므로 초기값으로 둔다
  const loaded = useRef({ key, page });

  useEffect(() => {
    if (lastKey.current !== key) {
      lastKey.current = key;
      cursors.current = [null];
      if (page !== 0) {
        // 첫 쪽으로 돌아간 렌더가 조회를 낸다. 여기서 부르면 옛 쪽 커서로 한 번 더 나간다
        setPage(0);
        return;
      }
    }
    if (loaded.current.key === key && loaded.current.page === page) return;
    loaded.current = { key, page };
    void res.reload();
    // reload는 안정 참조다. 조건과 쪽 변화에만 반응한다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, page]);

  return {
    page,
    rows: res.data?.data ?? [],
    hasMore: res.data?.hasMore ?? false,
    move: setPage,
    res,
  };
}
