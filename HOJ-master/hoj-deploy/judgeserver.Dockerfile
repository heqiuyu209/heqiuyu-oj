# 第一阶段：从本地源码编译判题服务
FROM maven:3.9-eclipse-temurin-8 AS builder

WORKDIR /build

# Keep the single Maven compiler within a predictable Java heap budget.
ARG MAVEN_OPTS="-Xms64m -Xmx768m"

COPY hoj-springboot/ ./

RUN --mount=type=cache,id=hoj-java8-maven,target=/root/.m2,sharing=locked mvn -B -pl JudgeServer -am package -DskipTests

# 第二阶段：复用包含编译器和沙箱的运行环境
FROM registry.cn-shenzhen.aliyuncs.com/hcode/hoj_judgeserver:latest

WORKDIR /judge/server

# 用本地源码生成的程序替换原镜像中的程序
COPY --from=builder /build/JudgeServer/target/hoj-judgeServer-4.6.jar /judge/server/app.jar

EXPOSE 8088
