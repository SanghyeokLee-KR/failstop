import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // 정적 산출물(out/)을 Nginx, Spring 정적 리소스로 서빙하기 위한 설정
  output: "export",
};

export default nextConfig;
