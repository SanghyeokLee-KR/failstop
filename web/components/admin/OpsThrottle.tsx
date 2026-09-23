"use client";

/**
 * 전역 OPS 조절기. 부하를 넣는 유일한 레버다.
 *
 * 이 프로젝트의 부하 유발 수단은 k6가 아니라 봇 발행률이다. 봇 OPS를 올리면 앱 CPU가
 * 오르고, 임계에 닿으면 조회 노드를 늘린다. 그래서 이 레버는 봇 화면만의 것이 아니라
 * 용량 화면에도 있어야 한다. 입력과 관측과 조치가 다른 화면에 흩어져 있으면 시연이
 * 화면 전환에서 끊기고, 보는 쪽은 무엇이 무엇을 움직였는지 잇지 못한다.
 *
 * 같은 컴포넌트를 두 곳에 배치하는 것이지 구현을 복제하는 것이 아니다. 상태 원천도
 * getSimStatus 하나다.
 *
 * 큐 경로는 성공이지만 완료가 아니다. 즉시 완료로 단정하면 봇 JVM이 죽어 명령이 PENDING으로
 * 남은 상태에서도 화면이 반영을 주장한다.
 */

import { useEffect, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { toast } from "@/components/Toast";
import { formatCount } from "@/lib/format";
import styles from "./admin.module.css";

/** 서버 거버너 상한과 같다. 화면이 더 큰 값을 보내면 서버가 반려한다 */
export const OPS_MAX = 600;

export default function OpsThrottle({
  /** 서버가 받아들인 목표. null이면 봇 노드가 상태를 발행하지 않는 것이다 */
  rate,
  /** 실측 발행량. 목표와 갈라 보여야 수렴 중인지 알 수 있다 */
  currentOps,
  canOps,
  onApplied,
}: {
  rate: number | null;
  currentOps: number | null;
  canOps: boolean;
  onApplied: () => void | Promise<void>;
}) {
  // 사용자가 잡은 값. 잡기 전에는 서버 값을 그대로 따르고, 잡은 뒤에는 서버 값이 바뀌어도 뺏지 않는다
  const [picked, setPicked] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const draft = picked ?? rate ?? 1;

  // 서버가 잡은 값에 닿으면 다시 서버 값을 따른다. 큐 경로는 적용 직후 응답이 아직 옛 값일 수 있어
  // 적용 성공만으로 풀지 않는다
  useEffect(() => {
    if (picked !== null && rate === picked) setPicked(null);
  }, [rate, picked]);

  const apply = async () => {
    setBusy(true);
    try {
      const r = await getAdminApi().simSetRate(draft);
      toast(
        r.applyPath === "queued"
          ? `OPS 목표 ${formatCount(draft)} 전달 완료. 반영까지 최대 1초 소요`
          : `OPS 목표 ${formatCount(draft)} 적용 완료`,
        "info",
      );
      await onApplied();
    } catch (e) {
      toast(e instanceof Error ? e.message : "처리 실패", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={styles.throttle}>
      <div className={styles.throttleHead}>
        <span className={styles.throttleLabel}>전역 OPS</span>
        <b className={`${styles.throttleDraft} num`}>{formatCount(draft)}</b>
      </div>
      <input
        type="range"
        // 서버 parsePositive가 0을 반려한다. 정지는 플릿 제어의 전용 버튼 소관이다
        min={1}
        max={OPS_MAX}
        step={1}
        value={draft}
        disabled={!canOps || busy}
        aria-label="전역 OPS 목표"
        onChange={(e) => setPicked(Number(e.target.value))}
      />
      <div className={styles.throttleFoot}>
        <span className={styles.throttleReadout}>
          실측 {currentOps == null ? "미수집" : formatCount(currentOps)}
          {rate != null && ` · 적용값 ${formatCount(rate)}`}
        </span>
        <button
          type="button"
          className={styles.primaryBtn}
          disabled={!canOps || busy || draft === rate}
          onClick={() => void apply()}
        >
          적용
        </button>
      </div>
    </div>
  );
}
