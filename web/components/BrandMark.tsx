/**
 * 페일스톱 브랜드 마크. 수평 이음매로 갈라진 다이아몬드: 위가 멈춰도 아래가 잇는다
 * (fail-stop 후 failover). 색은 currentColor 상속만 받아 소비처 잉크를 따른다.
 * 무연고 장식이던 단일 다이아를 도메인 모티프로 교체한 v1.6 마크.
 */

export default function BrandMark({ size = 20 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 30 30" fill="currentColor" aria-hidden="true">
      <path d="M15 3.2 L25.6 13.8 L4.4 13.8 Z" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" />
      <path d="M4.4 16.2 L25.6 16.2 L15 26.8 Z" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" />
    </svg>
  );
}
