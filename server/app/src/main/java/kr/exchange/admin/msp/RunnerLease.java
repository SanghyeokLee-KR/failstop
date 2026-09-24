package kr.exchange.admin.msp;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.UUID;

/**
 * 여러 관리 JVM 가운데 한 곳에서만 AI 판단·관찰 주기를 돌리게 하는 임대(설계 7-1, DB 설계 §10.5). 매칭 리더와
 * 무관하다. 커넥션을 붙잡는 세션 락 대신 DB 시계의 만료 시각을 쓴다. 학교 관리 JVM 의 DB 풀(4)에서 커넥션 하나를
 * 영구히 잡지 않기 위해서다.
 */
public class RunnerLease {

    // 관찰 주기(15초)마다 갱신하므로 여러 번 놓쳐도 넘어가지 않고, 멈춘 JVM 의 자리는 이 안에 넘어간다.
    static final Duration LEASE = Duration.ofSeconds(90);

    private final JdbcClient jdbc;
    private final String holder;

    public RunnerLease(JdbcClient jdbc) {
        this(jdbc, UUID.randomUUID().toString());
    }

    RunnerLease(JdbcClient jdbc, String holder) {
        this.jdbc = jdbc;
        this.holder = holder;
    }

    /** 자기가 보유자면 갱신하고, 비었거나 만료됐으면 가져간다. 다른 JVM 이 쥐고 있으면 거짓이다. */
    public boolean claim() {
        return jdbc.sql("""
                        UPDATE ops_runner_lease SET holder = ?, expires_at = now() + make_interval(secs => ?),
                               renewed_at = now()
                        WHERE name = 'ai_loop' AND (holder = ? OR holder IS NULL OR expires_at < now())
                        """)
                .params(holder, (double) LEASE.toSeconds(), holder)
                .update() == 1;
    }

    /** 멈출 때 놓는다. 놓지 못하고 죽으면 만료 뒤 다른 JVM 이 가져간다. */
    public void release() {
        jdbc.sql("UPDATE ops_runner_lease SET holder = NULL, expires_at = NULL WHERE name = 'ai_loop' AND holder = ?")
                .param(holder)
                .update();
    }

    String holder() {
        return holder;
    }
}
