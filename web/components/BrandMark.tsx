/**
 * 수평 이음매로 갈라진 다이아몬드. 위가 멈춰도 아래가 잇는다(fail-stop 후 failover).
 * 색은 currentColor 상속만 받아 소비처 잉크를 따른다.
 */

export default function BrandMark({ size = 20 }: { size?: number }) {
  return (
    // stroke를 쓰면 획이 각 변을 바깥으로 밀어 2.4유닛 이음매가 0.4유닛으로 줄고
    // 20px 렌더에서 0.27px가 돼 분할이 사라진다. fill만 써서 이음매를 그대로 남긴다
    <svg width={size} height={size} viewBox="0 0 30 30" fill="currentColor" aria-hidden="true">
      <path d="M15 2.4 L26.4 13.2 L3.6 13.2 Z" />
      <path d="M3.6 16.8 L26.4 16.8 L15 27.6 Z" />
    </svg>
  );
}
