package kr.exchange;

import kr.exchange.harness.S7Audit;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V55 마이그레이션 파일과 계약(DB 설계 §3.10 첫 SQL 블록)이 같은지 본다(ADR 0034 S7, S7 설계 4-1·6-2). 계약은 코드보다 먼저
 * 적었고 파일은 그 블록을 그대로 옮긴다. 대조는 줄바꿈을 LF 로 맞추고 끝 줄바꿈을 뺀 문자열이다. 그 정규화 문자열의 sha256 앞
 * 16자리는 설계가 적은 값(7a75c805b58f1646)이다. 파일이나 계약 한쪽만 고치면 이 시험이 실패한다.
 */
class V55ContractBlockTest {

    private static final String V55 = "db/migration/V55__partition_protocol_cutover.sql";
    private static final String SECTION = "### 3.10 `V55__partition_protocol_cutover.sql`";
    private static final String DESIGN_HASH = "7a75c805b58f1646";

    @Test
    void migrationFileEqualsTheContractBlock() throws Exception {
        String file = normalize(resource(V55));
        String block = normalize(contractBlock());
        assertEquals(block, file, "V55 파일이 DB 설계 §3.10 SQL 블록과 다르다");
        assertEquals(DESIGN_HASH, sha256Prefix(file), "V55 정규화 문자열의 해시가 설계 기록(S7 설계 4-1)과 다르다");
        assertTrue(file.startsWith("-- V55__partition_protocol_cutover.sql"), "블록의 첫 줄이 아니다");
        assertTrue(file.endsWith("END $$;"), "블록의 끝이 아니다");
    }

    /** DB 설계 §3.10 의 첫 ```sql 블록 본문. */
    static String contractBlock() throws Exception {
        String doc = S7Audit.dbDesign();
        int section = doc.indexOf(SECTION);
        assertTrue(section >= 0, "DB 설계 문서에 §3.10 이 없다");
        int open = doc.indexOf("```sql\n", section);
        int close = doc.indexOf("\n```", open + 7);
        assertTrue(open > section && close > open, "§3.10 의 SQL 블록을 찾지 못했다");
        return doc.substring(open + "```sql\n".length(), close + 1);
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = V55ContractBlockTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " 없음");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** LF 로 맞추고 끝 줄바꿈을 뺀다. */
    static String normalize(String text) {
        String lf = text.replace("\r\n", "\n");
        while (lf.endsWith("\n")) {
            lf = lf.substring(0, lf.length() - 1);
        }
        return lf;
    }

    static String sha256Prefix(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, 16);
    }
}
