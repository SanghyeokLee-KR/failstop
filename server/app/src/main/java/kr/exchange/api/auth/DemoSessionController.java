package kr.exchange.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 데모 세션 발급(§3-7). 무인증 엔드포인트라 IP 단위 강한 rate limit을 자체 적용한다.
 * §2-5 전역 토큰버킷 미구현 상태에서도 이 경로만은 발급 남용을 막아야 한다.
 */
@RestController
public class DemoSessionController {

    private static final int WINDOW_LIMIT = 10;
    private static final long WINDOW_MS = 60_000;
    private static final String BUCKET = "demo_session";

    private final DemoSessionService sessions;
    private final DemoUserProvisioner provisioner;
    private final ConcurrentHashMap<String, Deque<Long>> issuedAt = new ConcurrentHashMap<>();
    private final AtomicLong nextSweepAt = new AtomicLong();

    public DemoSessionController(DemoSessionService sessions, DemoUserProvisioner provisioner) {
        this.sessions = sessions;
        this.provisioner = provisioner;
    }

    @PostMapping("/api/v1/demo/session")
    public Map<String, Object> issue(HttpServletRequest request) {
        throttle(clientKey(request));
        DemoUserProvisioner.DemoUser user = provisioner.ensure();
        String token = sessions.issue(user.userId(), user.externalId(),
                DemoSessionService.DEFAULT_TTL);
        Map<String, Object> userView = new LinkedHashMap<>();
        userView.put("external_id", user.externalId());
        userView.put("email", user.email());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("access_token", token);
        body.put("token_type", "Bearer");
        body.put("expires_in", DemoSessionService.DEFAULT_TTL.toSeconds());
        body.put("user", userView);
        return body;
    }

    /**
     * 리버스 프록시 뒤에서 getRemoteAddr는 프록시 IP라 모든 클라이언트가 한 버킷을 공유한다.
     * X-Forwarded-For 첫 홉(원 클라이언트)을 신뢰 경계로 사용하고, 없으면 remoteAddr로 폴백한다.
     */
    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private void throttle(String ip) {
        long now = System.currentTimeMillis();
        sweep(now);
        Deque<Long> window = issuedAt.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= WINDOW_LIMIT) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("bucket", BUCKET);
                throw new ApiException(429, "too_many_requests",
                        "데모 세션 발급 한도를 초과했습니다.", detail,
                        (int) ((WINDOW_MS - (now - window.peekFirst())) / 1000) + 1);
            }
            window.addLast(now);
        }
    }

    /**
     * 재방문 없는 IP의 빈 Deque가 맵에 잔존해 무한 증가하는 것을 최소 간격으로 정리한다.
     * 창을 벗어난 엔트리만 제거하므로 활성 버킷은 건드리지 않는다.
     */
    private void sweep(long now) {
        long due = nextSweepAt.get();
        if (now < due || !nextSweepAt.compareAndSet(due, now + WINDOW_MS)) {
            return;
        }
        issuedAt.forEach((ip, window) -> {
            synchronized (window) {
                while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) {
                    window.pollFirst();
                }
                if (window.isEmpty()) {
                    issuedAt.remove(ip, window);
                }
            }
        });
    }
}
