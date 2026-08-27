package kr.exchange.api.support;

import kr.exchange.api.common.ApiException;

import java.util.Set;

/**
 * 문의 입력 검증(API 설계 §4-G).
 *
 * MemberInputs·TransferInputs와 같은 이유로 분리한다. DB도 Spring도 필요 없는 순수 규칙인데
 * 서비스 안에 있으면 Testcontainers 통합 테스트로만 검증된다.
 */
public final class SupportInputs {

    private SupportInputs() {
    }

    /** DDL CHECK와 같은 어휘. 한쪽만 늘면 23514가 런타임에 터진다 */
    public static final Set<String> CATEGORIES = Set.of("TRADE", "TRANSFER", "ACCOUNT", "ETC");

    /** support_inquiries.title VARCHAR(120) */
    public static final int MAX_TITLE = 120;
    /** 본문은 TEXT지만 상한을 둔다. 무제한 입력은 화면 렌더와 큐 판독을 함께 망친다 */
    public static final int MAX_BODY = 2000;
    public static final int MAX_ANSWER = 2000;
    private static final int MIN_BODY = 5;

    public static String category(String raw) {
        if (raw == null || !CATEGORIES.contains(raw)) {
            throw new ApiException(400, "invalid_support_input",
                    "분류는 TRADE, TRANSFER, ACCOUNT, ETC 중 하나");
        }
        return raw;
    }

    /**
     * 제목·본문은 공백류를 접고 양끝을 자른다.
     * 자르지 않으면 공백만으로 이뤄진 문의가 큐에 쌓이고, 운영자는 열어봐야 빈 것을 안다.
     */
    public static String title(String raw) {
        String value = collapse(raw);
        if (value.isEmpty()) {
            throw new ApiException(400, "invalid_support_input", "제목 필요");
        }
        if (value.codePointCount(0, value.length()) > MAX_TITLE) {
            throw new ApiException(400, "invalid_support_input", "제목은 최대 " + MAX_TITLE + "자");
        }
        return value;
    }

    /**
     * 본문은 줄바꿈을 보존한다. 문의는 상황을 나열하는 글이라 줄 구분이 내용의 일부다.
     * 제목과 달리 접으면 "언제 무엇을 했다"가 한 문단으로 뭉친다.
     */
    public static String body(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.codePointCount(0, value.length()) < MIN_BODY) {
            throw new ApiException(400, "invalid_support_input", "내용은 최소 " + MIN_BODY + "자");
        }
        if (value.codePointCount(0, value.length()) > MAX_BODY) {
            throw new ApiException(400, "invalid_support_input", "내용은 최대 " + MAX_BODY + "자");
        }
        return value;
    }

    public static String answer(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            throw new ApiException(400, "invalid_support_input", "답변 내용 필요");
        }
        if (value.codePointCount(0, value.length()) > MAX_ANSWER) {
            throw new ApiException(400, "invalid_support_input", "답변은 최대 " + MAX_ANSWER + "자");
        }
        return value;
    }

    /** 공백류를 U+0020 하나로 접고 양끝 절삭. 제목 전용 */
    private static String collapse(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }
}
