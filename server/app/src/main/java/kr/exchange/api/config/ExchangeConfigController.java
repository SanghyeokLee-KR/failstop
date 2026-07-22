package kr.exchange.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공개 거래 구성 조회(설계 §5-4). 트레이딩 UI(공개 오리진)가 효과 강도를 읽는 경로다.
 * UI 안전 키 화이트리스트만 노출한다. 운영·보안 config 키는 절대 내보내지 않는다
 * (system_config를 통째로 열지 않는다). /markets와 동급 무인증이다.
 */
@RestController
@RequestMapping("/api/v1")
public class ExchangeConfigController {

    private static final String FLASH_KEY = "ui.trade_flash_intensity";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ExchangeConfigController(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/exchange-config")
    public Map<String, Object> exchangeConfig() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", "1.0");
        body.put("trade_flash", readString(FLASH_KEY, "normal"));
        return body;
    }

    private String readString(String key, String fallback) {
        String json = jdbc.sql("SELECT value::text FROM system_config WHERE config_key = ?")
                .params(key).query(String.class).optional().orElse(null);
        if (json == null) {
            return fallback;
        }
        try {
            return objectMapper.readTree(json).asText(fallback);
        } catch (Exception e) {
            return fallback;
        }
    }
}
