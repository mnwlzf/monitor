# syntax=docker/dockerfile:1.7

# ============================================================
# 1) 构建前端静态资源
# ============================================================
FROM node:22-alpine AS frontend
WORKDIR /build/frontend

COPY frontend/package.json frontend/package-lock.json ./
# npm 缓存挂载：依赖下载结果在多次构建之间复用
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund

COPY frontend/ ./
RUN npm run build

# ============================================================
# 2) 构建后端可执行 Jar，并把前端产物打进 static 目录
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build

COPY backend/pom.xml ./
COPY backend/src ./src
COPY --from=frontend /build/frontend/dist ./src/main/resources/static

# Maven 本地仓库缓存挂载：jar 依赖只下载一次
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package

# ============================================================
# 3) 运行镜像：单个容器同时提供前端页面和后端 API
# ============================================================
FROM eclipse-temurin:21-jre AS runtime

ENV TZ=Asia/Shanghai \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r monitor \
    && useradd -r -g monitor -u 10001 monitor \
    && mkdir -p /app/config /app/logs \
    && chown -R monitor:monitor /app

WORKDIR /app
COPY --from=backend /build/target/*.jar /app/app.jar
RUN chown monitor:monitor /app/app.jar

USER monitor
EXPOSE 8080

# 外挂配置目录：把 application.yml / .env 放到宿主机的 ./config 并挂载到 /app/config 即可
VOLUME ["/app/config", "/app/logs"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --spring.config.additional-location=optional:file:/app/config/"]