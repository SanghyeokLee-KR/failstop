package kr.exchange.api.common;

/**
 * 수용 제한 상한 배분(API 설계 §2-5-a). 쓰기·조회·재조회의 합이 풀보다 작아야 면제 경로와
 * 백그라운드가 굶지 않고, 게이트된 요청이 커넥션을 기다리지 않는다. 배분 근거는 그 절에 있다.
 */
public record AdmissionLimits(int write, int query, int requery, int requestPoolSize) {

    private static final double WRITE_POOL_SHARE = 0.40;
    private static final double QUERY_POOL_SHARE = 0.40;
    private static final double REQUERY_POOL_SHARE = 0.10;
    private static final int MIN_LIMIT = 4;
    private static final int MIN_REQUERY_LIMIT = 2;

    /** 0 이하 설정은 풀 크기에서 유도한다. 카오스 스택은 풀을 환경변수로 덮으므로 유도가 곧 추종이다. */
    public static AdmissionLimits resolve(int requestPoolSize, int configuredWrite,
                                          int configuredQuery, int configuredRequery) {
        return new AdmissionLimits(
                share(configuredWrite, requestPoolSize, WRITE_POOL_SHARE, MIN_LIMIT),
                share(configuredQuery, requestPoolSize, QUERY_POOL_SHARE, MIN_LIMIT),
                share(configuredRequery, requestPoolSize, REQUERY_POOL_SHARE, MIN_REQUERY_LIMIT),
                requestPoolSize);
    }

    private static int share(int configured, int poolSize, double fraction, int floor) {
        if (configured > 0) {
            return configured;
        }
        return Math.max(floor, (int) Math.floor(poolSize * fraction));
    }

    /** 면제 경로·백그라운드 몫. 음수면 초소형 풀에서 하한이 지배한 것이다. */
    public int reserved() {
        return requestPoolSize - write - query - requery;
    }
}
