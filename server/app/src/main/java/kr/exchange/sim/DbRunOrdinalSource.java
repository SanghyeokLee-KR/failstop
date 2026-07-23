package kr.exchange.sim;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link RunOrdinalSource}를 DB 시퀀스 sim_run_ordinal로 구현한다(V11). 시퀀스는 비트랜잭션이라
 * 롤백에서 되감기지 않고 크래시에서도 앞으로만 튀므로 재발급이 없다. 엔진 기원 쓰기·리더 리스·원장
 * 게시와 무관한 봇 클라이언트측 채번이라 journal_counters와 분리한다.
 */
public final class DbRunOrdinalSource implements RunOrdinalSource {

    private final JdbcClient jdbc;

    public DbRunOrdinalSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long next() {
        return jdbc.sql("SELECT nextval('sim_run_ordinal')").query(Long.class).single();
    }
}
