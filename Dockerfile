# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

COPY gradle ./gradle
COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts gradle.properties ./
COPY src ./src

RUN chmod +x ./gradlew
ARG DEPENDENCY_REFRESH
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=secret,id=gpr_user,required=true \
    --mount=type=secret,id=gpr_key,required=true \
    export GPR_USER="$(cat /run/secrets/gpr_user)" \
    GPR_KEY="$(cat /run/secrets/gpr_key)" \
    && echo "Refreshing Gradle dependencies (build: ${DEPENDENCY_REFRESH})" \
    && ./gradlew --no-daemon --refresh-dependencies shadowJar

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        fontconfig \
        fonts-dejavu-core \
        fonts-inter \
        fonts-noto-cjk \
        fonts-noto-color-emoji \
        libfontconfig1 \
        libfreetype6 \
        libgl1 \
        libx11-6 \
        libxext6 \
        libxi6 \
        libxrender1 \
        libxtst6 \
        tzdata \
    && ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo Asia/Shanghai > /etc/timezone \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --home-dir /app snapshot

WORKDIR /app

COPY --from=builder --chown=snapshot:snapshot /workspace/build/libs/open-snapshot-all.jar /app/open-snapshot.jar
COPY --from=builder --chown=snapshot:snapshot /workspace/src/main/resources/application.yaml /app/config/application.yaml
COPY --chmod=755 docker/entrypoint.sh /app/entrypoint.sh

USER snapshot

ENV TZ="Asia/Shanghai"
ENV HOME="/tmp"
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -Duser.home=/tmp -Duser.timezone=Asia/Shanghai -XX:MaxRAMPercentage=60 -XX:InitialRAMPercentage=20 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
