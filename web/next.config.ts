import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // 정적 산출물(out/)을 Nginx, Spring 정적 리소스로 서빙하기 위한 설정.
  // output:"export"에서는 distDir이 빌드 작업 디렉터리가 아니라 export 출력 경로가 된다.
  // distDir로 dev 서버의 .next를 격리하려는 시도는 산출물 경로만 out/ 밖으로 옮기고 실패한다
  output: "export",
};

export default nextConfig;
