#syntax=docker/dockerfile:1

FROM dhi.io/amazoncorretto:25.0.4-alpine3.24-dev AS builder

RUN apk add --no-cache tini

COPY target/leader-elector-*.jar /app/leader-elector.jar

WORKDIR /app

FROM dhi.io/amazoncorretto:25.0.4-alpine3.24

WORKDIR /app

COPY --from=builder /sbin/tini /sbin/tini
COPY --from=builder /app/leader-elector.jar /app/leader-elector.jar

# Use tini as init system to handle signals and reap zombie processes
ENTRYPOINT ["/sbin/tini", "--"]

# Heap follows the container's memory limit rather than the host's, and an OOM exits so
# Kubernetes restarts the pod instead of letting it thrash; the dump survives for diagnosis.
CMD ["java", \
     "-XX:+UseContainerSupport", \
     "-XX:MaxRAMPercentage=75.0", \
     "-XX:InitialRAMPercentage=50.0", \
     "-XX:+ExitOnOutOfMemoryError", \
     "-XX:+HeapDumpOnOutOfMemoryError", \
     "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
     "-jar", \
     "leader-elector.jar"]
