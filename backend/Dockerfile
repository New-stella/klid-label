# syntax=docker/dockerfile:1
# ============================================================================
# klid-label backend (Spring Boot) — 멀티스테이지 빌드
#   build  : gradle:8-jdk17 → ./gradlew clean bootJar -x test
#   runtime: eclipse-temurin:17-jre, 비루트 유저, java -jar app.jar
# ============================================================================

# ---- build stage ----
FROM gradle:8.8-jdk17 AS build
WORKDIR /workspace

# Gradle 래퍼/설정 먼저 복사 → 의존성 레이어 캐시
COPY gradlew settings.gradle build.gradle gradle.lockfile ./
COPY gradle ./gradle
# 의존성 사전 해석 (소스 변경 시에도 재사용). 실패해도 무방하므로 || true.
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 bootJar (테스트 제외 — Testcontainers 가 빌드 컨테이너 내 docker 데몬 요구)
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- runtime stage ----
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# ffmpeg/ffprobe — FFmpegStep / DevAutolabelTestService 가 사용
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*

# 비루트 유저
RUN groupadd -r app && useradd -r -g app -d /app app \
    && mkdir -p /app/storage/raw /app/storage/deidentified \
    && chown -R app:app /app

COPY --from=build --chown=app:app /workspace/build/libs/*.jar /app/app.jar

USER app
EXPOSE 8080

# 컨테이너 메모리 제한에 맞춰 힙 자동 조정 (-XX:MaxRAMPercentage)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Duser.timezone=Asia/Seoul"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
