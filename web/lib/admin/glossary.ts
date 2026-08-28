/**
 * lib/admin/glossary.ts. 감사 액션 코드 -> 한국어 주해 사전(관리자_설계 §3-2, §4-2 표시층).
 * 기록 원문은 불변이고 화면 병기용으로만 쓴다. 미등재 코드는 원문 단독 폴백(억지 번역 금지).
 */

export const ACTION_GLOSS: Record<string, string> = {
  "session.establish": "콘솔 접속",
  "session.revoke": "콘솔 종료",
  "chaos.inject": "카오스 주입",
  "chaos.clear": "카오스 해제",
  "sim.bots.params": "봇 파라미터 변경",
  "sim.scenario": "국면 프리셋 적용",
  "community.post.moderate": "게시글 처리",
  "community.comment.moderate": "댓글 처리",
  "community.report.resolve": "신고 종결",
  "config.market.halt": "마켓 중지",
  "config.market.resume": "마켓 재개",
  "config.market.fees": "수수료 변경",
  "config.notice.publish": "공지 게시",
  // sim 감사 스트림의 실제 기록 코드. 설계 어휘(publish)와 같은 액션이라 같은 주해를 단다
  "config.notice.post": "공지 게시",
  "config.notice.update": "공지 수정",
  "config.notice.delete": "공지 삭제",
  "operators.create": "운영자 생성",
  "operators.status": "운영자 상태 변경",
  "operators.roles": "역할 변경",
  "members.suspend": "회원 정지",
  "members.reinstate": "회원 복구",
  "members.close": "회원 폐쇄",
  // 합성 코드(head:tail)의 head 주해. 감사 화면이 "실행 · 카오스 주입"처럼 이어 붙인다
  "approval.issue": "승인 발급",
  "approval.reject": "승인 거부",
  execute: "실행",
  "sim.start": "봇 플릿 시작",
  "sim.stop": "봇 플릿 정지",
  "sim.pause": "봇 일시정지 전환",
  "sim.rate": "OPS 목표 변경",
  "sim.scale": "봇 대수 변경",
  "sim.bias": "방향 바이어스 변경",
  "sim.effects": "체결 효과 변경",
  "sim.bots.create": "봇 생성",
  "sim.bots.delete": "봇 제거",
};

/** 액션 코드 네임스페이스(첫 점 앞) -> 한국어. 감사 필터의 액션 select가 병기한다 */
export const NS_GLOSS: Record<string, string> = {
  session: "세션",
  chaos: "카오스",
  config: "설정",
  operators: "운영자",
  // 봇 액션의 실기록 네임스페이스는 sim(sim.start, sim.bots.params 등)
  sim: "봇 플릿",
  approval: "승인",
  execute: "실행",
};
