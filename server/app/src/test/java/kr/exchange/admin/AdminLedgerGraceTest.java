package kr.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/** 관리 요청 인증의 원장 유예. DB 가 닿지 않을 때 조회 요청만 최근 원장 확인으로 받는다 */
class AdminLedgerGraceTest {

    private static final String OPERATOR = "ops-kim";

    /** 원장의 응답. DOWN 이면 연결 획득 실패를 던진다 */
    private enum Ledger { LIVE, REVOKED, DOWN }

    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-09-27T14:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovingClock clock = new MovingClock();
    private Ledger ledger = Ledger.LIVE;
    private final AdminSessionService sessions = new AdminSessionService(
            mock(JdbcClient.class, RETURNS_DEEP_STUBS), new ObjectMapper(), jti -> switch (ledger) {
                case LIVE -> true;
                case REVOKED -> false;
                case DOWN -> throw new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection");
            }, clock);
    private final String bearer = "Bearer " + sessions.issue(OPERATOR, Duration.ofHours(8)).token();

    @Test
    void 조회_요청은_원장이_닿지_않아도_최근에_확인한_세션을_받는다() {
        assertEquals(OPERATOR, sessions.verify(bearer, OPERATOR, true));
        ledger = Ledger.DOWN;
        clock.advance(Duration.ofSeconds(30));

        assertEquals(OPERATOR, sessions.verify(bearer, OPERATOR, true));
    }

    @Test
    void 변경_요청은_원장이_닿지_않으면_503() {
        sessions.verify(bearer, OPERATOR, true);
        ledger = Ledger.DOWN;

        ApiException e = assertThrows(ApiException.class, () -> sessions.verify(bearer, OPERATOR, false));
        assertEquals(503, e.status());
    }

    @Test
    void 원장에서_확인한_적_없는_세션은_조회도_503() {
        ledger = Ledger.DOWN;

        assertEquals(503, assertThrows(ApiException.class, () -> sessions.verify(bearer, OPERATOR, true)).status());
    }

    @Test
    void 유예가_지나면_조회도_503() {
        sessions.verify(bearer, OPERATOR, true);
        ledger = Ledger.DOWN;
        clock.advance(AdminSessionService.LEDGER_GRACE.plusSeconds(1));

        assertEquals(503, assertThrows(ApiException.class, () -> sessions.verify(bearer, OPERATOR, true)).status());
    }

    @Test
    void 폐기된_세션은_마지막_확인을_지운다() {
        sessions.verify(bearer, OPERATOR, true);
        ledger = Ledger.REVOKED;
        assertEquals(401, assertThrows(ApiException.class, () -> sessions.verify(bearer, OPERATOR, true)).status());

        ledger = Ledger.DOWN;
        assertEquals(503, assertThrows(ApiException.class, () -> sessions.verify(bearer, OPERATOR, true)).status());
    }

    @Test
    void 서명과_mTLS_신원_대조는_원장_유예보다_먼저다() {
        sessions.verify(bearer, OPERATOR, true);
        ledger = Ledger.DOWN;

        assertEquals(401, assertThrows(ApiException.class, () -> sessions.verify(bearer, "ops-lee", true)).status());
        assertEquals(401, assertThrows(ApiException.class, () -> sessions.verify(bearer + "x", OPERATOR, true)).status());
    }

    @Test
    void DB_장애가_아닌_실패는_그대로_던진다() {
        AdminSessionService broken = new AdminSessionService(mock(JdbcClient.class, RETURNS_DEEP_STUBS),
                new ObjectMapper(), jti -> {
                    throw new IllegalStateException("원장 질의 결함");
                }, clock);
        String token = "Bearer " + broken.issue(OPERATOR, Duration.ofHours(1)).token();

        assertThrows(IllegalStateException.class, () -> broken.verify(token, OPERATOR, true));
    }

    @Test
    void 운영자_조회도_같은_유예를_쓴다() {
        AdminOperatorDirectory.Operator kim = new AdminOperatorDirectory.Operator(
                1, OPERATOR, "김운영", "ACTIVE", EnumSet.of(AdminRole.SUPER));
        boolean[] down = {false};
        AdminOperatorDirectory operators = new AdminOperatorDirectory(null, clock) {
            @Override
            public Optional<Operator> find(String operatorId) {
                if (down[0]) {
                    throw new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection");
                }
                return Optional.of(kim);
            }
        };
        operators.findForRequest(OPERATOR, true);
        down[0] = true;

        assertSame(kim, operators.findForRequest(OPERATOR, true).orElseThrow());
        assertEquals(503, assertThrows(ApiException.class, () -> operators.findForRequest(OPERATOR, false)).status());
        assertEquals(503, assertThrows(ApiException.class, () -> operators.findForRequest("ops-lee", true)).status());
    }
}
