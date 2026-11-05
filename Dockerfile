# Builds the Revenant server and the demo worker as container images (ADR-0016).
#
#   docker build --target server       -t revenant-server .
#   docker build --target demo-worker  -t revenant-demo-worker .
#
# Multi-stage: the JDK and Gradle stay in the build stage; the final images hold only a JRE and
# the jars. The base images are multi-architecture, so this builds on x86 and on the ARM
# machines Oracle Cloud's free tier offers.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# A Windows checkout can leave Windows line endings in the wrapper script; strip them.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew \
    && ./gradlew --no-daemon -x test :revenant-server:bootJar :revenant-demo:installDist \
    && cp "$(ls revenant-server/build/libs/*.jar | grep -v -- '-plain')" /server.jar

FROM eclipse-temurin:21-jre AS server
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 revenant
COPY --from=build /server.jar /app/server.jar
USER revenant
# Leave headroom for the JVM's own memory on a small machine.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]

FROM eclipse-temurin:21-jre AS demo-worker
RUN useradd --system --uid 10001 revenant && mkdir /data && chown revenant /data
COPY --from=build /src/revenant-demo/build/install/revenant-demo/lib /app/lib
USER revenant
VOLUME /data
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40"
# Arguments: server URL, ledger file. The API key comes from the REVENANT_API_KEY variable.
ENTRYPOINT ["java", "-cp", "/app/lib/*", "io.github.amitreddy14.revenant.demo.DemoWorker"]
CMD ["http://server:8080", "/data/ledger.txt"]
