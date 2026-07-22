package kr.exchange.api.member;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.ClientIp;
import kr.exchange.api.common.SlidingWindowThrottle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 회원 인증 엔드포인트(회원_설계.md §3-1). 브라우저 전용이라 HMAC canonical·nonce를 요구하지
 * 않는다(데모 세션과 동일). refresh 토큰은 httpOnly·Secure·SameSite=Strict 쿠키 rt로만 오간다.
 * 액세스 토큰은 응답 바디로만 반환하고 서버는 쿠키에 담지 않는다(브라우저 메모리 보관 계약, §4-5).
 */
@RestController
public class MemberAuthController {

    private static final String REFRESH_COOKIE = "rt";
    // refresh·logout만 쿠키를 필요로 하므로 경로를 좁혀 다른 엔드포인트에 실려 나가지 않게 한다.
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final MemberAuthService auth;
    // 회원_설계.md §3-1 진입 rate limit. /api/v1/auth/*는 인증 필터 제외라 여기서 자체 스로틀한다.
    // 무인증 signup은 Argon2id(메모리 하드) + 원장 게시를 수행하므로 IP 한도가 필수 방어다.
    private final SlidingWindowThrottle signupThrottle =
            new SlidingWindowThrottle(5, 60_000, "auth_signup");
    private final SlidingWindowThrottle loginThrottle =
            new SlidingWindowThrottle(10, 60_000, "auth_login");
    private final SlidingWindowThrottle refreshThrottle =
            new SlidingWindowThrottle(30, 60_000, "auth_refresh");

    public MemberAuthController(MemberAuthService auth) {
        this.auth = auth;
    }

    public record SignupRequest(String email, String password, String display_name) {
    }

    public record LoginRequest(String email, String password) {
    }

    @PostMapping("/api/v1/auth/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody(required = false) SignupRequest request,
                                                      HttpServletRequest http) {
        String ip = ClientIp.of(http);
        signupThrottle.check(ip);
        SignupRequest body = request == null ? new SignupRequest(null, null, null) : request;
        MemberAuthService.SessionResult result = auth.signup(
                body.email(), body.password(), body.display_name(),
                ip, http.getHeader("User-Agent"));
        return sessionResponse(HttpStatus.CREATED, result);
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest request,
                                                     HttpServletRequest http) {
        LoginRequest body = request == null ? new LoginRequest(null, null) : request;
        String ip = ClientIp.of(http);
        // IP·이메일 각 10회/60s(§3-1). 계정 백오프(서비스 계층)와 병행해 IP 분산·계정 표적을 함께 막는다.
        loginThrottle.check(ip);
        if (body.email() != null && !body.email().isBlank()) {
            loginThrottle.check("email:" + body.email().trim().toLowerCase());
        }
        MemberAuthService.SessionResult result = auth.login(
                body.email(), body.password(), ip, http.getHeader("User-Agent"));
        return sessionResponse(HttpStatus.OK, result);
    }

    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<Map<String, Object>> refresh(HttpServletRequest http) {
        String ip = ClientIp.of(http);
        refreshThrottle.check(ip);
        MemberAuthService.SessionResult result = auth.refresh(
                readRefreshCookie(http), ip, http.getHeader("User-Agent"));
        return sessionResponse(HttpStatus.OK, result);
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        // 인증은 쿠키 rt다(§3-1). /api/v1/auth/*는 인증 필터 제외라 회원 세션 principal이 주입되지 않는다.
        auth.logout(readRefreshCookie(http));
        ResponseCookie cleared = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true).secure(true).sameSite("Strict")
                .path(REFRESH_COOKIE_PATH).maxAge(0).build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .build();
    }

    private ResponseEntity<Map<String, Object>> sessionResponse(HttpStatus status,
                                                                MemberAuthService.SessionResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("access_token", result.accessToken());
        body.put("token_type", "Bearer");
        body.put("expires_in", MemberSessionService.ACCESS_TTL.toSeconds());
        body.put("user", result.userView());
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, result.refreshTokenRaw())
                .httpOnly(true).secure(true).sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ofSeconds(result.refreshTtlSeconds()))
                .build();
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(body);
    }

    private static String readRefreshCookie(HttpServletRequest http) {
        Cookie[] cookies = http.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (REFRESH_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
