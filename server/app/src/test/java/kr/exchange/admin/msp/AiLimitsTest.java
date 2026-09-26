package kr.exchange.admin.msp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AI 한도의 하드 범위와 조이는 방향(ADR 0035, 정책 L1·L3). */
class AiLimitsTest {

    @Test
    void 하드_범위는_코드에_고정돼_있다() {
        assertTrue(AiLimits.Key.CONFIDENCE_PCT.inRange(70));
        assertTrue(AiLimits.Key.CONFIDENCE_PCT.inRange(95));
        assertFalse(AiLimits.Key.CONFIDENCE_PCT.inRange(69));
        assertFalse(AiLimits.Key.CONFIDENCE_PCT.inRange(96));
        assertFalse(AiLimits.Key.AUTO_CAP.inRange(0));
        assertFalse(AiLimits.Key.AUTO_CAP.inRange(11));
        assertFalse(AiLimits.Key.THRESHOLD_PER_ITEM.inRange(4));
        assertFalse(AiLimits.Key.BOT_COOLDOWN_MIN.inRange(4));
        assertFalse(AiLimits.Key.BOT_COOLDOWN_MIN.inRange(61));
        assertFalse(AiLimits.Key.BACKUP_DISK_FREE_PCT.inRange(19));
        assertFalse(AiLimits.Key.BACKUP_DISK_FREE_PCT.inRange(51));
    }

    @Test
    void 조이는_방향은_항목마다_다르다() {
        assertTrue(AiLimits.Key.AUTO_CAP.tightens(5, 3), "상한은 내리면 조인다");
        assertFalse(AiLimits.Key.AUTO_CAP.tightens(5, 7));
        assertTrue(AiLimits.Key.CONFIDENCE_PCT.tightens(80, 90), "확신도 기준은 올리면 조인다");
        assertFalse(AiLimits.Key.CONFIDENCE_PCT.tightens(80, 75));
        assertTrue(AiLimits.Key.BOT_COOLDOWN_MIN.tightens(10, 20));
        assertTrue(AiLimits.Key.BACKUP_DISK_FREE_PCT.tightens(30, 40));
    }

    @Test
    void 활성_때_값과_지금_값을_합치면_조이는_쪽이_이긴다() {
        AiLimits snapshot = AiFixtures.limits();
        AiLimits current = AiLimits.fromIds(Map.of("confidence_pct", 75, "auto_cap", 3, "threshold_per_item", 3,
                "bot_cooldown_min", 20, "backup_disk_free_pct", 25));

        AiLimits effective = AiLimits.stricter(snapshot, current);

        assertEquals(80, effective.confidencePct(), "푸는 변경(75)은 다음 활성부터");
        assertEquals(3, effective.autoCap(), "조이는 변경(3)은 곧바로");
        assertEquals(2, effective.thresholdPerItem(), "푸는 변경(3)은 다음 활성부터");
        assertEquals(20, effective.botCooldownMin(), "조이는 변경(20)은 곧바로");
        assertEquals(30, effective.backupDiskFreePct(), "푸는 변경(25)은 다음 활성부터");
    }
}
