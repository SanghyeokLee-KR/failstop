package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 보고 서비스(deploy/reporting) 호출(설계 7-12). AI 통지 사건을 넘기고 보고 목록과 전송 상태를 읽는다. Teams 웹훅 주소는
 * 보고 서비스만 가진다. 이 서비스의 토큰은 파일에서 읽어 Authorization 헤더로만 보낸다. 운영 상태를 싣고 가므로 주소는
 * 루프백·사설만 받고, 부를 때마다 이름을 다시 풀어 확인한다.
 */
public class ReportingClient {

    // 보고 목록 100건이 이 안에 든다. 보고 서비스의 수신 본문 상한(64 KiB)보다 크게 둔다.
    static final int MAX_RESPONSE = 256 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    // 보고 서비스의 수신 토큰 최소 길이(deploy/reporting/reporter.py ReportServer)
    private static final int MIN_TOKEN = 32;

    /** 넘김 결과. 202 면 접수이고 reportId 는 보고 서비스의 작업 id 다. 그 밖의 상태는 status 만 싣는다. */
    public record Posted(int status, String reportId, boolean duplicate) {
    }

    private final URI base;
    private final String token;
    private final String customer;
    private final String environment;
    private final SourceHttp http;
    private final ObjectMapper mapper;

    public ReportingClient(MspProperties.Reporting props, ObjectMapper mapper) {
        this.base = MspProperties.sourceUri("reporting.url", props.getUrl());
        List<InetAddress> resolved = LlmClient.resolve(base.getHost());
        if (resolved != null && !resolved.stream().allMatch(LlmClient::privateOrLoopback)) {
            throw new IllegalStateException("exchange.msp.reporting.url 은 루프백·사설 주소여야 한다");
        }
        this.token = readToken(props.getTokenFile());
        this.customer = props.getCustomer();
        this.environment = props.getEnvironment();
        this.http = new SourceHttp(TIMEOUT, TIMEOUT, MAX_RESPONSE, null);
        this.mapper = mapper;
    }

    /** 사건 하나를 넘긴다. 연결 실패와 시간 초과는 SourceException 이다. 같은 사건 id 는 보고 서비스가 한 번만 받는다. */
    public Posted postEvent(byte[] json) {
        checkAddress();
        SourceHttp.Response response = http.post(URI.create(base + "/msp/events"), json, auth());
        if (response.status() != 202) {
            return new Posted(response.status(), null, false);
        }
        JsonNode body = tree(response.body());
        return new Posted(202, body.path("id").asText(null), body.path("duplicate").asBoolean(false));
    }

    /** 보고 목록(GET /console/reports). 설정한 고객·환경 범위만 읽는다. */
    public JsonNode reports(int limit) {
        checkAddress();
        URI uri = URI.create(base + "/console/reports?customer=" + enc(customer) + "&environment=" + enc(environment)
                + "&limit=" + limit);
        return ok(http.get(uri, auth()));
    }

    /** 전송 상태(GET /console/status). */
    public JsonNode status() {
        checkAddress();
        return ok(http.get(URI.create(base + "/console/status"), auth()));
    }

    private JsonNode ok(SourceHttp.Response response) {
        if (response.status() != 200) {
            throw new SourceException("http_status", false);
        }
        return tree(response.body());
    }

    private JsonNode tree(byte[] body) {
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw new SourceException("bad_response", false);
            }
            return node;
        } catch (IOException e) {
            throw new SourceException("bad_response", false);
        }
    }

    private Map<String, String> auth() {
        return Map.of("Authorization", "Bearer " + token);
    }

    private void checkAddress() {
        List<InetAddress> addresses = LlmClient.resolve(base.getHost());
        if (addresses == null) {
            throw new SourceException("unreachable", false);
        }
        if (!addresses.stream().allMatch(LlmClient::privateOrLoopback)) {
            throw new SourceException("remote_address", false);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String readToken(String file) {
        try {
            String token = Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
            if (token.length() < MIN_TOKEN) {
                throw new IllegalStateException("exchange.msp.reporting.token-file 의 토큰이 너무 짧다(32자 이상)");
            }
            return token;
        } catch (IOException e) {
            throw new IllegalStateException("exchange.msp.reporting.token-file 을 읽을 수 없다");
        }
    }
}
