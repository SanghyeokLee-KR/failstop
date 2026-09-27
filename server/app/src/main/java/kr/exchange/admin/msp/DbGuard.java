package kr.exchange.admin.msp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * DB 에 기대는 관제 칸의 실패를 그 칸 안에 가둔다(API 설계 §6-F). 주 DB 가 멈추거나 바뀌는 동안 관리 JVM 의 DB 읽기는 커넥션
 * 대기(3초) 뒤 실패한다. 그 실패로 한 판이나 흐름 조회 전체가 500 이 되면 화면은 마지막 값에 멈추고, 정작 Prometheus 원천의
 * 칸(노드, 복제, 거래)이 보여 줄 DB 전환이 보이지 않는다(2026-09-27 시연 11회차). 그래서 그 칸만 UNAVAILABLE
 * (database_unavailable)로 낸다. 한 번 실패하면 잠깐은 DB 를 다시 부르지 않고 곧바로 같은 상태를 내어, 한 요청이 칸마다 3초씩
 * 기다려 느려지지 않게 한다. DB 가 아닌 예외(코드 결함)는 가리지 않고 그대로 던진다.
 */
final class DbGuard {

    static final String REASON = "database_unavailable";

    private static final Logger log = LoggerFactory.getLogger(DbGuard.class);

    private final Clock clock;
    private final Duration cooldown;
    private volatile Instant failedAt;

    DbGuard(Clock clock, Duration cooldown) {
        this.clock = clock;
        this.cooldown = cooldown;
    }

    /** DB 에 기대는 칸 하나. DB 읽기가 실패하면 그 칸만 UNAVAILABLE 이다 */
    Map<String, Object> section(Supplier<Map<String, Object>> body) {
        return call(body, () -> MspSections.unavailable(REASON));
    }

    /** DB 에 기대는 값 하나. DB 읽기가 실패했거나 실패 직후면 fallback 을 낸다 */
    <T> T call(Supplier<T> body, Supplier<T> fallback) {
        Instant last = failedAt;
        if (last != null && clock.instant().isBefore(last.plus(cooldown))) {
            return fallback.get();
        }
        try {
            T value = body.get();
            failedAt = null;
            return value;
        } catch (RuntimeException e) {
            if (!database(e)) {
                throw e;
            }
            // 실패로 넘어가는 순간만 경고로 남긴다. 멈춘 동안 요청마다 같은 경고가 쌓이지 않게 한다
            if (last == null) {
                log.warn("관제 칸의 DB 읽기가 실패해 그 칸만 {} 상태로 냅니다: {}", REASON, e.toString());
            } else {
                log.debug("관제 칸의 DB 읽기가 아직 실패합니다: {}", e.toString());
            }
            failedAt = clock.instant();
            return fallback.get();
        }
    }

    /** 예외나 그 원인 가운데 DB 접근 실패가 있는가 */
    static boolean database(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof DataAccessException || c instanceof TransactionException || c instanceof SQLException) {
                return true;
            }
        }
        return false;
    }
}
