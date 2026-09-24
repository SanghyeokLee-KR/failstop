package kr.exchange.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 파티션 리더 설정(DB 설계 §5.4-c 9·10, ADR 0034 결정 2·4·12). 선호와 참여는 P3 에서만 뜻이 있고 P1·P2 의 matching-engine
 * 에는 선호와 유예가 없다.
 *
 * @param preferred      선호 파티션 자원 목록. 비선호 파티션은 획득을 유예한다. 기본 빈 목록
 * @param participate    참여 파티션 자원 목록. 비우면 계획의 전부다
 * @param maxDemotions   차단기 상한. 창 안의 파티션 치명 강등이 이 수에 이르면 그 파티션을 차단한다. 기본 3
 * @param demotionWindow 차단기 창. 기본 10분
 */
@ConfigurationProperties(prefix = "exchange.leader.partition")
public record LeaderPartitionProperties(List<String> preferred, List<String> participate,
                                        int maxDemotions, Duration demotionWindow) {

    public LeaderPartitionProperties {
        preferred = preferred == null ? List.of() : List.copyOf(preferred);
        participate = participate == null ? List.of() : List.copyOf(participate);
        if (maxDemotions <= 0) {
            maxDemotions = 3;
        }
        if (demotionWindow == null) {
            demotionWindow = Duration.ofMinutes(10);
        }
        if (demotionWindow.isNegative() || demotionWindow.isZero()) {
            throw new IllegalArgumentException("demotionWindow는 양수여야 한다: " + demotionWindow);
        }
    }

    public boolean prefers(String resource) {
        return preferred.contains(resource);
    }

    public boolean participates(String resource) {
        return participate.isEmpty() || participate.contains(resource);
    }
}
