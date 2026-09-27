package kr.exchange.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 관리 요청 인증이 DB 원장(세션 폐기·만료, 운영자 상태·역할)에서 마지막으로 확인한 값이다. DB 가 닿지 않는 동안 조회
 * 요청이 유예 시간 안의 값을 쓴다. 폐기와 비활성화도 DB 쓰기라 DB 가 닿지 않는 동안에는 원장이 바뀌지 않는다. 그래서 이
 * 값이 원장과 어긋날 수 있는 것은 장애 직전 한 요청 간격 안의 변경뿐이다.
 */
final class LedgerGrace<T> {

    /** 이보다 많이 쌓이면 유예가 지난 키를 지운다. 관리 세션과 운영자는 이만큼 동시에 쓰이지 않는다 */
    private static final int PRUNE_AT = 256;

    private record Seen<T>(T value, Instant at) {
    }

    private final Clock clock;
    private final Duration grace;
    private final ConcurrentHashMap<String, Seen<T>> seen = new ConcurrentHashMap<>();

    LedgerGrace(Clock clock, Duration grace) {
        this.clock = clock;
        this.grace = grace;
    }

    void confirm(String key, T value) {
        Instant now = clock.instant();
        seen.put(key, new Seen<>(value, now));
        if (seen.size() > PRUNE_AT) {
            Instant cutoff = now.minus(grace);
            seen.values().removeIf(s -> s.at().isBefore(cutoff));
        }
    }

    void forget(String key) {
        seen.remove(key);
    }

    /** 유예 시간 안에 원장에서 확인한 값. 확인한 적이 없거나 유예가 지났으면 비어 있다 */
    Optional<T> recent(String key) {
        Seen<T> s = seen.get(key);
        if (s == null || s.at().plus(grace).isBefore(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(s.value());
    }
}
