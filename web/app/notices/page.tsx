"use client";

/**
 * 목록은 심각도가 아니라 시간순이고, 등급은 표식 막대만 지고 본문 잉크는 등급과 무관하다(§4-F).
 * 전역 스토어 notices는 배너용 첫 쪽이라 아카이브인 이 화면은 자기 커서를 쓴다.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { ConnectionBanner } from "@/components/StatusSignals";
import Pager from "@/components/Pager";
import Skeleton from "@/components/Skeleton";
import { usePageTitle } from "@/components/PageTitle";
import { getDataSource } from "@/lib/api/datasource";
import { useExchangeStore } from "@/lib/store";
import { sortNotices } from "@/lib/notices";
import type { NoticeSeverity, PublicNotice } from "@/lib/types";
import styles from "./page.module.css";

/* 한 쪽 10건. 항목이 122~145px이라 한 쪽이 약 1.4화면이다. 아카이브는 시간 축으로
   훑는 화면이라 한 쪽에 며칠치가 같이 보여야 "어제 일과 오늘 일" 관계가 남는다 */
const PAGE_SIZE = 10;

const SEVERITY_LABEL: Record<NoticeSeverity, string> = {
  CRITICAL: "중요",
  WARNING: "주의",
  INFO: "안내",
};

export default function NoticesPage() {
  usePageTitle("공지사항");
  const markNoticesSeen = useExchangeStore((s) => s.markNoticesSeen);
  /* 배너와 표식이 쓰는 첫 쪽은 이 화면과 별개로 계속 최신이어야 한다. 목록을 보는 동안
     배너가 멈추면 그 사이 올라온 경보를 놓친다 */
  const loadNotices = useExchangeStore((s) => s.loadNotices);

  const [rows, setRows] = useState<PublicNotice[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  /* 커서는 앞으로만 발급된다(§2-6). 뒤로 가려면 지나온 쪽의 시작 커서를 들고 있어야 한다.
     i번째 칸이 i쪽을 여는 커서고 첫 쪽은 null이다 */
  const cursorsRef = useRef<(string | null)[]>([null]);

  const load = useCallback(async (target = 0) => {
    setFailed(false);
    try {
      const res = await getDataSource().getNotices({
        cursor: cursorsRef.current[target] ?? null,
        limit: PAGE_SIZE,
      });
      cursorsRef.current[target + 1] = res.next_cursor;
      setRows(res.data);
      setHasMore(res.has_more);
      setPage(target);
    } catch {
      setRows(null);
      setFailed(true);
    }
  }, []);

  useEffect(() => {
    void load(0);
    void loadNotices();
  }, [load, loadNotices]);

  /* 목록에 들어온 시점이 읽은 시점이다. 뒤쪽 쪽을 넘겨보는 것은 과거를 읽는 행위라
     첫 쪽에서만 표식을 내린다 */
  useEffect(() => {
    if (page === 0 && rows && rows.length > 0) markNoticesSeen();
  }, [page, rows, markNoticesSeen]);

  const sorted = rows === null ? null : sortNotices(rows);

  return (
    <main className={styles.page}>
      {/* 공지 배너는 끈다. 이 화면의 목록이 같은 공지를 이미 첫 항목으로 보여준다.
          연결 배너는 남긴다. 연결 끊김은 목록이 대신 말해주지 않는 사실이다 */}
      <ConnectionBanner showNotice={false} />
      <header className={styles.head}>
        <span className="gauge-plate">거래소</span>
        <h1 className={styles.title}>공지사항</h1>
        <p className={styles.desc}>점검·장애·기능 변경 안내. 최신순</p>
      </header>

      {sorted === null && !failed ? (
        <Skeleton variant="card" width="100%" height={200} />
      ) : failed ? (
        /* 화면이 직접 읽어 "없다"와 "못 읽었다"를 구분해 말한다. 둘은 사용자가 할 일이 다르다 */
        <section className="card">
          <div className="empty-state empty-state--compact" role="alert">
            <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
            <span className="empty-state__title">공지를 불러오지 못함</span>
            <button type="button" className="btn btn--secondary" onClick={() => void load(page)}>
              다시 시도
            </button>
          </div>
        </section>
      ) : sorted && sorted.length === 0 ? (
        <section className="card">
          <div className="empty-state empty-state--compact">
            <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
            {/* 헤더가 이미 "점검·장애·기능 변경 안내"라고 말했다. 빈 목록 아래에서
                같은 말을 반복하면 두 번 읽히기만 하고 새로 아는 것이 없다 */}
            <span className="empty-state__title">등록된 공지 없음</span>
          </div>
        </section>
      ) : (
        <ol className={styles.list}>
          {(sorted ?? []).map((notice) => (
            <li key={notice.id} className={styles.item} data-severity={notice.severity}>
              {/* 색 단독으로 등급을 말하지 않는다. 막대와 라벨을 병기한다 */}
              <span className={styles.rail} aria-hidden="true" />
              <div className={styles.body}>
                <div className={styles.metaRow}>
                  <span className={styles.badge}>{SEVERITY_LABEL[notice.severity]}</span>
                  {notice.published_at != null && (
                    <time className={`${styles.time} num`} dateTime={new Date(notice.published_at).toISOString()}>
                      {formatDate(notice.published_at)}
                    </time>
                  )}
                </div>
                <h2 className={styles.itemTitle}>{notice.title}</h2>
                {notice.body && <p className={styles.itemBody}>{notice.body}</p>}
              </div>
            </li>
          ))}
        </ol>
      )}

      <Pager
        page={page}
        hasMore={hasMore}
        onMove={(target) => void load(target)}
        ariaLabel="공지 목록 쪽 이동"
      />
    </main>
  );
}

/** 공지는 초 단위가 의미 없다. 날짜와 시각까지만 */
function formatDate(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
