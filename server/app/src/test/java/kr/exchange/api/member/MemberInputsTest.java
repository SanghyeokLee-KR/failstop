package kr.exchange.api.member;

import kr.exchange.api.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 가입 입력 검증(회원_설계 §1-1, §3-4).
 *
 * 이전 구현은 @ 개수와 마지막 점 위치만 봐서 a..b@x.com, .a@x.com, a@-x.com, a@x..com이
 * 전부 통과했다. 그 구멍이 다시 열리는지 본다.
 *
 * DB도 Spring 컨텍스트도 안 쓴다. 이 규칙이 순수 함수라서 그렇고, Testcontainers에 묶이면
 * Docker 없는 환경에서 회귀 검증이 통째로 멈춘다.
 */
class MemberInputsTest {

    private static String nameError(String raw) {
        return assertThrows(ApiException.class, () -> MemberInputs.displayName(raw)).name();
    }

    private static String emailError(String raw) {
        return assertThrows(ApiException.class, () -> MemberInputs.email(raw)).name();
    }

    @Test
    void validEmailIsLowercased() {
        assertEquals("trader@demo.local", MemberInputs.email("Trader@Demo.Local"));
        assertEquals("trader@demo.local", MemberInputs.email("  trader@demo.local  "));
        assertEquals("a.b+tag_1%x-y@sub.demo.co.kr",
                MemberInputs.email("a.b+tag_1%x-y@sub.demo.co.kr"));
    }

    @Test
    void regressionHolesOfPreviousCheckAreClosed() {
        // 회귀 방지 본체. 넷 다 옛 규칙(@ 개수 + 마지막 점 위치)으로는 통과했다
        assertEquals("invalid_email", emailError("a..b@demo.local"));
        assertEquals("invalid_email", emailError(".a@demo.local"));
        assertEquals("invalid_email", emailError("a@-demo.local"));
        assertEquals("invalid_email", emailError("a@demo..local"));
    }

    @Test
    void malformedEmailsRejected() {
        for (String bad : new String[]{
                "", "   ", "trader", "trader@", "@demo.local", "a@b@demo.local",
                "a.@demo.local", "a@demo.local.", "a@.demo.local", "a@demo-.local",
                "a@localhost", "a@demo.l", "a@demo.123", "a@demo.local-",
                "a b@demo.local", "a@demo local", "a<b>@demo.local", "a\"b@demo.local",
        }) {
            assertEquals("invalid_email", emailError(bad), "통과하면 안 됨: [" + bad + "]");
        }
    }

    @Test
    void interiorWhitespaceAndControlCharsRejected() {
        // 안쪽에 낀 문자는 주소를 실제로 바꾼다. 조회 키가 눈에 안 보이게 갈리므로 거절한다
        assertEquals("invalid_email", emailError("a" + (char) 0x00A0 + "b@demo.local"));
        assertEquals("invalid_email", emailError("a" + (char) 0x0000 + "b@demo.local"));
        assertEquals("invalid_email", emailError("a@demo" + (char) 0x3000 + ".local"));
        assertEquals("invalid_email", emailError("a@demo.local" + (char) 0x3000));
    }

    @Test
    void trailingControlCharsAreTrimmedNotRejected() {
        // trim이 U+0020 이하를 걷으므로 끝에 붙은 NUL·CR·탭은 제거되고 정상 주소가 된다.
        // 붙여넣기에 딸려오는 찌꺼기라 거절보다 제거가 맞다. 제거 후 형태가 어긋나면 그때
        // 형식 검사가 잡으므로 통과 경로가 넓어지지도 않는다
        assertEquals("a@demo.local", MemberInputs.email("a@demo.local" + (char) 0x0000));
        assertEquals("a@demo.local", MemberInputs.email("a@demo.local" + (char) 0x000D));
        assertEquals("a@demo.local", MemberInputs.email((char) 0x0009 + "a@demo.local"));
    }

    @Test
    void lengthLimitsEnforced() {
        String local64 = "a".repeat(64);
        assertEquals(local64 + "@demo.local", MemberInputs.email(local64 + "@demo.local"));
        assertEquals("invalid_email", emailError("a".repeat(65) + "@demo.local"));

        // 라벨 63자는 통과, 64자는 거절
        assertEquals("invalid_email", emailError("a@" + "b".repeat(64) + ".local"));
        assertTrue(MemberInputs.email("a@" + "b".repeat(63) + ".local").endsWith(".local"));

        // 전체 상한은 라벨·로컬 검사보다 먼저 걸려야 한다. 긴 입력이 검증 비용이 되면 안 된다
        assertEquals("invalid_email", emailError("a".repeat(300) + "@demo.local"));
    }

    @Test
    void loginLookupSkipsFormatRules() {
        // 옛 규칙으로 가입한 계정이 규칙 강화로 로그인을 잃으면 안 된다
        assertEquals("a..b@demo.local", MemberInputs.normalizeEmailForLookup("A..B@Demo.Local"));
        assertEquals("trader@demo.local", MemberInputs.normalizeEmailForLookup(" Trader@Demo.Local "));
    }

    @Test
    void loginLookupRejectsOnlyEmptyAndOverlong() {
        assertEquals(null, MemberInputs.normalizeEmailForLookup(null));
        assertEquals(null, MemberInputs.normalizeEmailForLookup(""));
        assertEquals(null, MemberInputs.normalizeEmailForLookup("   "));
        assertEquals(null, MemberInputs.normalizeEmailForLookup("a".repeat(255)));
    }

    @Test
    void displayNameCollapsesWhitespace() {
        assertEquals("데모 트레이더", MemberInputs.displayName("  데모   트레이더  "));
        // U+00A0·U+3000은 String.trim이 못 걷는다. 이것만으로 이뤄진 이름은 빈 이름이다
        assertEquals("invalid_parameter", nameError("" + (char) 0x00A0 + (char) 0x3000));
        assertEquals("A B", MemberInputs.displayName("A" + (char) 0x00A0 + "B"));
    }

    @Test
    void displayNameRejectsInvisibleChars() {
        // 제로폭: 두 계정이 화면상 같은 이름을 갖는다
        assertEquals("invalid_parameter", nameError("관리자" + (char) 0x200B));
        assertEquals("invalid_parameter", nameError("관" + (char) 0xFEFF + "리자"));
        // 양방향 제어: 이름이 거꾸로 그려져 다른 문자열로 읽힌다
        assertEquals("invalid_parameter", nameError("admin" + (char) 0x202E));
        assertEquals("invalid_parameter", nameError("" + (char) 0x2066 + "user"));
    }

    @Test
    void displayNameFoldsLineBreaksIntoSpace() {
        // 개행은 거절 대신 공백으로 접는다. 붙여넣기로 딸려오는 값이고, 접고 나면 게시판
        // 작성자 줄을 깨뜨리지 못한다. 거절하면 사용자는 원인을 모른 채 폼 앞에서 막힌다
        assertEquals("데모 트레이더", MemberInputs.displayName("데모\n트레이더"));
        assertEquals("데모 트레이더", MemberInputs.displayName("데모\r\n\t트레이더"));
    }

    @Test
    void displayNameLengthCountsCodePoints() {
        // DDL이 VARCHAR(32)라 문자 수 기준이다. UTF-16 단위로 세면 보조평면 문자가 절반만 들어간다
        assertEquals("가".repeat(32), MemberInputs.displayName("가".repeat(32)));
        assertEquals("invalid_parameter", nameError("가".repeat(33)));

        String surrogatePair = new String(Character.toChars(0x1F600)); // 보조평면 1자
        String name = surrogatePair.repeat(32);
        assertEquals(64, name.length(), "UTF-16 단위로는 64");
        assertEquals(name, MemberInputs.displayName(name), "코드포인트로는 32라 통과해야 한다");
    }

    @Test
    void displayNameRejectsEmpty() {
        assertEquals("invalid_parameter", nameError(null));
        assertEquals("invalid_parameter", nameError(""));
        assertEquals("invalid_parameter", nameError("     "));
    }

    @Test
    void displayNameNormalizesToNfc() {
        // 조합형(초성 U+1100 + 중성 U+1161)과 완성형 "가"가 다른 문자열로 저장되면
        // 화면에서만 같아 보인다. 소스에 리터럴로 넣으면 편집기가 합쳐버려 검증이 무의미해진다
        String decomposed = new String(new char[]{0x1100, 0x1161});
        assertEquals("가", MemberInputs.displayName(decomposed));
    }
}
