package kr.exchange.api.system;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.ClientIp;
import kr.exchange.api.common.SlidingWindowThrottle;
import kr.exchange.engine.WriteAvailability;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 거래 상태(API 설계 §4-A-7). 노드 메모리의 쓰기 가능 상태만 읽고 DB 에 닿지 않는 무인증 조회다.
 *
 * 탐지기의 SUSPENDED 와 UNREACHABLE 은 공개로는 모두 SUSPENDED 이고 reason 으로 가른다. 노드·DB 이름과 주소, 복제 지연은 싣지
 * 않는다(관리자 설계 §2-3 공개 범위). PublicMarketPaths 에 넣지 않고 공지 조회처럼 자기 스로틀을 든다. 수용 제한 버킷에도 넣지
 * 않는다(AdmissionFilter). DB 를 쓰지 않는 경로를 DB 연결 수 상한으로 막을 이유가 없다.
 */
@RestController
public class StatusController {

    // 화면이 5초마다 읽고 쓰기 거절과 재연결 때 더 읽는다. 탭 여럿과 여유를 둔 상한이다.
    private static final int POLL_AFTER_SECONDS = 5;

    private final SlidingWindowThrottle throttle = new SlidingWindowThrottle(120, 60_000, "status");
    private final WriteAvailability availability;

    public StatusController(WriteAvailability availability) {
        this.availability = availability;
    }

    @GetMapping("/api/v1/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        throttle.check(ClientIp.of(request));
        WriteAvailability.View view = availability.view();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("trading_suspended", !view.active());
        body.put("trading_state", view.active() ? "ACTIVE" : "SUSPENDED");
        body.put("reason", view.reason());
        body.put("since", view.sinceMillis());
        body.put("checked_at", view.checkedAtMillis());
        body.put("poll_after_seconds", POLL_AFTER_SECONDS);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
