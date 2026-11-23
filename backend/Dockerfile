# 백엔드 실행 이미지. 먼저 `mvn -B -DskipTests package` 로 jar 를 만든 뒤 빌드한다(빌드 도구·의존성 캐시는 CI 에서 관리).
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app && mkdir -p /data/files && chown app:app /data/files
WORKDIR /app
COPY target/commerce-os-backend-*.jar /app/app.jar
USER app
ENV FILES_ROOT=/data/files
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --retries=6 CMD wget -qO- http://localhost:8080/actuator/health/readiness >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
