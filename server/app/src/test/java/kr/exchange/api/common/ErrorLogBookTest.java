package kr.exchange.api.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 오류 로그 묶음(API 설계 §6-F '오류 로그'). 녹화한 실제 앱 로그(2026-09-13 리스 기아 실험)를 넣어 비슷한 줄이 한 묶음이
 * 되고 건수·처음·마지막 시각·대표 문장이 맞는지, 창과 상한과 가리기가 도는지 본다.
 */
class ErrorLogBookTest {

    private static final Instant T0 = Instant.parse("2026-09-28T03:00:00Z");
    // 스프링 기본 로그 줄: 컨테이너 이름, 도커 시각, 로그 시각, 등급, pid, 앱 이름, 스레드, 로거, 문장.
    private static final Pattern LINE = Pattern.compile(
            "^(\\S+) \\S+ (\\S+)\\s+(TRACE|DEBUG|INFO|WARN|ERROR) \\d+ --- \\[[^\\]]*\\] \\[[^\\]]*\\] (\\S+)\\s*: (.*)$");
    private static final Pattern CAUSED = Pattern.compile("^\\S+ \\S+ Caused by: ([\\w.$]+): (.*)$");

    private record Line(String node, Instant at, String level, String logger, String message, String cause,
                        String causeMessage) {
    }

    /** 녹화 로그를 줄 단위 사건으로 읽는다. 'Caused by:' 줄은 같은 노드의 직전 사건에 원인 예외로 붙인다. */
    private static List<Line> recorded() throws IOException {
        Path file = Path.of(System.getProperty("errorlog.recorded"));
        List<Line> out = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher m = LINE.matcher(raw);
            if (m.matches()) {
                out.add(new Line(m.group(1), Instant.parse(m.group(2)), m.group(3), m.group(4), m.group(5), null, null));
                continue;
            }
            Matcher c = CAUSED.matcher(raw);
            if (c.matches() && !out.isEmpty()) {
                Line last = out.remove(out.size() - 1);
                out.add(new Line(last.node(), last.at(), last.level(), last.logger(), last.message(), c.group(1),
                        c.group(2)));
            }
        }
        return out;
    }

    private static Clock at(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groups(ErrorLogBook book) {
        return (List<Map<String, Object>>) book.view().get("groups");
    }

    @Test
    void 녹화한_리스_기아_로그는_비슷한_WARN_을_한_묶음으로_세고_INFO_는_버린다() throws IOException {
        List<Line> lines = recorded();
        assertEquals(9, lines.size(), "녹화 로그의 로그 줄 수(INFO 6, WARN 3)");
        ErrorLogBook book = new ErrorLogBook(at(Instant.parse("2026-09-13T04:30:00Z")));
        for (Line l : lines) {
            if (l.level().equals("WARN") || l.level().equals("ERROR")) {
                // 녹화에는 채운 문장만 있어 그 문장을 틀로 넣는다. 인자를 이어 붙여 찍는 로그와 같은 조건이다.
                book.record(l.level(), l.logger(), l.message(), l.cause(), l.causeMessage(), null, l.message(), l.at());
            }
        }
        List<Map<String, Object>> rows = groups(book);

        assertEquals(2, rows.size());
        Map<String, Object> fencing = rows.stream().filter(r -> "org.postgresql.util.PSQLException".equals(r.get("exception")))
                .findFirst().orElseThrow();
        assertEquals(2L, fencing.get("count"));
        assertEquals("2026-09-13T04:24:38.304Z", fencing.get("first_seen"));
        assertEquals("2026-09-13T04:24:38.603Z", fencing.get("last_seen"));
        assertEquals("WARN", fencing.get("level"));
        assertEquals("kr.exchange.engine.LeaderLeaseService", fencing.get("logger"));
        // 틀은 가린 뒤 시각과 숫자를 지워 두 줄이 한 묶음이 된다. 날짜와 시각은 긴 숫자열로 가리지 않는다. 두 줄은 299ms 차이라
        // 대표 문장은 첫 줄 그대로다(같은 묶음의 대표 문장은 1초에 한 번만 바꾼다).
        assertTrue(((String) fencing.get("template")).contains("lease_until=#-#-# #:#:#.#+#"));
        assertTrue(((String) fencing.get("message")).contains("now=2026-09-13 04:24:37.415845+00"),
                (String) fencing.get("message"));
        assertTrue(((String) fencing.get("exception_message")).startsWith("ERROR: fencing 위반: 리스 만료"));

        Map<String, Object> lost = rows.stream().filter(r -> r.get("exception") == null).findFirst().orElseThrow();
        assertEquals(1L, lost.get("count"));
        // 보유자 UUID 는 내보낼 때 가린다.
        assertTrue(((String) lost.get("message")).contains("holder=[id]"), (String) lost.get("message"));
        assertFalse(((String) lost.get("message")).contains("31575d86"));
    }

    @Test
    void 녹화한_묶음은_15분이_지나면_창에서_빠진다() throws IOException {
        ErrorLogBook book = new ErrorLogBook(at(Instant.parse("2026-09-13T04:40:00Z")));
        for (Line l : recorded()) {
            if (l.level().equals("WARN")) {
                book.record(l.level(), l.logger(), l.message(), l.cause(), l.causeMessage(), null, l.message(), l.at());
            }
        }
        assertTrue(groups(book).isEmpty());
    }

    @Test
    void 자리표시자_틀이_같으면_인자가_달라도_한_묶음이고_예외_원인이_다르면_다른_묶음이다() {
        ErrorLogBook book = new ErrorLogBook(at(T0.plusSeconds(60)));
        book.record("ERROR", "kr.exchange.api.order.OrderGateway", "주문 접수 실패: market={}", "java.net.ConnectException",
                "Connection refused", "OrderGateway.submit", "주문 접수 실패: market=KRW-BTC", T0);
        book.record("ERROR", "kr.exchange.api.order.OrderGateway", "주문 접수 실패: market={}", "java.net.ConnectException",
                "Connection refused", "OrderGateway.submit", "주문 접수 실패: market=KRW-ETH", T0.plusSeconds(5));
        book.record("ERROR", "kr.exchange.api.order.OrderGateway", "주문 접수 실패: market={}",
                "java.sql.SQLTransientConnectionException", "timeout", "OrderGateway.submit", "주문 접수 실패", T0);

        List<Map<String, Object>> rows = groups(book);
        assertEquals(2, rows.size());
        Map<String, Object> refused = rows.stream().filter(r -> "java.net.ConnectException".equals(r.get("exception")))
                .findFirst().orElseThrow();
        assertEquals(2L, refused.get("count"));
        assertEquals("주문 접수 실패: market=KRW-ETH", refused.get("message"));
        assertEquals(16, ((String) refused.get("id")).length());
    }

    @Test
    void 창_건수는_최근_15분이고_누적은_기동_뒤_전부다() {
        ErrorLogBook book = new ErrorLogBook(at(T0.plusSeconds(20 * 60)));
        for (int minute = 0; minute <= 20; minute++) {
            book.record("WARN", "kr.exchange.x.Y", "지연 {}ms", null, null, null, "지연", T0.plusSeconds(minute * 60L));
        }
        Map<String, Object> row = groups(book).get(0);
        // 20분(지금)부터 6분까지 15개 분 칸이다.
        assertEquals(15L, row.get("count"));
        assertEquals(21L, row.get("total"));
    }

    @Test
    void 묶음이_상한을_넘으면_가장_오래_조용한_묶음을_버리고_센다() {
        ErrorLogBook book = new ErrorLogBook(at(T0.plusSeconds(600)));
        for (int i = 0; i < ErrorLogBook.MAX_GROUPS; i++) {
            book.record("WARN", "kr.exchange.x.Logger" + (char) ('A' + i % 26) + (char) ('a' + i / 26), "틀", null,
                    null, null, "문장", T0.plusSeconds(i));
        }
        book.record("WARN", "kr.exchange.x.Newest", "틀", null, null, null, "문장", T0.plusSeconds(500));

        assertEquals(ErrorLogBook.MAX_GROUPS, groups(book).size());
        assertEquals(1L, book.view().get("overflow"));
        assertTrue(groups(book).stream().noneMatch(r -> "kr.exchange.x.LoggerAa".equals(r.get("logger"))));
    }

    @Test
    void 내보내는_문장은_주소와_자격_증명_모양을_가린다() {
        String text = ErrorLogBook.redact("호출 실패 http://10.0.1.5:9090/api user=ops@example.com "
                + "Authorization: Bearer abc.def password=hunter2 from 172.31.2.86:5432 card 1234 5678 9012 3456");
        assertFalse(text.contains("10.0.1.5"));
        assertFalse(text.contains("ops@example.com"));
        assertFalse(text.contains("abc.def"));
        assertFalse(text.contains("hunter2"));
        assertFalse(text.contains("172.31.2.86"));
        assertFalse(text.contains("9012"));
        assertTrue(text.contains("[주소]") && text.contains("[이메일]") && text.contains("[비밀]"));
    }

    @Test
    void 가리기는_숫자를_지우고_자르기_전에_해서_틀과_문장과_예외_문장_어디에도_남지_않는다() {
        // 숫자를 먼저 # 로 바꾸면 JWT(eyJhbGciOiJIUzI#NiJ#)와 이메일(kim##@naver.com)이 가리기 모양과 맞지 않았다.
        String secrets = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
                + " 사용자 kim12@naver.com 본문 {\"password\":\"pwSecretAlpha\"} client_secret=clientSecretBeta"
                + " db_password=hunterGamma 키 wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
                + " 주소 fe80::1ff:fe23:4567:890a 와 2001:db8::8a2e:370:7334"
                + " 호스트 ip-172-31-2-86 과 ip-172-31-2-86.ap-northeast-2.compute.internal";
        ErrorLogBook book = new ErrorLogBook(at(T0.plusSeconds(60)));
        // 인자를 이어 붙여 찍은 로그라 틀에도 같은 값이 든다.
        book.record("ERROR", "kr.exchange.api.auth.ApiAuthFilter", "인증 실패: " + secrets,
                "java.lang.IllegalStateException", "원인: " + secrets, null, "인증 실패: " + secrets, T0);
        Map<String, Object> row = groups(book).get(0);

        List<String> leaks = List.of("eyJ", "kim", "naver", "pwSecretAlpha", "clientSecretBeta", "hunterGamma",
                "wJalrXUtnFEMI", "EXAMPLEKEY", "fe80", "fe23", "::", "8a2e", "ip-", "ap-northeast", "compute.internal");
        for (String field : List.of("template", "message", "exception_message")) {
            String value = (String) row.get(field);
            for (String leak : leaks) {
                assertFalse(value.contains(leak), field + " 에 " + leak + " 가 남았다: " + value);
            }
        }
        // 이름은 남기고 값만 가린다.
        assertTrue(((String) row.get("message")).contains("\"password\":[비밀]"), (String) row.get("message"));
        assertTrue(((String) row.get("message")).contains("client_secret=[비밀]"));
        assertTrue(((String) row.get("message")).contains("db_password=[비밀]"));
    }

    @Test
    void 자르는_자리에_걸친_비밀도_가린_뒤에_잘라_남지_않는다() {
        // 240자에서 먼저 자르면 끝의 JWT 가 두 조각이 되지 못해 가리기를 빠져나갔다. 예외 문장의 이메일도 200자에서 잘렸다.
        String filler = "log ".repeat(57);
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJraW0ifQ.c2lnbmF0dXJlLXZhbHVlLXNlY3JldA";
        ErrorLogBook book = new ErrorLogBook(at(T0.plusSeconds(60)));
        book.record("WARN", "kr.exchange.x.Y", filler + jwt, null, filler.substring(0, 190) + " kim12@naver.com",
                null, filler + jwt, T0);
        Map<String, Object> row = groups(book).get(0);

        for (String field : List.of("template", "message", "exception_message")) {
            String value = (String) row.get(field);
            assertFalse(value.contains("eyJ"), field + ": " + value);
            assertFalse(value.contains("kim"), field + ": " + value);
        }
        assertTrue(((String) row.get("message")).endsWith("[토큰]"));
        assertTrue(((String) row.get("message")).length() <= ErrorLogBook.MAX_MESSAGE);
        assertTrue(((String) row.get("exception_message")).length() <= ErrorLogBook.MAX_EXCEPTION_MESSAGE);

        // 가리기 전 읽는 길이에서 자를 때는 끝에 걸친 낱말을 통째로 버린다.
        String scanned = ErrorLogBook.scanPrefix("a ".repeat(1020) + jwt + " 뒤");
        assertTrue(scanned.endsWith(ErrorLogBook.CUT), scanned.substring(scanned.length() - 10));
        assertFalse(scanned.contains("eyJ"));
    }

    @Test
    void 묶음이_가득_차면_WARN_을_먼저_버리고_ERROR_는_WARN_에_밀려나지_않는다() {
        ErrorLogBook book = new ErrorLogBook(at(T0.plusSeconds(900)));
        for (int i = 0; i < ErrorLogBook.MAX_GROUPS - 1; i++) {
            book.record("ERROR", "kr.exchange.x.Error" + i, "틀", null, null, null, "문장", T0.plusSeconds(100 + i));
        }
        book.record("WARN", "kr.exchange.x.Warn", "틀", null, null, null, "문장", T0.plusSeconds(400));

        // ERROR 가 더 오래 조용해도 WARN 을 먼저 버린다.
        book.record("ERROR", "kr.exchange.x.NewError", "틀", null, null, null, "문장", T0.plusSeconds(500));
        assertEquals(List.of(), loggers(book, "WARN"));
        assertTrue(loggers(book, "ERROR").contains("kr.exchange.x.NewError"));
        assertTrue(loggers(book, "ERROR").contains("kr.exchange.x.Error0"));
        assertEquals(1L, book.view().get("overflow"));

        // 모두 ERROR 이면 새 WARN 은 받지 않는다.
        book.record("WARN", "kr.exchange.x.LateWarn", "틀", null, null, null, "문장", T0.plusSeconds(510));
        assertEquals(List.of(), loggers(book, "WARN"));
        assertEquals(ErrorLogBook.MAX_GROUPS, groups(book).size());
        assertEquals(2L, book.view().get("overflow"));

        // 새 ERROR 는 가장 오래 조용한 ERROR 를 밀어낸다.
        book.record("ERROR", "kr.exchange.x.Newer", "틀", null, null, null, "문장", T0.plusSeconds(520));
        assertTrue(loggers(book, "ERROR").contains("kr.exchange.x.Newer"));
        assertFalse(loggers(book, "ERROR").contains("kr.exchange.x.Error0"));
        assertEquals(3L, book.view().get("overflow"));
    }

    @Test
    void 끝점_응답은_상한_안이고_넘치면_ERROR_를_먼저_싣고_나머지는_센다() throws Exception {
        // 한글 문장이 긴 묶음이 200개 차면 약 450 KB 로 수집기의 응답 상한(256 KiB)을 넘었다.
        String korean = "가나다라마바사아자차카타파하".repeat(40);
        ErrorLogBook book = new ErrorLogBook(at(T0.plusSeconds(60)));
        for (int i = 0; i < ErrorLogBook.MAX_GROUPS; i++) {
            book.record(i % 4 == 0 ? "ERROR" : "WARN", "kr.exchange.load.Logger" + i, korean,
                    "java.lang.IllegalStateException", korean, "Load.run", korean, T0.plusSeconds(i % 60));
        }
        Map<String, Object> view = book.view();
        byte[] json = new ObjectMapper().writeValueAsBytes(view);
        List<Map<String, Object>> rows = groups(book);

        assertTrue(json.length <= ErrorLogBook.MAX_VIEW_BYTES + 1024, "응답 " + json.length + "바이트");
        assertTrue(json.length < 256 * 1024);
        assertEquals(50, rows.stream().filter(r -> "ERROR".equals(r.get("level"))).count(), "ERROR 는 모두 싣는다");
        assertEquals(ErrorLogBook.MAX_GROUPS - rows.size(), view.get("omitted"));
        assertTrue((Integer) view.get("omitted") > 0);
        for (Map<String, Object> r : rows) {
            assertTrue(((String) r.get("message")).getBytes(StandardCharsets.UTF_8).length
                    <= ErrorLogBook.MAX_MESSAGE_BYTES);
            assertTrue(((String) r.get("template")).getBytes(StandardCharsets.UTF_8).length
                    <= ErrorLogBook.MAX_TEMPLATE_BYTES);
            assertTrue(((String) r.get("exception_message")).getBytes(StandardCharsets.UTF_8).length
                    <= ErrorLogBook.MAX_EXCEPTION_MESSAGE_BYTES);
        }
    }

    private static List<String> loggers(ErrorLogBook book, String level) {
        return groups(book).stream().filter(r -> level.equals(r.get("level"))).map(r -> (String) r.get("logger"))
                .toList();
    }

    @Test
    void 로그백_사건에서_원인_예외와_첫_앱_프레임과_틀을_읽고_INFO_는_무시한다() {
        LoggerContext context = new LoggerContext();
        ErrorLogBook book = new ErrorLogBook(Clock.systemUTC());
        book.setContext(context);
        book.start();
        Logger logger = context.getLogger("kr.exchange.api.order.OrderGateway");
        logger.addAppender(book);

        logger.info("정상 {}", 1);
        logger.warn("접수 재시도 {}", 3, new IllegalStateException("감쌈", new ConnectException("Connection refused")));

        List<Map<String, Object>> rows = groups(book);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("WARN", row.get("level"));
        assertEquals("접수 재시도 {}", row.get("template"));
        assertEquals("java.net.ConnectException", row.get("exception"));
        assertEquals("Connection refused", row.get("exception_message"));
        assertEquals("ErrorLogBookTest.로그백_사건에서_원인_예외와_첫_앱_프레임과_틀을_읽고_INFO_는_무시한다", row.get("frame"));
        assertEquals("접수 재시도 3", row.get("message"));
    }
}
