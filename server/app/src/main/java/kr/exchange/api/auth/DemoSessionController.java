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

/**
 * 데모 세션 발급(§3-7). 무인증 엔드포인트라 IP 단위 강한 rate limit을 자체 적용한다.
 * §2-5 전역 토큰버킷 미구현 상태에서도 이 경로만은 발급 남용을 막아야 한다.
 */
@RestController
public class DemoSessionController {

    private static final int WINDOW_LIMIT = 10;
    private static final long WINDOW_MS = 60_000;

    private final DemoSessionService sessions;
    private final DemoUserProvisioner provisioner;
    private final ConcurrentHashMap<String, Deque<Long>> issuedAt = new ConcurrentHashMap<>();

    public DemoSessionController(DemoSessionService sessions, DemoUserProvisioner provisioner) {
        this.sessions = sessions;
        this.provisioner = provisioner;
    }

    @PostMapping("/api/v1/demo/session")
    public Map<String, Object> issue(HttpServletRequest request) {
        throttle(request.getRemoteAddr());
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

    private void throttle(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> window = issuedAt.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= WINDOW_LIMIT) {
                throw new ApiException(429, "too_many_requests",
                        "데모 세션 발급 한도를 초과했습니다.", null,
                        (int) ((WINDOW_MS - (now - window.peekFirst())) / 1000) + 1);
            }
            window.addLast(now);
        }
    }
}
