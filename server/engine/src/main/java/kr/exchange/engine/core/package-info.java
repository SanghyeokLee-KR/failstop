/**
 * 매칭엔진 코어. 결정론 상태기계와 단일 라이터 루프의 순수 Java 구현을 담는다.
 *
 * 결정론 원칙(API 설계 v2.5 §0-1, §7-2):
 * - 입력은 OrderCommand 스트림 하나뿐이다. 같은 명령열은 항상 같은 이벤트열을 낳는다.
 * - 시간은 command의 engineTs로 박제된 값만 쓴다. 엔진 내부 System.currentTimeMillis() 금지.
 * - 난수, I/O, 락, Spring 의존 금지. tie-break는 command의 tieBreak 값만 사용한다.
 * - 이 규칙이 깨지면 저널 재생(replay)이 라이브 상태와 달라져 복구 증명(recovery_runs.matched)이 무너진다.
 */
package kr.exchange.engine.core;
