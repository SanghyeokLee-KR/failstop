"use client";

/**
 * DataTable: 자산·거래내역·시스템 로그 공용 정렬 테이블 (§5.2).
 * sortValue가 정의된 컬럼만 정렬 대상. 행 높이는 rowHeight로 화면별 밀도를 맞춘다.
 * 헤더 sticky 기준 top 56px: 페이지 스크롤 컨테이너에서 HeaderNav 아래 고정.
 */

import { useMemo, useState, type KeyboardEvent, type MouseEvent, type ReactNode } from "react";
import styles from "./DataTable.module.css";

/** 맨 글자 값은 요소로 감싼다. 칸이 좁으면 말줄임표로 자르고, 잘린 칸을 가리키면 원문을 툴팁으로 보인다 */
function cellContent(v: ReactNode): ReactNode {
  return typeof v === "string" || typeof v === "number" ? <span>{v}</span> : v;
}

/** 칸을 가리킬 때 글이 잘려 있으면 원문을 툴팁으로 단다. 이미 제 툴팁을 가진 요소는 건드리지 않는다 */
function fitTitle(e: MouseEvent<HTMLElement>): void {
  const el = e.currentTarget.firstElementChild as HTMLElement | null;
  if (!el || (el.hasAttribute("title") && !el.hasAttribute("data-fit-title"))) return;
  if (el.scrollWidth > el.clientWidth + 1) {
    el.title = el.textContent ?? "";
    el.setAttribute("data-fit-title", "");
  } else if (el.hasAttribute("data-fit-title")) {
    el.removeAttribute("title");
    el.removeAttribute("data-fit-title");
  }
}

export interface DataColumn<T> {
  key: string;
  label: string;
  /** grid-template-columns 트랙 값. 생략 시 minmax(0, 1fr) */
  width?: string;
  align?: "left" | "right" | "center";
  /** 정렬 기준 값. 생략 시 정렬 불가 컬럼 */
  sortValue?: (row: T) => number | string;
  /** 처음 정렬 선택 시 방향. 기본 desc */
  firstDir?: "asc" | "desc";
  render: (row: T) => ReactNode;
}

interface SortState {
  key: string;
  dir: "asc" | "desc";
}

interface Props<T> {
  columns: DataColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  defaultSort?: SortState;
  /** px. 기본 44 */
  rowHeight?: number;
  onRowClick?: (row: T) => void;
  rowAriaLabel?: (row: T) => string;
  ariaLabel: string;
  empty?: ReactNode;
  /**
   * 열이 align을 지정하지 않았을 때의 기본 정렬. 기본 left.
   * 관리 콘솔 표는 표 전체를 center로 두지 않고 열의 성격으로 정렬한다(디자인 시스템 §12-7).
   * 식별 텍스트는 기본값 left에 맡기고 수치·시각·조치는 right, 상태 배지는 center로 열에 적는다.
   */
  defaultAlign?: "left" | "right" | "center";
}

export default function DataTable<T>({
  columns,
  rows,
  rowKey,
  defaultSort,
  rowHeight = 44,
  onRowClick,
  rowAriaLabel,
  ariaLabel,
  empty,
  defaultAlign = "left",
}: Props<T>) {
  const [sort, setSort] = useState<SortState | null>(defaultSort ?? null);

  const onSort = (col: DataColumn<T>) => {
    if (!col.sortValue) return;
    setSort((s) =>
      s?.key === col.key
        ? { key: col.key, dir: s.dir === "desc" ? "asc" : "desc" }
        : { key: col.key, dir: col.firstDir ?? "desc" },
    );
  };

  const sorted = useMemo(() => {
    const col = sort ? columns.find((c) => c.key === sort.key) : undefined;
    if (!sort || !col?.sortValue) return rows;
    const sv = col.sortValue;
    return [...rows].sort((a, b) => {
      const va = sv(a);
      const vb = sv(b);
      const cmp =
        typeof va === "string" || typeof vb === "string"
          ? String(va).localeCompare(String(vb), "ko")
          : va - vb;
      return sort.dir === "asc" ? cmp : -cmp;
    });
  }, [rows, sort, columns]);

  const grid = columns.map((c) => c.width ?? "minmax(0, 1fr)").join(" ");

  const onRowKey = (e: KeyboardEvent, row: T) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      onRowClick?.(row);
    }
  };

  return (
    <div className={styles.root} role="table" aria-label={ariaLabel}>
      <div className={styles.head} role="row" style={{ gridTemplateColumns: grid }}>
        {columns.map((c) => {
          const on = sort?.key === c.key;
          return (
            <span
              key={c.key}
              role="columnheader"
              className={styles.hcell}
              data-align={c.align ?? defaultAlign}
              aria-sort={on ? (sort.dir === "desc" ? "descending" : "ascending") : undefined}
            >
              {c.sortValue ? (
                <button
                  type="button"
                  className={styles.sortBtn}
                  data-on={on || undefined}
                  aria-label={`${c.label} 기준 정렬${on ? (sort.dir === "desc" ? ", 내림차순" : ", 오름차순") : ""}`}
                  onClick={() => onSort(c)}
                >
                  {c.label}
                  {on && <span aria-hidden="true">{sort.dir === "desc" ? "▼" : "▲"}</span>}
                </button>
              ) : (
                c.label
              )}
            </span>
          );
        })}
      </div>

      {sorted.length === 0 ? (
        empty
      ) : (
        <div role="rowgroup">
          {sorted.map((row) => (
            <div
              key={rowKey(row)}
              role="row"
              className={styles.row}
              style={{ gridTemplateColumns: grid, height: rowHeight }}
              data-clickable={onRowClick ? true : undefined}
              tabIndex={onRowClick ? 0 : undefined}
              aria-label={rowAriaLabel?.(row)}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              onKeyDown={onRowClick ? (e) => onRowKey(e, row) : undefined}
            >
              {columns.map((c) => (
                <span
                  key={c.key}
                  role="cell"
                  className={styles.cell}
                  data-align={c.align ?? defaultAlign}
                  onMouseEnter={fitTitle}
                >
                  {cellContent(c.render(row))}
                </span>
              ))}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
