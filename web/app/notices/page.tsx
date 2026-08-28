"use client";

/**
 * 공지 목록(§4-F).
 *
 * 배너가 전하는 것은 "지금 무슨 일이 있다"이고, 여기가 전하는 것은 "무엇이 언제 있었나"다.
 * 그래서 배너는 한 건만, 목록은 전부 시간순이다. 심각도로 정렬하지 않는다. 사람은 공지를
 * 읽을 때 시간 축으로 읽고, 등급으로 묶으면 "어제 일과 오늘 일" 관계가 사라진다.
 *
 * 등급은 정렬이 아니라 형태로 드러낸다. 왼쪽 표식 막대의 색과 라벨이 등급을 지고,
 * 본문 잉크는 등급과 무관하게 같다. 등급으로 본문 색까지 바꾸면 INFO 공지가 읽기 어려운
 * 흐린 글씨가 되고, 그건 등급이 낮다는 뜻이지 덜 읽어도 된다는 뜻이 아니다.
 *
 * 전역 스토어의 notices를 읽지 않는다. 그쪽은 배너와 진입점 표식이 쓰는 첫 쪽이고, 여기는
 * 아카이브라 커서로 끝까지 따라가야 한다. 한 배열이 두 역할을 겸하던 동안 목록은 서버의
 * 고정 상한에 묶여 그보다 오래된 공지를 볼 방법이 없었다.
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

  /* 목록에 들어온 시점이 읽은 시점이다. 개별 공지를 펼쳐야 읽음 처리하는 방식은
     전문이 이미 목록에 다 보이는 구조에서 의미가 없다.

     첫 쪽에서만 표식을 내린다. 읽음 기준은 스토어가 든 최신 발행 시각이고, 뒤쪽을
     넘겨보는 것은 과거를 읽는 행위라 최신 공지를 읽었다는 근거가 되지 않는다 */
  useEffect(() => {
    if (page === 0 && rows && rows.length > 0) markNoticesSeen();
  }, [page, rows, markNoticesSeen]);

  const sorted = rows === null ? null : sortNotices(rows);

  return (
    <main className={styles.page}>
      <ConnectionBanner />
      <header className={styles.head}>
        <span className="gauge-plate">거래소</span>
        <h1 className={styles.title}>공지사항</h1>
        <p className={styles.desc}>점검·장애·기능 변경 안내. 최신순</p>
      </header>

      {sorted === null && !failed ? (
        <Skeleton variant="card" width="100%" height={200} />
      ) : failed ? (
        /* 종전에는 스토어가 실패를 삼켜 빈 목록으로 보였다. 화면이 직접 읽는 지금은
           "없다"와 "못 읽었다"를 구분해 말한다. 둘은 사용자가 할 일이 다르다 */
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
