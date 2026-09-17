# syntax=docker/dockerfile:1

# ============================================================================
# 阶段一：构建
# ============================================================================
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# 可选的 Maven 镜像加速。容器里首次构建要从中央仓库拉 Spring AI 全家桶，
# 直连会比较慢。用法：
#   docker build --build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public .
ARG MAVEN_MIRROR=""

COPY pom.xml .
RUN if [ -n "$MAVEN_MIRROR" ]; then \
      mkdir -p /root/.m2 && \
      printf '%s' \
        '<settings><mirrors><mirror><id>mirror</id><name>mirror</name>' \
        "<url>$MAVEN_MIRROR</url>" \
        '<mirrorOf>central</mirrorOf></mirror></mirrors></settings>' \
        > /root/.m2/settings.xml; \
    fi \
    && mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ============================================================================
# 阶段二：运行
# ============================================================================
FROM eclipse-temurin:17-jre

WORKDIR /app

# 不以 root 运行
RUN groupadd --system app && useradd --system --gid app --create-home app

COPY --from=build /build/target/*.jar /app/app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# 关于配置注入：
#   .env 不在镜像里 —— 它在项目根目录、被 .gitignore 与 .dockerignore 双重排除。
#   容器里的变量通过 docker run --env-file .env 或 docker compose 的 env_file 注入。
#
#   一个变量都不给也能正常启动：application.yml 里用的是
#   spring.config.import=optional:file:.env[.properties]，文件缺失时静默跳过，
#   然后回落到 app.llm.provider 的默认值 mock。
#
#   -Dfile.encoding=UTF-8 是必要的：不指定的话容器里的中文报告在部分环境下会乱码。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
