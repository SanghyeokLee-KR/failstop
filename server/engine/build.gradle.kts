// 순수 Java 모듈. Spring, I/O, 벽시계, 난수 의존 금지가 결정론 재생의 전제다.
// 의존성 추가 시 API 설계 v2.6 §0-1(exchange-core 결정론 규칙)과 정합해야 한다.
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // 재생 결정론·오더북 불변식 속성 검증(하네스 T3-B). 테스트 전용.
    testImplementation("net.jqwik:jqwik:1.9.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
