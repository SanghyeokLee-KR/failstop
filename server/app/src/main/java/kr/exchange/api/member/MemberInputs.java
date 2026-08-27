package kr.exchange.api.member;

import kr.exchange.api.common.ApiException;

import java.text.Normalizer;

/**
 * 가입·로그인 입력 정규화와 형식 검증(회원_설계 §1-1, §3-4).
 *
 * MemberAuthService에서 분리한 이유는 두 가지다. 이 규칙은 Spring 컨텍스트도 DB도 필요 없는
 * 순수 함수인데 서비스 안에 있으면 Testcontainers 통합 테스트로만 검증된다. 그리고 프론트가
 * 같은 규칙을 인라인으로 다시 구현하므로, 두 구현이 갈렸을 때 비교할 단일 기준이 필요하다.
 *
 * 이메일은 완전 RFC 5322 정규식을 쓰지 않는다. 그 정규식은 수백 자에 중첩 수량자를 포함해
 * 입력에 따라 지수 시간으로 갈 수 있고(ReDoS), 가입은 무인증 엔드포인트라 그 경로가 곧
 * 공격면이다. 대신 길이를 먼저 자르고 구조를 직접 훑는다. 판정은 선형이고 각 거절 사유가
 * 코드에 그대로 남는다.
 *
 * 실제 소유 검증(메일 발송)은 하지 않는다. 런타임 외부 API 의존 금지 규칙이라 불가능하고,
 * 이메일은 미검증 데모 핸들이라는 것이 정본의 확정 사항이다(§1-1).
 */
public final class MemberInputs {

    private MemberInputs() {
    }

    /** RFC 5321 경로 상한. 정규식보다 먼저 걸어 긴 입력이 검증 비용이 되지 않게 한다 */
    public static final int MAX_EMAIL_LENGTH = 254;
    /** RFC 5321 로컬파트 상한 */
    public static final int MAX_LOCAL_LENGTH = 64;
    /** DNS 라벨 상한 */
    public static final int MAX_LABEL_LENGTH = 63;
    /** user_profiles.display_name VARCHAR(32). 코드포인트로 센다(DDL과 같은 단위) */
    public static final int MAX_DISPLAY_NAME = 32;

    /* RFC 5322 atext에서 실무상 쓰이지 않는 기호(`{|}~^ 등)까지 다 열면 표시·로그·검색에서
       계속 예외를 만든다. 실제 메일 주소에 쓰이는 범위로 좁힌다. 데모 핸들이라 손해가 없다 */
    private static final String LOCAL_SPECIALS = "._%+-";

    /**
     * 이메일 정규화(소문자) 후 반환. 형식 오류는 400 invalid_email.
     *
     * 막는 것: @ 부재·복수, 로컬·도메인 빈 값, 점 시작·끝, 연속 점, 하이픈으로 시작·끝나는
     * 라벨, 점 없는 도메인, 숫자만으로 끝나는 TLD, 길이 초과, 공백·제어문자.
     */
    public static String email(String raw) {
        if (raw == null) {
            throw invalidEmail("이메일이 필요합니다.");
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw invalidEmail("이메일이 필요합니다.");
        }
        if (value.length() > MAX_EMAIL_LENGTH) {
            throw invalidEmail("이메일이 너무 깁니다.");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // trim이 U+0020 이하만 걷어내므로 그 밖의 공백·제어문자는 여기서 잡는다
            if (c <= ' ' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw invalidEmail("이메일에 공백을 쓸 수 없습니다.");
            }
        }
        int at = value.indexOf('@');
        if (at < 0 || at != value.lastIndexOf('@')) {
            throw invalidEmail("이메일 형식이 올바르지 않습니다.");
        }
        checkLocalPart(value.substring(0, at));
        checkDomain(value.substring(at + 1));
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 로그인 조회용 정규화. 형식 검증은 하지 않는다.
     *
     * 형식 규칙은 가입 시점에 한 번 적용되고, 로그인은 이미 저장된 행을 찾는 조회다. 여기서도
     * 최신 규칙을 강제하면 규칙이 엄해질 때마다 이전 규칙으로 가입한 계정이 로그인 자체를
     * 잃는다. 실제로 이 프로젝트의 초기 규칙은 연속 점과 하이픈 시작 도메인을 통과시켰다.
     *
     * 길이 상한만 남긴다. 무인증 경로라 임의 길이 문자열이 그대로 DB 조회 인자가 되면 안 된다.
     * 형태가 어긋난 입력은 그냥 조회에서 안 맞고, 호출부가 invalid_credentials로 수렴시킨다.
     * 형식 오류와 자격 불일치를 로그인에서 갈라 말할 이유도 없다.
     *
     * @return 정규화된 이메일. 비었거나 상한을 넘으면 null
     */
    public static String normalizeEmailForLookup(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_EMAIL_LENGTH) {
            return null;
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static void checkLocalPart(String local) {
        if (local.isEmpty() || local.length() > MAX_LOCAL_LENGTH) {
            throw invalidEmail("이메일 형식이 올바르지 않습니다.");
        }
        if (local.charAt(0) == '.' || local.charAt(local.length() - 1) == '.') {
            throw invalidEmail("이메일의 점 위치가 올바르지 않습니다.");
        }
        char prev = 0;
        for (int i = 0; i < local.length(); i++) {
            char c = local.charAt(i);
            boolean ok = isAsciiAlnum(c) || LOCAL_SPECIALS.indexOf(c) >= 0;
            if (!ok) {
                throw invalidEmail("이메일에 쓸 수 없는 문자가 있습니다.");
            }
            if (c == '.' && prev == '.') {
                throw invalidEmail("이메일에 점이 연속될 수 없습니다.");
            }
            prev = c;
        }
    }

    private static void checkDomain(String domain) {
        if (domain.isEmpty() || domain.length() > MAX_EMAIL_LENGTH - 2) {
            throw invalidEmail("이메일 형식이 올바르지 않습니다.");
        }
        if (domain.charAt(0) == '.' || domain.charAt(domain.length() - 1) == '.') {
            throw invalidEmail("이메일의 점 위치가 올바르지 않습니다.");
        }
        String[] labels = domain.split("\\.", -1);
        // 점 없는 도메인(localhost)은 거절한다. 데모 계정 식별자로 쓰이므로 형태를 좁혀둔다
        if (labels.length < 2) {
            throw invalidEmail("이메일 도메인 형식이 올바르지 않습니다.");
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
                throw invalidEmail("이메일 도메인 형식이 올바르지 않습니다.");
            }
            if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                throw invalidEmail("이메일 도메인에 하이픈 위치가 올바르지 않습니다.");
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!isAsciiAlnum(c) && c != '-') {
                    throw invalidEmail("이메일 도메인에 쓸 수 없는 문자가 있습니다.");
                }
            }
        }
        String tld = labels[labels.length - 1];
        if (tld.length() < 2) {
            throw invalidEmail("이메일 도메인 형식이 올바르지 않습니다.");
        }
        for (int i = 0; i < tld.length(); i++) {
            // 숫자·하이픈만인 최상위 라벨은 실재하지 않는다. IP 리터럴 형태를 여기서 막는다
            if (!isAsciiLetter(tld.charAt(i))) {
                throw invalidEmail("이메일 도메인 형식이 올바르지 않습니다.");
            }
        }
    }

    /**
     * 표시명 정규화 후 반환. 형식 오류는 400 invalid_parameter.
     *
     * 표시명은 게시판에서 다른 회원의 글 옆에 작성자로 렌더된다(CommunityService). 그래서
     * 길이만이 아니라 보이지 않는 문자를 막아야 한다. 막지 않으면 두 계정이 화면상 같은
     * 이름을 갖거나(제로폭), 이름이 거꾸로 그려져 다른 문자열로 읽힌다(양방향 제어).
     *
     * NFC로 먼저 합성하는 이유는 같은 글자의 조합형·완성형이 서로 다른 문자열로 저장돼
     * 화면에서만 같아 보이는 것을 줄이기 위해서다. 동형이의 문자(키릴 a 등)까지는 막지
     * 않는다. 그건 스크립트 혼용 정책이 필요한 별도 주제다.
     */
    public static String displayName(String raw) {
        if (raw == null) {
            throw invalidName("표시명이 필요합니다.");
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFC);
        value = collapseWhitespace(value);
        if (value.isEmpty()) {
            throw invalidName("표시명이 필요합니다.");
        }
        int i = 0;
        while (i < value.length()) {
            int cp = value.codePointAt(i);
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.UNASSIGNED
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                throw invalidName("표시명에 보이지 않는 문자를 쓸 수 없습니다.");
            }
            i += Character.charCount(cp);
        }
        int length = value.codePointCount(0, value.length());
        if (length > MAX_DISPLAY_NAME) {
            throw invalidName("표시명은 최대 " + MAX_DISPLAY_NAME + "자입니다.");
        }
        return value;
    }

    /**
     * 공백류를 전부 U+0020으로 바꾸고 연속을 하나로 접은 뒤 양끝을 자른다.
     * String.trim은 U+0020 이하만 보므로 U+00A0·U+3000만으로 이뤄진 이름이 통과한다.
     */
    private static String collapseWhitespace(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean pendingSpace = false;
        int i = 0;
        while (i < value.length()) {
            int cp = value.codePointAt(i);
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

    private static boolean isAsciiAlnum(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static ApiException invalidEmail(String message) {
        return new ApiException(400, "invalid_email", message);
    }

    private static ApiException invalidName(String message) {
        return new ApiException(400, "invalid_parameter", message);
    }
}
