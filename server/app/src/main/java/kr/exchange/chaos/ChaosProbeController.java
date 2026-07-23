package kr.exchange.chaos;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * E8 게이트웨이 평면 프로브 엔드포인트. chaos-monkey 프로파일에서만 매핑된다(/api·/admin 필터 무관 경로).
 * assault 중 CB가 open되면 CallNotPermittedException으로 즉시 503(circuit_open)을 낸다. CB open 전
 * downstream 실패·지연 구간은 503(downstream_error)으로 신호한다. 정상 구간은 200이다. 이 평면 저하는
 * 매칭 코어와 독립이라 통합 무결성(C01~C08)에 영향을 주지 않는다.
 */
@RestController
@Profile("chaos-monkey")
public class ChaosProbeController {

    private final ChaosProbeGateway gateway;

    public ChaosProbeController(ChaosProbeGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/internal/chaos/probe")
    public ResponseEntity<Map<String, Object>> probe() {
        try {
            return ResponseEntity.ok(Map.of("status", gateway.call()));
        } catch (CallNotPermittedException e) {
            return ResponseEntity.status(503).body(Map.of("status", "circuit_open"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body(Map.of("status", "downstream_error"));
        }
    }
}
