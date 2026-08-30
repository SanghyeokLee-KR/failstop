# 격리 카오스 토폴로지용 앱 이미지. 호스트에서 사전 빌드한 bootJar를 담는다.
# 빌드 컨텍스트에 app.jar가 있어야 한다(up.ps1이 :app:bootJar 산출물을 복사).
# 계약 표준형(하네스_CICD_IaC §2-4)의 축약본. jarmode 레이어 분리는 데모 범위에서 생략한다.
FROM eclipse-temurin:21-jre

# readiness 헬스체크용 curl. jre 이미지에는 없다.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY app.jar app.jar
# compose deploy.limits.memory 1g 기준 힙 산정
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# 마스터키 blank 기동 차단. 이미지에 굽는 이유는 compose 인자를 빼먹어도 살아야 하기
# 때문이다. 실측 사고(2026-08-30): --env-file 없이 부분 재기동해 키가 빈 값이 됐고,
# 부팅과 헬스체크는 통과한 채 인증이 3시간 49분간 100% 500이었다. 절차로 막히지 않는다.
ENV EXCHANGE_AUTH_REQUIRE_MASTER_KEY=true
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
