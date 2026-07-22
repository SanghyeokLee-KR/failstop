package kr.exchange.harness;

/**
 * 단일 불변식 판정 결과. ok는 violatedRows=0으로 기계 판정한다.
 */
public record InvariantResult(InvariantCheck check, boolean ok, long violatedRows) {

    static InvariantResult of(InvariantCheck check, long violatedRows) {
        return new InvariantResult(check, violatedRows == 0, violatedRows);
    }
}
