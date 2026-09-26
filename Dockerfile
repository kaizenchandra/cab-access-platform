FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -DskipTests dependency:go-offline
COPY src src
COPY scripts/Healthcheck.java scripts/Healthcheck.java
RUN ./mvnw -B -DskipTests package && javac -d /build/health scripts/Healthcheck.java

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /build/target/cab-access-platform-0.1.0-SNAPSHOT.jar /app/app.jar
COPY --from=build /build/health /app/health
COPY infra/certs/global-bundle.pem /app/certs/global-bundle.pem
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD ["java", "-cp", "/app/health", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
