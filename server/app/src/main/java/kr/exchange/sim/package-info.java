/**
 * 봇 플릿(배포단위 B 상당). 하네스 계약상 봇은 외부 관점 클라이언트다:
 * 거래 행위는 §3 서명 HTTP 왕복만 사용하고 내부 서비스 직호출을 금지한다.
 * 시드(계정·자금·키)만 시드 타임 헬퍼(AccountBootstrap·DemoKeySeeder)를 공유한다.
 */
package kr.exchange.sim;
