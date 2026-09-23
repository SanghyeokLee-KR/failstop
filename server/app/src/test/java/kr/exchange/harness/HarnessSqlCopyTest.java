package kr.exchange.harness;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VM 실습망의 복원 점검과 DB 승격 검사는 하네스 판정 SQL 의 사본을 읽는다(DB02 구축 문서가 사본이라고 적는다). 사본이 갈리면
 * 같은 DB 를 두고 저장소와 VM 이 다른 판정을 낸다.
 */
class HarnessSqlCopyTest {

    @Test
    void vmLabSummarySqlIsByteIdenticalToHarnessOriginal() throws Exception {
        String dir = System.getProperty("harness.sql.dir");
        String copy = System.getProperty("vmlab.summary.sql");
        assertNotNull(dir, "시스템 속성 harness.sql.dir 없음(server/app/build.gradle.kts 가 넘긴다)");
        assertNotNull(copy, "시스템 속성 vmlab.summary.sql 없음(server/app/build.gradle.kts 가 넘긴다)");
        Path original = Path.of(dir, "summary.sql");
        assertTrue(Files.isRegularFile(original), "하네스 SQL 없음: " + original);
        assertTrue(Files.isRegularFile(Path.of(copy)), "VM 실습망 사본 없음: " + copy);
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(Path.of(copy)),
                "VM 실습망 summary.sql 이 harness/sql/summary.sql 과 다르다");
    }
}
