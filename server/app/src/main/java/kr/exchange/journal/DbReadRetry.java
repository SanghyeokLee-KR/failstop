package kr.exchange.journal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 트랜잭션 밖 읽기의 한 번 더 읽기(API 설계 §2-5-d 'DB가 흔들린 직후', S5 설계 4-6).
 *
 * 끊긴 연결은 풀이 버리므로 연결 끊김으로 끝난 읽기를 한 번 더 부르면 다른 연결로 DB 도달 여부를 다시 본다. DB 는 살아 있는데
 * 쓰던 연결만 끊긴 장면에서 리더가 503 을 내 쓰기 업스트림에서 빠지는 일을 줄이려는 것이다. 둘째마저 DB 에 닿지 못하면 첫
 * 예외를 올려 종전 응답(§2-5-c 의 503)이 그대로 나간다. 커넥션 획득 실패와 자원 부족은 다시 부르지 않는다. 연결 대기를 한 번
 * 더 쓸 뿐이고 절단 중 인증 필터의 응답 시점만 늦춘다.
 *
 * 부수 효과가 없는 읽기에만 쓴다. 쓰기(멱등 키 선점, append, 인계 행 넣기)는 커밋 여부를 모르므로 다시 부르지 않는다.
 */
public final class DbReadRetry {

    /** 인증 필터의 키 조회. */
    public static final String AUTH = "auth";
    /** 주문 생성·취소의 접수 전 읽기(계정 상태, 저장된 해시, 저널 존재, 가용 잔고, 대상 주문, 미체결 목록). */
    public static final String ORDER = "order";

    private static final List<String> SITES = List.of(AUTH, ORDER);

    private final Map<String, Counter> recovered = new HashMap<>();
    private final Map<String, Counter> unreachable = new HashMap<>();

    public DbReadRetry(MeterRegistry registry) {
        // 태그 조합을 기동 때 모두 등록한다. 프로메테우스 increase() 가 첫 증가를 센다(하네스 CI/CD §5-2).
        for (String site : SITES) {
            recovered.put(site, counter(registry, site, "recovered"));
            unreachable.put(site, counter(registry, site, "unreachable"));
        }
    }

    /** 계수를 내보내지 않는 판. 배선하지 않은 시험과 도구가 쓴다. */
    public static DbReadRetry untracked() {
        return new DbReadRetry(new SimpleMeterRegistry());
    }

    private static Counter counter(MeterRegistry registry, String site, String outcome) {
        return Counter.builder("exchange.db.read_retry")
                .tags("site", site, "outcome", outcome)
                .description("연결 끊김으로 끝난 트랜잭션 밖 읽기를 한 번 더 부른 수(recovered 닿음, unreachable 둘째도 닿지 못함)")
                .register(registry);
    }

    /**
     * 읽기를 부르고, 연결 끊김으로 끝나면 한 번 더 부른다. 둘째가 성공하면 그 값을, DB 에 닿지 못하면 첫 예외를 올린다. 둘째가
     * DB 에 닿았으나 다른 이유로 실패하면 그 예외를 올린다.
     */
    public <T> T read(String site, Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException first) {
            if (DbOutageErrors.kind(first) != DbOutageErrors.Kind.CONNECTION_LOST) {
                throw first;
            }
            T value;
            try {
                value = read.get();
            } catch (RuntimeException second) {
                if (DbOutageErrors.isOutage(second)) {
                    count(unreachable, site);
                    first.addSuppressed(second);
                    throw first;
                }
                throw second;
            }
            count(recovered, site);
            return value;
        }
    }

    /** 그 자리에서 한 번 더 읽어 닿은 수. */
    public double recovered(String site) {
        Counter c = recovered.get(site);
        return c == null ? 0 : c.count();
    }

    /** 그 자리에서 한 번 더 읽었는데도 DB 에 닿지 못한 수. */
    public double unreachable(String site) {
        Counter c = unreachable.get(site);
        return c == null ? 0 : c.count();
    }

    private static void count(Map<String, Counter> counters, String site) {
        Counter c = counters.get(site);
        if (c != null) {
            c.increment();
        }
    }
}
