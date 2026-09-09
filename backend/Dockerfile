# Backend production image — multi-stage JVM build (Spring Boot layered bootJar).
#
# Stage 1 compiles the executable jar with Maven (~30-60s on a warm dependency
# cache). Stage 2 is a Temurin JRE 25 runtime that runs the layered jar as
# nonroot. Spring Boot's layered jar is extracted into separate Docker layers
# (dependencies / spring-boot-loader / snapshot-dependencies / application) so a
# code-only change reuses the cached dependency layers across deploys.
#
# Build:  docker build -t tessary ./backend
# Run:    docker run --rm -p 8080:8080 tessary

# ---- builder ----
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

# .mvn/jvm.config carries the --add-exports/--add-opens that Error Prone needs to
# reach the javac internals during compilation; without it the compiler plugin
# throws IllegalAccessError. Copy it before any mvn invocation.
COPY .mvn ./.mvn

# Prefetch deps as a cached layer (only re-runs when a pom changes). Every module pom is copied
# before any source, so go-offline resolves the whole reactor and a source-only edit reuses it.
# A module added to the aggregator must be added here too, or its deps are fetched at build time.
COPY pom.xml ./
COPY shared/pom.xml ./shared/pom.xml
COPY contract/pom.xml ./contract/pom.xml
COPY test-support/pom.xml ./test-support/pom.xml
COPY core/pom.xml ./core/pom.xml
COPY tenancy/pom.xml ./tenancy/pom.xml
COPY substrate/pom.xml ./substrate/pom.xml
COPY product/pom.xml ./product/pom.xml
COPY llm-runtime/pom.xml ./llm-runtime/pom.xml
COPY analysis/pom.xml ./analysis/pom.xml
COPY surfaces/pom.xml ./surfaces/pom.xml
COPY app/pom.xml ./app/pom.xml
RUN mvn -B -q dependency:go-offline

# Every module's sources: `app` is only the assembly, and the reactor builds each jar it depends
# on at `package` time. `core/tools/` holds the build-time schema-column generator that the
# generate-sources phase runs over core's Liquibase changelog, so it ships alongside the sources.
COPY config ./config
COPY shared/src ./shared/src
COPY contract/src ./contract/src
COPY test-support/src ./test-support/src
COPY core/src ./core/src
COPY tenancy/src ./tenancy/src
COPY substrate/src ./substrate/src
COPY product/src ./product/src
COPY llm-runtime/src ./llm-runtime/src
COPY analysis/src ./analysis/src
COPY surfaces/src ./surfaces/src
COPY app/src ./app/src
COPY core/tools ./core/tools

# Build the executable (layered) bootJar; skip tests (CI runs the full gate).
RUN mvn -B -q -DskipTests package

# Extract the layered jar into per-layer directories so the runtime stage can
# COPY them as distinct, cache-friendly Docker layers. The reactor puts the jar in app/target.
RUN cp app/target/app-*-exec.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Pyroscope continuous-profiling agent. Fetched in the BUILDER so the runtime stage
# gains no curl and the download is layer-cached. Version pinned deliberately —
# floating it changes sampling behaviour without a code change, and it must match the
# io.pyroscope:agent dependency in backend/shared/pom.xml: the labels API is shared
# static state between the javaagent and the app, so a skew makes the tessary_* pool
# and tenant labels silently vanish with no error.
ARG PYROSCOPE_AGENT_VERSION=2.8.0
RUN curl -fsSL -o /pyroscope.jar \
      "https://github.com/grafana/pyroscope-java/releases/download/v${PYROSCOPE_AGENT_VERSION}/pyroscope.jar"

# ---- runtime ----
FROM eclipse-temurin:25-jre AS runtime

# The image's version identity (epic 5 clause 3, #1135): the repo's short-SHA convention, supplied by
# the build orchestrator (Taskfile IMAGE_VERSION / IMAGE_REVISION via docker-compose.yml's build args,
# or --build-arg). "dev" when nobody supplied one, so a bare `docker build` still labels the image and
# a paid build layered on it can compare. Written to a file as well as a LABEL because a derived image
# can COPY a file from its base and cannot read its base's labels.
ARG IMAGE_VERSION=dev
ARG IMAGE_REVISION=unknown
LABEL org.opencontainers.image.version=$IMAGE_VERSION \
      org.opencontainers.image.revision=$IMAGE_REVISION

WORKDIR /app
RUN printf '%s\n' "$IMAGE_VERSION" > /app/IMAGE_VERSION

# Run as an unprivileged user (the JRE base ships root by default).
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring:spring

# Copy the extracted layers most-stable-first so a code-only rebuild only
# invalidates the small `application` layer.
COPY --from=build --chown=spring:spring /app/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /app/extracted/application/ ./

# Profiling agent. All of its configuration is PYROSCOPE_* environment in
# docker-compose.yml, including PYROSCOPE_AGENT_ENABLED as the runtime kill switch —
# so disabling profiling never needs an image rebuild.
COPY --from=build --chown=spring:spring /pyroscope.jar /app/pyroscope.jar

EXPOSE 8080

# OTLP receive ports (image metadata only — EXPOSE does not itself publish; the
# orchestrator decides external exposure via compose/Caddy). The gRPC OTLP transport
# (#235) binds 4317 and the HTTP transport 4318, both default-off behind
# `tessary.ingest.otlp.enabled` + `transport=grpc|http|both`.
EXPOSE 4317 4318

# `-jar app.jar` runs the extracted application jar, whose manifest enumerates lib/ on its
# Class-Path; there is no launcher in this image (extract --layers ran without --launcher, so
# spring-boot-loader/ is empty). A derived image that needs more on the classpath uses `-cp`.
# -XX:+DebugNonSafepoints is what makes the flame graph trustworthy: without it the JIT
# only records line numbers at safepoints, so samples are attributed to the wrong frame
# ("safepoint bias") and you chase the wrong method. The agent is attached
# unconditionally; PYROSCOPE_AGENT_ENABLED=false turns profiling off at runtime.
#
# Heap: sized as a share of the container limit rather than inherited from whatever the host's Docker
# allocation happens to be. On a 2 GB dev VM the default ergonomics gave ~480 MB, which is a different
# machine from production and hides exactly the memory bugs that matter.
#
# OOM: dump, then exit. Surviving an OutOfMemoryError tells you nothing about whether the heap is still
# coherent, and this process learned the hard way what the alternative costs — an OOM landed on the lone
# substrate-writer thread, killed it, and ingest stopped for good while the container stayed up and
# reported healthy. Dying with a dump makes the orchestrator responsible for the restart and leaves
# evidence behind. Same pairing, same order, as Elasticsearch's shipped jvm.options.
ENTRYPOINT ["java", \
  "-javaagent:/app/pyroscope.jar", \
  "-XX:+UnlockDiagnosticVMOptions", \
  "-XX:+DebugNonSafepoints", \
  "-XX:MaxRAMPercentage=75", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "app.jar"]
