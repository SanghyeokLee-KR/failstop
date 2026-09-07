package kr.exchange.api.common;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 조회 전용 헬스 그룹의 구성 계약(`/actuator/health/read`).
 *
 * readiness 는 쓰기를 받을 수 있는가를 답한다. 팔로워·승격 대기·강등 노드는 REFUSING 이라
 * 503 이고 그것이 맞다(DB 설계 §5.4). 단일 라이터 구조에서 팔로워로 쓰기를 보내면 순서가 깨진다.
 *
 * 그러나 팔로워도 조회는 정상으로 낸다(2026-09-07 실측: 팔로워 직접 호출 orderbook 200). 그
 * 노드를 조회 분산 대상에 넣으려면 리더 여부를 보지 않는 헬스 경로가 따로 있어야 한다. 이
 * 그룹이 `readinessState` 를 포함하면 인그레스가 팔로워를 대상에서 빼 조회 분산이 통째로
 * 무너진다(증거 `scenario-20260907` 남은 위험).
 *
 * 컨테이너 없이 설정만 고정한다. 응답이 실제로 200 인지는 `AdmissionIntegrationTest` 의 생존
 * 신호 목록이 확인한다.
 */
class ReadHealthGroupTest {

    @SuppressWarnings("unchecked")
    private static List<String> readGroupMembers() {
        List<Object> docs = new ArrayList<>();
        try (InputStream in = ReadHealthGroupTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "application.yml 을 클래스패스에서 찾지 못했다");
            new Yaml().loadAll(in).forEach(docs::add);
        } catch (Exception e) {
            throw new IllegalStateException("application.yml 파싱 실패", e);
        }
        // 프로파일 문서가 여러 개다. 그룹을 정의한 첫 문서를 쓴다.
        for (Object doc : docs) {
            if (!(doc instanceof Map)) {
                continue;
            }
            Object v = dig((Map<String, Object>) doc,
                    "management", "endpoint", "health", "group", "read", "include");
            if (v != null) {
                return Arrays.stream(String.valueOf(v).split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
            }
        }
        throw new AssertionError(
                "조회 전용 헬스 그룹(management.endpoint.health.group.read.include)이 없다. "
                        + "인그레스가 팔로워를 조회 대상에서 뺀다");
    }

    @SuppressWarnings("unchecked")
    private static Object dig(Map<String, Object> root, String... path) {
        Object cur = root;
        for (String key : path) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(key);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    @Test
    void readGroupDoesNotDependOnLeadership() {
        List<String> members = readGroupMembers();
        assertFalse(members.contains("readinessState"),
                "조회 헬스가 readinessState 를 보면 팔로워가 503 이 되어 분산 대상에서 빠진다");
        assertFalse(members.contains("livenessState"),
                "재기동 판단 축을 섞으면 조회 가능 여부와 재기동 필요 여부가 한 신호가 된다");
    }

    @Test
    void readGroupChecksWhatReadsActuallyNeed() {
        List<String> members = readGroupMembers();
        assertTrue(members.contains("db"),
                "조회는 DB 를 탄다. db 를 빼면 DB 가 끊긴 노드로도 조회가 계속 흘러간다");
        assertFalse(members.isEmpty(), "구성원이 없으면 그룹이 항상 UP 이라 헬스체크가 무의미하다");
    }
}
