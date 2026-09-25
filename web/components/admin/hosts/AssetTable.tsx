"use client";

/**
 * 자산 목록(관리자 설계 4-10-3, 레이아웃 §7 3라운드). 계약의 열한 항목을 여덟 칸 두 줄로 싣는다. 짝을 이루는 항목은 한
 * 칸의 윗줄과 아랫줄이다(호스트와 역할, 위치와 타입, OS와 커널, 가동 시간과 최근 부팅, 수집 상태와 마지막 수집). 1280
 * 창에서 가로 스크롤과 낱말 안 줄바꿈 없이 들고, 넘치는 줄은 말줄임과 원문 툴팁이다. 행을 누르면 노드 상세가 열린다.
 * 읽는 자리라 실행 단추가 없다. 원천이 없는 칸은 그 칸만 '◌'이고 원천 이름은 툴팁과 접근 이름이 싣는다.
 */

import type { ReactNode } from "react";
import { ASSET_SOURCE, assetRows, type AssetCollect, type AssetRow } from "@/lib/admin/assets";
import { useOpsDetail } from "@/lib/admin/detailStore";
import { mdhm, spanWord } from "@/lib/admin/timeText";
import type { ControlCenter, HostAsset } from "@/lib/admin/types";
import StatusShape from "../StatusShape";
import { SimMark } from "../ui";
import { Ago, useClock } from "../control/parts";
import styles from "./hosts.module.css";

const COLLECT: Record<AssetCollect, { shape: "circle" | "triangle" | "dashed" | "ring"; tone: string; word: string }> = {
  OK: { shape: "circle", tone: "success", word: "수집 중" },
  LIVE: { shape: "circle", tone: "success", word: "연결됨" },
  STALE: { shape: "triangle", tone: "warning", word: "수집 지연" },
  MISSING: { shape: "dashed", tone: "off", word: "미수집" },
  OFF: { shape: "ring", tone: "off", word: "꺼짐" },
  PENDING: { shape: "triangle", tone: "warning", word: "기동 중" },
  DRAINING: { shape: "triangle", tone: "warning", word: "드레이닝" },
  ASSUMED: { shape: "dashed", tone: "off", word: "가정" },
};

/** 원천이 없는 칸. 그 칸만 '◌'이고 원천 이름은 툴팁과 접근 이름이 싣는다 */
function Missing({ what }: { what: keyof HostAsset }) {
  return (
    <span className={styles.missing} title={`원천 연결 전 · ${ASSET_SOURCE[what]}`}>
      <span aria-hidden="true">◌</span>
      <span className="sr-only">원천 연결 전 · {ASSET_SOURCE[what]}</span>
    </span>
  );
}

/** 인스턴스가 없는 칸(꺼진 app-3, 구축 전 ai-1). 값이 없는 것이 사실이라 비운다 */
function Blank() {
  return <span className="sr-only">해당 없음</span>;
}

function cell<K extends keyof HostAsset>(r: AssetRow, key: K, show: (v: NonNullable<HostAsset[K]>) => ReactNode): ReactNode {
  if (r.asset === null) return <Blank />;
  const v = r.asset[key];
  if (v === null) return <Missing what={key} />;
  return show(v as NonNullable<HostAsset[K]>);
}

function Row({ r, now }: { r: AssetRow; now: number }) {
  const c = COLLECT[r.collect];
  const open = () => useOpsDetail.getState().openNode(r.id);
  const failed = r.asset?.failedUnits ?? null;
  const typeMissing = r.type.startsWith("장비 원천");
  return (
    <tr className={styles.row} data-off={r.collect === "OFF" ? "" : undefined} onClick={open}>
      <th scope="row" className={styles.nameCell}>
        <span className={styles.line}>
          <button
            type="button"
            className={styles.name}
            aria-haspopup="dialog"
            title={`${r.id} 상세`}
            onClick={(e) => {
              e.stopPropagation();
              open();
            }}
          >
            {r.id}
          </button>
          {r.assumed && (
            <span className={styles.assumed}>
              <SimMark kind="assumed" short="가정" />
            </span>
          )}
        </span>
        <span className={styles.sub}>{r.role}</span>
      </th>
      <td>
        <span className={`${styles.line} num`}>{r.location}</span>
        <span className={`${styles.sub} num`}>{typeMissing ? <span className={styles.missing} title={r.type}>◌</span> : r.type}</span>
      </td>
      <td title={r.asset?.os ? `${r.asset.os}${r.asset.kernel ? ` · 커널 ${r.asset.kernel}` : ""}` : undefined}>
        <span className={styles.line}>{cell(r, "os", (os) => os)}</span>
        {r.asset !== null && <span className={styles.sub}>{cell(r, "kernel", (k) => `커널 ${k}`)}</span>}
      </td>
      <td className="num" data-num="">
        <span className={styles.line}>{cell(r, "bootedAt", (at) => spanWord(now - Date.parse(at)))}</span>
        {r.asset !== null && <span className={styles.sub}>{cell(r, "bootedAt", (at) => `${mdhm(at)} 부팅`)}</span>}
      </td>
      <td className="num" data-num="">
        {cell(r, "securityUpdates", (n) => (n === 0 ? <span className={styles.quiet}>없음</span> : <b>{n}건</b>))}
      </td>
      <td>
        <span className={styles.collect} data-tone={c.tone}>
          <StatusShape kind={c.shape} tone={c.tone} className={styles.collectShape} />
          <span>{c.word}</span>
        </span>
        {r.collect === "OK" && r.collectedAt && (
          <span className={`${styles.sub} ${styles.collectAgo} num`}>
            <Ago at={r.collectedAt} />
          </span>
        )}
      </td>
      <td className="num" data-num="">
        {r.asset === null ? (
          <Blank />
        ) : (
          <>
            {r.diskUsedPct === null ? <span className={styles.quiet}>◌</span> : `${r.diskUsedPct}%`}
            <span className={styles.sep}> · </span>
            {r.asset.inodeUsedPct === null ? <Missing what="inodeUsedPct" /> : `${r.asset.inodeUsedPct}%`}
          </>
        )}
      </td>
      <td className={styles.clip} title={failed && failed.length > 0 ? failed.join(", ") : undefined}>
        {cell(r, "failedUnits", (units) => (units.length === 0 ? <span className={styles.quiet}>없음</span> : <b className={styles.failed}>{units.join(", ")}</b>))}
      </td>
    </tr>
  );
}

export default function AssetTable({ center }: { center: ControlCenter }) {
  // 가동 시간은 분 단위라 분마다만 다시 그린다. 수집 'n초 전'은 그 조각이 시계를 탄다
  const now = useClock(60_000);
  const rows = assetRows(center);
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <caption className="sr-only">노드 자산 목록. 행을 누르면 노드 상세가 열립니다</caption>
        <colgroup>
          <col className={styles.cName} />
          <col className={styles.cAz} />
          <col />
          <col className={styles.cUp} />
          <col className={styles.cUpd} />
          <col className={styles.cCollect} />
          <col className={styles.cDisk} />
          <col className={styles.cFailed} />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">호스트 · 역할</th>
            <th scope="col">위치 · 타입</th>
            <th scope="col">OS · 커널</th>
            <th scope="col" data-num="">가동 · 최근 부팅</th>
            <th scope="col" data-num="" title="보안 업데이트 대기">보안 업데이트</th>
            <th scope="col">수집기</th>
            <th scope="col" data-num="">디스크 · inode</th>
            <th scope="col">실패한 서비스</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <Row key={r.id} r={r} now={now} />
          ))}
        </tbody>
      </table>
    </div>
  );
}
