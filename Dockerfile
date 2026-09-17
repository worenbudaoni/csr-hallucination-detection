# syntax=docker/dockerfile:1

# ============================================================================
# 直接用构建好的 jar，不在镜像里编译
# ============================================================================
#
# 构建前必须先在宿主机打包：
#     mvn package -DskipTests        （或 ./mvnw package -DskipTests）
#
# 为什么不在镜像里编译：容器内跑 Maven 每次都要下一遍依赖，构建慢、镜像层大，
# 而且本地已经用 IDE 或命令行构建过了，再来一遍纯属重复。
# 代价是构建镜像前不能忘了先 package —— 忘了会看到下面这条报错：
#     failed to compute cache key: "/target/xxx.jar": not found
#
# 对应的 .dockerignore 里用
#     target/*
#     !target/*.jar
# 只放行 jar，其余构建产物（classes、测试报告等）不进构建上下文，
# 否则每次 docker build 都要把几百 MB 传给守护进程。

FROM eclipse-temurin:17-jre

WORKDIR /app

# 不以 root 运行
RUN groupadd --system app && useradd --system --gid app --create-home app

# 用 *.jar 而不是写死版本号，pom 改版本时这里不用跟着改。
# Spring Boot 的 repackage 会在 target 下留一个 xxx.jar.original，
# 但它不以 .jar 结尾，不会被这个模式匹配到。
COPY target/*.jar /app/app.jar

RUN chown -R app:app /app
USER app

EXPOSE 8080

# 关于配置注入：
#   .env 不在镜像里 —— 它在项目根目录、被 .gitignore 与 .dockerignore 双重排除。
#   容器里的变量通过 docker run --env-file .env 或 docker compose 的 env_file 注入。
#
#   一个变量都不给也能正常启动：application.yml 里用的是
#   spring.config.import=optional:file:.env[.properties]，文件缺失时静默跳过。
#   模型凭据本来就不在服务端——由用户在页面上现填，随请求传给后端。
#
#   -Dfile.encoding=UTF-8 是必要的：不指定的话容器里的中文在部分环境下会乱码。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
