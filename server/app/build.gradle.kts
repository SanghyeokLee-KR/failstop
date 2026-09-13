// 버전 정책: SB BOM 관리 항목은 개별 핀 금지, BOM 미관리 항목(Resilience4j)만 핀.
// API 설계 v2.5 §1 버전 고정 원칙.
plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":engine"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // 회원 비밀번호 Argon2id·회원 세션 EdDSA(Ed25519).
    // spring-security-crypto는 SB BOM 관리(핀 없음). Argon2PasswordEncoder는 bcprov 런타임 의존이고
    // Ed25519 서명도 bcprov를 쓴다. bcprov는 BOM 미관리라 버전 고정 원칙에 따라 핀한다.
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.4.0")

    // 카오스 매트릭스 E8 assault 주입기. 자동설정은 ChaosMonkeyCondition으로 chaos-monkey 프로파일
    // (또는 LOAD_CHAOS_MONKEY 시스템 프로퍼티)에서만 로드된다. dev·prod·bots·데모는 프로파일 미활성이라
    // 빈·actuator 엔드포인트가 전혀 뜨지 않는다(격리 chaos 스택 한정). SB BOM 미관리라 버전 핀.
    implementation("de.codecentric:chaos-monkey-spring-boot:3.3.0")
    // RFC 8785(JCS) 정규화. 서명 canonical과 멱등 내용 비교의 공통 원천(API 설계 §3-3(b), §2-4).
    // SB BOM 미관리 항목이라 개별 핀 유지(버전 고정 원칙).
    implementation("io.github.erdtman:java-json-canonicalization:1.1")

    // 리더 리스 보관소를 PostgreSQL 커밋 경로에서 떼기 위한 DynamoDB 클라이언트(2026-09-08).
    // 거래 데이터는 PostgreSQL에 그대로 있고 리스 항목 하나만 여기로 간다. SB BOM 미관리
    // 항목이라 버전 고정 원칙에 따라 핀한다. url-connection-client는 Netty 대신 JDK
    // HttpURLConnection을 쓰는 최소 동기 전송이다. 리스 왕복은 초당 한 번이라 비동기 스택이
    // 필요 없고, 기본 전송(apache/netty)을 끌어오면 앱 이미지와 기동 시간만 커진다.
    //
    // Apache HttpClient 5 를 반드시 배제한다. 이것이 클래스패스에 들어오면 Spring 의
    // ClientHttpRequestFactory 자동 선택이 JDK 클라이언트 대신 그것을 고르고, TestRestTemplate 의
    // 급송이 소형 커넥션 풀에 직렬화된다. 그러면 레이트리밋 급송 검증이 한도를 넘지 못해 429 대신
    // 201 이 나오고 회차 시간이 5 배가 된다. 2026-09-08 에 실측으로 확인했다. 배제 전 8 건 실패,
    // 배제 후 전량 통과다. 전송은 url-connection-client 하나로 충분하므로 Apache 는 필요 없다.
    implementation("software.amazon.awssdk:dynamodb:2.54.13") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "org.apache.httpcomponents.client5", module = "httpclient5")
        exclude(group = "org.apache.httpcomponents.core5", module = "httpcore5")
        exclude(group = "org.apache.httpcomponents.core5", module = "httpcore5-h2")
    }
    implementation("software.amazon.awssdk:url-connection-client:2.54.13")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 테스트 워커의 파일·경로 인코딩을 UTF-8로 고정해 콘솔 코드페이지와 무관하게 재현되게 한다.
    // 다만 이 설정으로 비ASCII 프로젝트 경로가 구제되지는 않는다. 워커가 @argfile 클래스패스를
    // cp949로 디코딩해 ClassNotFoundException을 내는 문제는 프로젝트를 ASCII 경로에 두어야만
    // 해결된다(server/README.md). 한글 경로 실측에서 이 블록이 있는 :app:test도 전량 실패했다.
    defaultCharacterEncoding = "UTF-8"
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    // 판정 술어 세 벌 대조 시험이 컨테이너 psql 에 넘기는 하네스 SQL. 작업 디렉터리에 기대지 않게 절대 경로로
    // 넘기고, 빌드 캐시가 SQL 변경 뒤에도 시험 결과를 재사용하지 않도록 입력으로 선언한다.
    val harnessSql = rootProject.file("../harness/sql")
    inputs.dir(harnessSql).withPropertyName("harnessSql").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("harness.sql.dir", harnessSql.absolutePath)
}
