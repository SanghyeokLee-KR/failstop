package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 경보 의미표(API 설계 §6-F '원인 분석과 부하 판정'). 저장소의 경보 규칙에 있는 이름은 모두 표에 있어야 하고, 이름이 비슷한
 * 앱 계층 경보와 DB 계층 경보를 표가 가른다.
 */
class AlertMeaningsTest {

    private static final Pattern ALERT = Pattern.compile("^\\s*-\\s*alert:\\s*([A-Za-z0-9_:]+)", Pattern.MULTILINE);
    private static final AlertMeanings MEANINGS = AlertMeanings.load(new ObjectMapper());

    @Test
    void 저장소_경보_규칙의_이름은_모두_의미표에_있다() throws IOException {
        Set<String> names = new TreeSet<>();
        for (String file : System.getProperty("alert.rules").split(",")) {
            Matcher m = ALERT.matcher(Files.readString(Path.of(file), StandardCharsets.UTF_8));
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        assertTrue(names.size() > 60, "읽은 경보 이름 수: " + names.size());
        Set<String> missing = new TreeSet<>(names);
        missing.removeAll(MEANINGS.names());
        assertTrue(missing.isEmpty(), "의미표에 없는 경보: " + missing);
    }

    @Test
    void 매칭_리더_경보와_DB_Primary_경보는_계층이_다르고_혼동을_적는다() {
        AlertMeanings.Meaning leader = MEANINGS.of("LeaderAbsent");
        assertEquals("matching", leader.layer());
        assertTrue(leader.not().contains("DB Primary 부재가 아닙니다"));
        assertEquals("matching", MEANINGS.of("PartitionRebuildFailing").layer());
        assertEquals("matching", MEANINGS.of("PartitionLeaderAbsent").layer());
        AlertMeanings.Meaning primary = MEANINGS.of("PatroniPrimaryAbsent");
        assertEquals("database_ha", primary.layer());
        assertTrue(primary.not().contains("LeaderAbsent"));
    }

    @Test
    void 표에_없는_이름과_버린_이름은_unknown_이다() {
        assertEquals("unknown", MEANINGS.of("SomethingNew").layer());
        assertEquals("unknown", MEANINGS.of(null).layer());
        Map<String, Object> item = new LinkedHashMap<>();
        MEANINGS.describe(item, null);
        assertEquals("unknown", item.get("layer"));
        assertNull(item.get("meaning"));
    }

    @Test
    void 대상_지표는_원인_분석의_신호_이름이다() {
        for (String name : MEANINGS.names()) {
            String signal = MEANINGS.of(name).signal();
            if (signal != null) {
                assertNotNull(CauseQuery.bySignal(signal), name + "의 대상 지표가 원인 분석 신호가 아니다: " + signal);
            }
        }
        assertEquals("app_cpu", MEANINGS.of("AppTierCpuHigh").signal());
    }
}
