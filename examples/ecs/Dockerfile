# Build the host Boot application first and place its executable JAR beside this file.
# Pin an approved Java 17 runtime digest in your deployment pipeline.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --chown=10001:10001 host.jar /app/host.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/host.jar"]
