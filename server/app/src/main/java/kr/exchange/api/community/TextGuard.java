package kr.exchange.api.community;

/**
 * 사용자 입력 텍스트 출력 이스케이프(게시판_설계.md, XSS 방어). 저장은 원문 그대로 두고 응답 렌더
 * 시점에 HTML 유의 문자를 엔티티로 치환한다. 순진한 프론트가 innerHTML로 렌더해도 스크립트가
 * 실행되지 않는다. 저장 시 이스케이프하지 않는 이유는 이중 이스케이프·수정 왕복 훼손을 피하기 위함이다.
 */
final class TextGuard {

    private TextGuard() {
    }

    static String escape(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
