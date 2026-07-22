package kr.exchange.harness;

import java.util.List;

/**
 * 권위 run 결과 묶음(실험 판정 프로토콜의 사전·사후 run 산출물).
 *
 * verdict PASS = 평가한 전 체크 ok. CRITICAL 위반이 하나라도 있으면 FAIL.
 * runId·window·watermark는 invariant_checks.details와 실험 리포트의 조인 키다.
 */
public record AuthoritativeRun(
        String runId,
        String window,
        long commandWatermark,
        long eventWatermark,
        List<InvariantResult> results) {

    public boolean pass() {
        return results.stream().allMatch(InvariantResult::ok);
    }

    public boolean criticalClean() {
        return results.stream()
                .filter(r -> r.check().severity() == InvariantCheck.Severity.CRITICAL)
                .allMatch(InvariantResult::ok);
    }

    public long totalViolations() {
        return results.stream().mapToLong(InvariantResult::violatedRows).sum();
    }
}
