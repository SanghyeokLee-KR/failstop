// 루트는 공통 설정만 담당한다. 실제 빌드 로직은 각 모듈에 둔다.
allprojects {
    group = "kr.exchange"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
