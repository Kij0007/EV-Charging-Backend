FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress clean verify

FROM eclipse-temurin:17-jre
RUN groupadd --system evcharging && useradd --system --gid evcharging --home-dir /app evcharging
WORKDIR /app
COPY --from=build --chown=evcharging:evcharging /build/target/*.jar /app/evcharging.jar
USER evcharging
EXPOSE 8081
ENV SERVER_ADDRESS=0.0.0.0
# Leave native/metaspace/thread headroom on the free 512 MB demo instance.
# Spring reads the host's PORT, or SERVER_PORT when explicitly supplied.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50.0", "-jar", "/app/evcharging.jar"]
