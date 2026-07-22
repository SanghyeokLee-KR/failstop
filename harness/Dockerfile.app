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
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
