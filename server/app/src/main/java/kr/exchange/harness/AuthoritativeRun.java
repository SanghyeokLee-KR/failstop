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

    // 방어 복사 겸 불변화. null 리스트·null 원소는 List.copyOf가 거른다.
    public AuthoritativeRun {
        results = List.copyOf(results);
    }

    public boolean pass() {
        // 빈 결과는 공허 PASS다. 체크가 하나도 안 돈 run을 통과로 보고하지 않는다.
        return !results.isEmpty() && results.stream().allMatch(InvariantResult::ok);
    }

    public boolean criticalClean() {
        return results.stream()
                .filter(r -> r.check().severity() == InvariantCheck.Severity.CRITICAL)
                .allMatch(InvariantResult::ok);
    }

    public long totalViolations() {
        // 음수 위반 수는 없어야 하나, 0으로 클램프해 부호 상쇄로 위반을 가리지 않는다.
        return results.stream()
                .mapToLong(r -> Math.max(0, r.violatedRows()))
                .sum();
    }
}
