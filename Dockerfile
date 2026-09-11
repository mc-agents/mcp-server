ARG JRE_IMAGE=eclipse-temurin:25-jre-noble

# The jar is the build's, not this file's. A Gradle build inside a multi-architecture image build
# runs once per architecture under emulation, for a jar that is the same bytes either way, and it
# turned a two-minute publish into one that had not finished in twelve.
#
#   ./gradlew bootJar && docker build .
#
FROM ${JRE_IMAGE}
WORKDIR /app
ARG JAR=build/libs/app.jar
COPY ${JAR} app.jar
RUN useradd --system --uid 10001 mcagents
USER 10001
# 3000 takes MCP over HTTP; 8765 is where bots dial in.
EXPOSE 3000 8765
# bash, not sh: the image has no curl, and /dev/tcp is a bash feature that dash does not have.
# Kubernetes ignores this and uses the probes in the pod spec; it is here for docker run.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${MCP_PORT:-3000} && printf 'GET /actuator/health/liveness HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q 200"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
