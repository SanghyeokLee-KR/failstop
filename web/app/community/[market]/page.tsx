// 정적 export용 서버 셸. 종목 토론 게시판(게시판_설계 §5). 전 마켓 사전 생성.
// 글 상세는 이 라우트의 쿼리 파라미터(?post=externalId)로 열어 정적 export를 유지한다.
import { Suspense } from "react";
import { COIN_META } from "@/lib/sim/engine";
import CommunityClient from "./CommunityClient";

export const metadata = { title: "토론" };

export function generateStaticParams() {
  return COIN_META.map((c) => ({ market: c.market }));
}

export default function CommunityBoardPage() {
  // useSearchParams는 정적 export에서 Suspense 경계를 요구한다
  return (
    <Suspense fallback={null}>
      <CommunityClient />
    </Suspense>
  );
}
