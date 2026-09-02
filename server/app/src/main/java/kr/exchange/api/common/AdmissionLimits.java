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

    /**
     * 0 이하 설정은 풀 크기에서 유도한다. 카오스 스택은 풀을 환경변수로 덮으므로 유도가 곧 추종이다.
     *
     * 명시 설정은 풀과 대조한다. 유도 경로는 배분율이 합을 풀 아래로 묶어 주지만 명시값에는
     * 그런 보장이 없다. 종전에는 풀 16 에 order-in-flight=30 을 줘도 기동이 성공하고 불변식만
     * 조용히 깨졌다. 그러면 게이트를 통과한 요청이 커넥션을 기다리게 되고, 무대기 백프레셔라는
     * 성질 자체가 사라진다. 노드를 죽인 것이 커넥션이 아니라 커넥션을 기다리던 스레드였으므로
     * 이 성질이 이 게이트의 존재 이유다. 어겨지면 기동을 멈춘다.
     */
    public static AdmissionLimits resolve(int requestPoolSize, int configuredWrite,
                                          int configuredQuery, int configuredRequery) {
        AdmissionLimits limits = new AdmissionLimits(
                share(configuredWrite, requestPoolSize, WRITE_POOL_SHARE, MIN_LIMIT),
                share(configuredQuery, requestPoolSize, QUERY_POOL_SHARE, MIN_LIMIT),
                share(configuredRequery, requestPoolSize, REQUERY_POOL_SHARE, MIN_REQUERY_LIMIT),
                requestPoolSize);
        boolean explicit = configuredWrite > 0 || configuredQuery > 0 || configuredRequery > 0;
        /* 유도 경로는 검사하지 않는다. 초소형 풀에서 하한이 지배해 예비분이 0 이하가 되는 것은
           이미 알려진 상태이고(reserved 주석), 카오스 스택이 풀을 줄여 도는 구간이 있다.
           여기서 막으면 이번 변경의 대상이 아닌 경로의 기동을 깬다. */
        if (explicit && limits.reserved() <= 0) {
            throw new IllegalStateException(
                    "수용 상한 합이 요청 풀을 잠식한다. 쓰기 " + limits.write()
                            + " / 조회 " + limits.query() + " / 재조회 " + limits.requery()
                            + " 합 " + (limits.write() + limits.query() + limits.requery())
                            + ", 요청 풀 " + requestPoolSize + ", 예비 " + limits.reserved()
                            + ". exchange.admission.{order,query,requery}-in-flight 를 줄이거나"
                            + " spring.datasource.hikari.maximum-pool-size 를 키워라."
                            + " 예비분은 면제 경로와 백그라운드 몫이고 0 이하면 게이트된 요청이"
                            + " 커넥션을 기다린다(API 설계 2-5-a).");
        }
        return limits;
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
