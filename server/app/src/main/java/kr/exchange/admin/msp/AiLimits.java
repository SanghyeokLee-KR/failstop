package kr.exchange.admin.msp;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 운영자가 바꾸는 AI 한도 다섯(ADR 0035, 사용자 확정 2026-09-25). 하드 범위는 코드에 고정하고 DB CHECK 가 한 번 더
 * 건다(DB 설계 §10.4). tighterUp 은 값을 올려야 AI 권한이 좁아지는 항목이다.
 */
public record AiLimits(Map<Key, Integer> values) {

    public enum Key {
        CONFIDENCE_PCT("confidence_pct", 70, 95, true, "%"),
        AUTO_CAP("auto_cap", 1, 10, false, "회"),
        THRESHOLD_PER_ITEM("threshold_per_item", 1, 3, false, "회"),
        BOT_COOLDOWN_MIN("bot_cooldown_min", 5, 60, true, "분"),
        BACKUP_DISK_FREE_PCT("backup_disk_free_pct", 20, 50, true, "%");

        private final String id;
        private final int min;
        private final int max;
        private final boolean tighterUp;
        private final String unit;

        Key(String id, int min, int max, boolean tighterUp, String unit) {
            this.id = id;
            this.min = min;
            this.max = max;
            this.tighterUp = tighterUp;
            this.unit = unit;
        }

        public String id() {
            return id;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        public boolean tighterUp() {
            return tighterUp;
        }

        public String unit() {
            return unit;
        }

        public boolean inRange(long value) {
            return value >= min && value <= max;
        }

        /** from 에서 to 로 바꾸는 것이 AI 권한을 좁히는가. 같은 값은 좁히는 쪽으로 본다. */
        public boolean tightens(int from, int to) {
            return tighterUp ? to >= from : to <= from;
        }

        public static Key of(String id) {
            for (Key k : values()) {
                if (k.id.equals(id)) {
                    return k;
                }
            }
            return null;
        }
    }

    public AiLimits {
        values = Map.copyOf(values);
        for (Key k : Key.values()) {
            if (!values.containsKey(k)) {
                throw new IllegalStateException("AI 한도 항목이 빠졌다: " + k.id());
            }
        }
    }

    public int get(Key key) {
        return values.get(key);
    }

    public int confidencePct() {
        return get(Key.CONFIDENCE_PCT);
    }

    public int autoCap() {
        return get(Key.AUTO_CAP);
    }

    public int thresholdPerItem() {
        return get(Key.THRESHOLD_PER_ITEM);
    }

    public int botCooldownMin() {
        return get(Key.BOT_COOLDOWN_MIN);
    }

    public int backupDiskFreePct() {
        return get(Key.BACKUP_DISK_FREE_PCT);
    }

    /** 항목마다 AI 권한을 좁히는 쪽을 고른다. 활성 때 잡아 둔 값과 지금 값을 합칠 때 쓴다(정책 L3). */
    public static AiLimits stricter(AiLimits a, AiLimits b) {
        Map<Key, Integer> out = new EnumMap<>(Key.class);
        for (Key k : Key.values()) {
            int x = a.get(k);
            int y = b.get(k);
            out.put(k, k.tighterUp() ? Math.max(x, y) : Math.min(x, y));
        }
        return new AiLimits(out);
    }

    /** DB 의 키 이름으로 된 값 표에서 만든다. */
    public static AiLimits fromIds(Map<String, Integer> byId) {
        Map<Key, Integer> out = new EnumMap<>(Key.class);
        byId.forEach((id, v) -> {
            Key k = Key.of(id);
            if (k != null) {
                out.put(k, v);
            }
        });
        return new AiLimits(out);
    }

    public Map<String, Integer> byId() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Key k : Key.values()) {
            out.put(k.id(), values.get(k));
        }
        return out;
    }
}
