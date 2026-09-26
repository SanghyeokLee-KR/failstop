package kr.exchange.harness;

import java.util.List;

/**
 * 하네스 SQL 두 벌(summary.sql, invariants.sql) 판정 코드의 시험 공용 목록(하네스 검증설계 §4-1 '판정 어휘'). 시험마다 코드를
 * 따로 적으면 새 N/A 코드가 생길 때 한 곳만 고쳐 다른 시험이 거짓으로 실패하거나 거짓으로 통과한다(S7 설계 11절 지적 8).
 */
public final class HarnessSqlCodes {

    /** summary.sql 이 N/A 를 낼 수 있는 코드. 이 밖의 N/A 는 형식 오류다. C30 은 ADR 0034 S7 이 더했다. */
    public static final List<String> NOT_APPLICABLE = List.of("C29", "C30", "C31");

    /** V48 객체 다섯을 읽는 코드. V47 이하 스키마에서 N/A, 다섯이 일부만 있으면 FAIL 이다. */
    public static final List<String> V48_OBJECTS = List.of("C29", "C30");

    /** V50 객체 둘을 읽는 코드. V49 이하 스키마에서 N/A 다. */
    public static final List<String> V50_OBJECTS = List.of("C31");

    private HarnessSqlCodes() {
    }
}
