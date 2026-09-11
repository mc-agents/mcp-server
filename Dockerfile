ARG JDK_IMAGE=eclipse-temurin:25-jdk-noble
ARG JRE_IMAGE=eclipse-temurin:25-jre-noble

FROM ${JDK_IMAGE} AS build
WORKDIR /app
# The wrapper and the dependency declarations first, so a source-only change reuses the download.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties VERSION ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY catalog ./catalog
COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM ${JRE_IMAGE} AS runtime
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN useradd --system --uid 10001 mcagents
USER 10001
# 3000 takes MCP over HTTP; 8765 is where bots dial in.
EXPOSE 3000 8765
# bash, not sh: the image has no curl, and /dev/tcp is a bash feature that dash does not have.
# Kubernetes ignores this and uses the probes in the pod spec; it is here for docker run.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${MCP_PORT:-3000} && printf 'GET /actuator/health/liveness HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q 200"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
