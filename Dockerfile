#syntax=docker/dockerfile:1

# === Build stage: Install tini and prepare application ===
FROM dhi.io/amazoncorretto:25.0.2-alpine3.22-dev AS builder

# Install tini for proper signal handling and process management
RUN apk add --no-cache tini

COPY target/leader-elector-*.jar /app/leader-elector.jar

WORKDIR /app

# === Final stage: Create minimal runtime image ===
FROM dhi.io/amazoncorretto:25.0.2-alpine3.22

WORKDIR /app

# Copy tini and application from the builder stage
COPY --from=builder /sbin/tini /sbin/tini
COPY --from=builder /app/leader-elector.jar /app/leader-elector.jar

# Use tini as init system to handle signals and reap zombie processes
ENTRYPOINT ["/sbin/tini", "--"]

# Configure JVM for container environment:
# - UseContainerSupport: Detect container memory/CPU limits
# - MaxRAMPercentage: Use up to 75% of container memory for heap
# - InitialRAMPercentage: Start with 50% of container memory for heap
# - +ExitOnOutOfMemoryError: Exit cleanly on OOM instead of hanging
# - +HeapDumpOnOutOfMemoryError: Create heap dump on OOM for debugging
# - HeapDumpPath: Location for heap dumps
CMD ["java", \
     "-XX:+UseContainerSupport", \
     "-XX:MaxRAMPercentage=75.0", \
     "-XX:InitialRAMPercentage=50.0", \
     "-XX:+ExitOnOutOfMemoryError", \
     "-XX:+HeapDumpOnOutOfMemoryError", \
     "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
     "-jar", \
     "leader-elector.jar"]
