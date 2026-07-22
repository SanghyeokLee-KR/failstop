"use client";

/**
 * 마켓·공지(/admin/config). 마켓 halt/resume는 SUPER + 2인 승인(사용자 확정 결정).
 * 공지 등록은 SUPER 즉시 실행(가역, 승인 불요). 수수료는 조회 표기.
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { useCan } from "@/lib/admin/store";
import type { ConfigMarket, DestructiveAction, Notice } from "@/lib/admin/types";
import { formatTimeHMS } from "@/lib/format";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import RequestButton from "@/components/admin/RequestButton";
import ApprovalList from "@/components/admin/ApprovalList";
import { HealthPill, PageHeader, PermissionNote } from "@/components/admin/ui";
import { toast } from "@/components/Toast";
import styles from "@/components/admin/admin.module.css";

const MARKET_ACTIONS: DestructiveAction[] = ["config.market.halt", "config.market.resume"];

export default function ConfigPage() {
  const canSuper = useCan("SUPER");
  const [markets, setMarkets] = useState<ConfigMarket[] | null>(null);
  const [notices, setNotices] = useState<Notice[] | null>(null);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const actions = useMemo(() => MARKET_ACTIONS, []);

  const load = useCallback(async () => {
    try {
      const api = getAdminApi();
      const [m, n] = await Promise.all([api.getConfigMarkets(), api.getNotices()]);
      setMarkets(m);
      setNotices(n);
    } catch {
      // 세션 게이트 처리
    }
  }, []);

  useEffect(() => {
    void load();
    const iv = setInterval(() => void load(), 2000);
    return () => clearInterval(iv);
  }, [load]);

  const postNotice = async () => {
    if (!title.trim() || !body.trim()) {
      toast("제목과 내용을 입력하세요", "error");
      return;
    }
    try {
      await getAdminApi().postNotice(title.trim(), body.trim());
      toast("공지를 등록했어요", "info");
      setTitle("");
      setBody("");
      await load();
    } catch (e) {
      toast(e instanceof Error ? e.message : "등록에 실패했어요", "error");
    }
  };

  const marketCols: DataColumn<ConfigMarket>[] = [
    { key: "symbol", label: "마켓", width: "120px", render: (m) => m.symbol },
    { key: "name", label: "이름", width: "minmax(120px, 1fr)", render: (m) => m.koreanName },
    {
      key: "state",
      label: "상태",
      width: "110px",
      render: (m) => (m.state === "ACTIVE" ? <HealthPill health="success" label="거래 중" /> : <HealthPill health="danger" label="중지" />),
    },
    { key: "maker", label: "maker", width: "90px", align: "right", render: (m) => m.makerFee },
    { key: "taker", label: "taker", width: "90px", align: "right", render: (m) => m.takerFee },
    {
      key: "action",
      label: "제어",
      width: "130px",
      align: "right",
      render: (m) =>
        m.state === "ACTIVE" ? (
          <RequestButton
            action="config.market.halt"
            payload={{ action: "config.market.halt", symbol: m.symbol }}
            label="중지 요청"
            onRequested={load}
          />
        ) : (
          <RequestButton
            action="config.market.resume"
            payload={{ action: "config.market.resume", symbol: m.symbol }}
            label="재개 요청"
            onRequested={load}
          />
        ),
    },
  ];

  return (
    <div className={styles.page}>
      <PageHeader title="마켓·공지" desc="마켓 거래 중지·재개는 SUPER + 2인 승인. 공지는 즉시 등록." />

      <PanelCard title="마켓 상태·수수료">
        {!canSuper && <PermissionNote>마켓 제어 권한(SUPER)이 없어 조회만 가능합니다.</PermissionNote>}
        {!markets ? (
          <Skeleton variant="card" width="100%" height={200} />
        ) : (
          <DataTable columns={marketCols} rows={markets} rowKey={(m) => m.symbol} rowHeight={48} ariaLabel="마켓 설정" />
        )}
      </PanelCard>

      <PanelCard title="마켓 제어 승인 대기 · 실행">
        <ApprovalList actions={actions} onChange={load} emptyLabel="대기 중인 마켓 제어 승인이 없어요" />
      </PanelCard>

      <PanelCard title="공지 등록">
        {!canSuper && <PermissionNote>공지 등록 권한(SUPER)이 없습니다.</PermissionNote>}
        <div className={styles.noticeForm}>
          <label className={styles.field}>
            <span>제목</span>
            <input value={title} onChange={(e) => setTitle(e.target.value)} disabled={!canSuper} aria-label="공지 제목" />
          </label>
          <label className={styles.field}>
            <span>내용</span>
            <textarea value={body} onChange={(e) => setBody(e.target.value)} disabled={!canSuper} rows={3} aria-label="공지 내용" />
          </label>
          <button type="button" className={styles.primaryBtn} disabled={!canSuper} onClick={() => void postNotice()}>
            등록
          </button>
        </div>
      </PanelCard>

      <PanelCard title="공지 목록">
        {!notices ? (
          <Skeleton variant="card" width="100%" height={120} />
        ) : notices.length === 0 ? (
          <p className={styles.approvalEmpty}>등록된 공지가 없어요</p>
        ) : (
          <ul className={styles.noticeList}>
            {notices.map((n) => (
              <li key={n.id} className={styles.noticeItem}>
                <div className={styles.noticeHead}>
                  <span className={styles.noticeTitle}>{n.title}</span>
                  <span className={styles.noticeMeta}>
                    {n.createdBy} · {formatTimeHMS(n.createdAt)}
                  </span>
                </div>
                <p className={styles.noticeBody}>{n.body}</p>
              </li>
            ))}
          </ul>
        )}
      </PanelCard>
    </div>
  );
}
