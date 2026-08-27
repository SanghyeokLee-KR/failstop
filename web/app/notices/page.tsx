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
 */

import { useEffect } from "react";
import { ConnectionBanner } from "@/components/StatusSignals";
import Skeleton from "@/components/Skeleton";
import { usePageTitle } from "@/components/PageTitle";
import { useExchangeStore } from "@/lib/store";
import { sortNotices } from "@/lib/notices";
import type { NoticeSeverity } from "@/lib/types";
import styles from "./page.module.css";

const SEVERITY_LABEL: Record<NoticeSeverity, string> = {
  CRITICAL: "중요",
  WARNING: "주의",
  INFO: "안내",
};

export default function NoticesPage() {
  usePageTitle("공지사항");
  const notices = useExchangeStore((s) => s.notices);
  const loaded = useExchangeStore((s) => s.noticesLoaded);
  const loadNotices = useExchangeStore((s) => s.loadNotices);
  const markNoticesSeen = useExchangeStore((s) => s.markNoticesSeen);

  useEffect(() => {
    void loadNotices();
  }, [loadNotices]);

  /* 목록에 들어온 시점이 읽은 시점이다. 개별 공지를 펼쳐야 읽음 처리하는 방식은
     전문이 이미 목록에 다 보이는 구조에서 의미가 없다 */
  useEffect(() => {
    if (notices.length > 0) markNoticesSeen();
  }, [notices, markNoticesSeen]);

  const rows = sortNotices(notices);

  return (
    <main className={styles.page}>
      <ConnectionBanner />
      <header className={styles.head}>
        <span className="gauge-plate">거래소</span>
        <h1 className={styles.title}>공지사항</h1>
        <p className={styles.desc}>점검·장애·기능 변경 안내. 최신순</p>
      </header>

      {!loaded ? (
        <Skeleton variant="card" width="100%" height={200} />
      ) : notices.length === 0 ? (
        <section className="card">
          <div className="empty-state empty-state--compact">
            <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
            <span className="empty-state__title">등록된 공지 없음</span>
            <span className="empty-state__desc">점검·장애 안내가 있으면 여기에 표시</span>
          </div>
        </section>
      ) : (
        <ol className={styles.list}>
          {rows.map((notice) => (
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
    </main>
  );
}

/** 공지는 초 단위가 의미 없다. 날짜와 시각까지만 */
function formatDate(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
