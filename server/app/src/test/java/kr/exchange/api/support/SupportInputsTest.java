package kr.exchange.api.support;

import kr.exchange.api.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 문의 입력 검증(API 설계 §4-G). DB도 Spring도 안 쓴다 */
class SupportInputsTest {

    private static String err(Runnable r) {
        return assertThrows(ApiException.class, r::run).name();
    }

    @Test
    void categoryMatchesDdlVocabulary() {
        for (String ok : new String[]{"TRADE", "TRANSFER", "ACCOUNT", "ETC"}) {
            assertEquals(ok, SupportInputs.category(ok));
        }
        for (String bad : new String[]{null, "", "trade", "OTHER", "ORDER"}) {
            assertEquals("invalid_support_input", err(() -> SupportInputs.category(bad)), "거절: " + bad);
        }
    }

    @Test
    void titleCollapsesWhitespace() {
        assertEquals("체결이 안 됩니다", SupportInputs.title("  체결이   안   됩니다  "));
        assertEquals("A B", SupportInputs.title("A" + (char) 0x00A0 + "B"));
        // 줄바꿈이 든 제목은 한 줄로 접힌다. 제목은 목록에서 한 줄로 렌더된다
        assertEquals("앞 뒤", SupportInputs.title("앞\n뒤"));
    }

    @Test
    void titleRejectsEmptyAndOverlong() {
        for (String bad : new String[]{null, "", "   ", "\n\t"}) {
            assertEquals("invalid_support_input", err(() -> SupportInputs.title(bad)), "거절: [" + bad + "]");
        }
        // 공백류만으로 이뤄진 제목도 빈 제목이다. String.trim이 못 걷는 문자를 포함한다
        assertEquals("invalid_support_input", err(() -> SupportInputs.title("" + (char) 0x3000)));
        assertEquals("가".repeat(SupportInputs.MAX_TITLE),
                SupportInputs.title("가".repeat(SupportInputs.MAX_TITLE)));
        assertEquals("invalid_support_input",
                err(() -> SupportInputs.title("가".repeat(SupportInputs.MAX_TITLE + 1))));
    }

    @Test
    void bodyPreservesLineBreaks() {
        // 문의는 상황을 나열하는 글이라 줄 구분이 내용의 일부다. 접으면 시간 순서가 뭉친다
        String multi = "10시에 주문했습니다\n11시에도 미체결입니다";
        assertEquals(multi, SupportInputs.body(multi));
        // 양끝 공백은 자른다
        assertEquals("본문입니다", SupportInputs.body("  본문입니다  "));
    }

    @Test
    void bodyEnforcesLength() {
        assertEquals("invalid_support_input", err(() -> SupportInputs.body(null)));
        assertEquals("invalid_support_input", err(() -> SupportInputs.body("짧음")));
        assertTrue(SupportInputs.body("다섯자입니다").length() > 0);
        assertEquals("invalid_support_input",
                err(() -> SupportInputs.body("가".repeat(SupportInputs.MAX_BODY + 1))));
    }

    @Test
    void titleLengthCountsCodePoints() {
        // VARCHAR(120)은 문자 수 기준이다. UTF-16 단위로 세면 보조평면 문자가 절반만 들어간다
        String emoji = new String(Character.toChars(0x1F600)).repeat(SupportInputs.MAX_TITLE);
        assertEquals(SupportInputs.MAX_TITLE * 2, emoji.length());
        assertEquals(emoji, SupportInputs.title(emoji));
    }

    @Test
    void answerRequiredAndBounded() {
        assertEquals("확인 후 처리했습니다", SupportInputs.answer("  확인 후 처리했습니다  "));
        assertEquals("invalid_support_input", err(() -> SupportInputs.answer(null)));
        assertEquals("invalid_support_input", err(() -> SupportInputs.answer("   ")));
        assertEquals("invalid_support_input",
                err(() -> SupportInputs.answer("가".repeat(SupportInputs.MAX_ANSWER + 1))));
    }
}
