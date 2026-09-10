FROM maven:3.9-eclipse-temurin-8 AS builder

WORKDIR /build
COPY hoj-springboot/ ./

RUN mvn -B -pl DataBackup -am package -DskipTests

FROM eclipse-temurin:8-jre

WORKDIR /app

COPY --from=builder /build/DataBackup/target/hoj-backend-4.6.jar /app/app.jar

EXPOSE 6688

ENTRYPOINT ["java", "-jar", "/app/app.jar"]