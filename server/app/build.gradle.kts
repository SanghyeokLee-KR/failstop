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
    // RFC 8785(JCS) 정규화. 서명 canonical과 멱등 내용 비교의 공통 원천(API 설계 §3-3(b), §2-4).
    // SB BOM 미관리 항목이라 개별 핀 유지(버전 고정 원칙).
    implementation("io.github.erdtman:java-json-canonicalization:1.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 워크스페이스가 비ASCII 물리 경로(정션 대상: ...\프로젝트)일 때 Windows 기본 코드페이지
    // (cp949)로 기동한 테스트 워커가 @argfile 클래스패스를 잘못 디코딩해 ClassNotFoundException을
    // 낸다. 워커 파일·경로 인코딩을 UTF-8로 고정해 콘솔 코드페이지와 무관하게 재현되게 한다.
    defaultCharacterEncoding = "UTF-8"
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}
