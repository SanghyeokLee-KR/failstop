/**
 * OrderPanel의 프리렌더 골격. 주문 패널이 useSearchParams로 CSR 바일아웃하는 동안 자리를 지킨다.
 * 콜드 마운트 상태(지정가·매수·마켓메타 미도착·잔고 미도착)의 DOM을 같은 요소·같은 클래스로 재현한다.
 * side 열이 auto 트랙이라 높이가 어긋나면 아래 마켓 목록이 밀린다(시프트 0 규약).
 * 표기값은 지어내지 않고 OrderPanel과 같은 폴백 상수·포매터에서 뽑는다.
 * OrderPanel의 초기 렌더 구조를 바꾸면 이 파일을 함께 고칠 것.
 */

import PanelCard from "@/components/PanelCard";
import SegmentedTab from "@/components/SegmentedTab";
import Skeleton from "@/components/Skeleton";
import { mulDecimal } from "@/lib/decimal";
import { formatQty } from "@/lib/format";
import { FALLBACK_FEE_RATE_STR, COLD_CTA_LABEL, SIDE_ITEMS } from "./OrderPanel";
import styles from "./OrderPanel.module.css";

const noop = () => {};

export default function OrderPanelSkeleton({ market }: { market: string }) {
  const base = market.split("-")[1] ?? "";
  const feePctStr = mulDecimal(FALLBACK_FEE_RATE_STR, "100");

  return (
    <PanelCard srTitle="주문" className={styles.card}>
      {/* inert: 스크립트 도착 전에는 조작할 수 없는 자리표시다. 포커스·접근성 트리에서 함께 빠진다 */}
      <div className={styles.form} inert>
        <SegmentedTab
          items={SIDE_ITEMS}
          value="bid"
          onChange={noop}
          variant="directional"
          semantics="radio"
          ariaLabel="주문 방향"
        />

        <div className={styles.typRow}>
          <button type="button" className={styles.typBtn} data-on>
            지정가
          </button>
          <button type="button" className={styles.typBtn}>
            시장가
          </button>
        </div>

        <div className={styles.field}>
          <span className={styles.label}>가격</span>
          <div className={styles.inp}>
            <button type="button" className={styles.step}>
              −
            </button>
            <input className="num" placeholder="0" readOnly />
            <span className={`unit-suffix ${styles.unit}`}>원</span>
            <button type="button" className={styles.step}>
              ＋
            </button>
          </div>
        </div>

        <div className={styles.field}>
          <span className={styles.label}>수량</span>
          <div className={styles.inp}>
            <input className="num" placeholder="0" readOnly />
            <span className={`unit-suffix ${styles.unit}`}>{base}</span>
          </div>
        </div>

        <div className={styles.pct}>
          {[10, 25, 50, 100].map((p) => (
            <button key={p} type="button" className={styles.pctBtn}>
              {p === 100 ? "최대" : `${p}%`}
            </button>
          ))}
        </div>

        <div className={styles.divider} />

        <div className={styles.field}>
          <span className={styles.label}>주문총액</span>
          <div className={styles.inp}>
            <input className="num" placeholder="0" readOnly />
            <span className={`unit-suffix ${styles.unit}`}>원</span>
          </div>
        </div>

        <div className={styles.sumRow}>
          <span>예상 수수료 ({feePctStr}%)</span>
          <b className="num">
            {formatQty("0")} <span className="unit-suffix">{base}</span>
          </b>
        </div>
        <div className={styles.sumRow}>
          <span>주문가능</span>
          <Skeleton variant="text" width={110} height={16} />
        </div>

        <button type="button" className={`btn btn--buy btn--full ${styles.cta}`} disabled>
          {COLD_CTA_LABEL}
        </button>
      </div>
    </PanelCard>
  );
}
