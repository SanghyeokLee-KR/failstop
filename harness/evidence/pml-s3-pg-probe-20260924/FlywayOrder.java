import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * S3 설계 3절 P7. 높은 번호(V4)가 적용된 DB 에 낮은 번호(V3)를 뒤늦게 넣으면 Flyway 11.7.2 기본 설정이 어떻게 하는가.
 * S3 마이그레이션을 V50 으로 두고 권한 회수 V49 를 나중에 넣을 수 있는지 판단하려는 것이다. 앱과 같은 Gradle 캐시의 jar 를 쓴다.
 * 사용: java -cp <jar들> FlywayOrder.java <jdbc-url> <user> <password> <작업 폴더>
 */
public class FlywayOrder {
    public static void main(String[] a) throws Exception {
        String url = a[0], user = a[1], pw = a[2];
        Path dir = Path.of(a[3]);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("V1__one.sql"), "CREATE TABLE t1 (id int);");
        Files.writeString(dir.resolve("V2__two.sql"), "CREATE TABLE t2 (id int);");
        Files.writeString(dir.resolve("V4__four.sql"), "CREATE TABLE t4 (id int);");
        MigrateResult first = Flyway.configure().dataSource(url, user, pw)
                .locations("filesystem:" + dir).load().migrate();
        System.out.println("step1 migrate V1,V2,V4: success=" + first.success + " applied=" + first.migrationsExecuted
                + " target=" + first.targetSchemaVersion);

        Files.writeString(dir.resolve("V3__three.sql"), "CREATE TABLE t3 (id int);");
        try {
            MigrateResult second = Flyway.configure().dataSource(url, user, pw)
                    .locations("filesystem:" + dir).load().migrate();
            System.out.println("step2 migrate after adding V3 (defaults): success=" + second.success
                    + " applied=" + second.migrationsExecuted);
        } catch (Exception e) {
            System.out.println("step2 migrate after adding V3 (defaults): " + e.getClass().getSimpleName() + ": "
                    + e.getMessage().replace('\n', ' '));
        }
        MigrateResult third = Flyway.configure().dataSource(url, user, pw)
                .locations("filesystem:" + dir).outOfOrder(true).load().migrate();
        System.out.println("step3 migrate with outOfOrder=true: success=" + third.success + " applied="
                + third.migrationsExecuted);

        // 앞 이미지 흉내: V1·V2 만 아는 앱이 V1~V4 가 적용된 DB 에 붙는다(DB 에만 있는 뒤 번호).
        Path old = dir.resolveSibling(dir.getFileName() + "-old");
        Files.createDirectories(old);
        Files.copy(dir.resolve("V1__one.sql"), old.resolve("V1__one.sql"));
        Files.copy(dir.resolve("V2__two.sql"), old.resolve("V2__two.sql"));
        try {
            MigrateResult older = Flyway.configure().dataSource(url, user, pw)
                    .locations("filesystem:" + old).load().migrate();
            System.out.println("step4 older image (knows V1,V2) on DB with V1..V4: success=" + older.success
                    + " applied=" + older.migrationsExecuted);
        } catch (Exception e) {
            System.out.println("step4 older image: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage().replace('\n', ' '));
        }
    }
}
