# syntax=docker/dockerfile:1

# ---- Build ---------------------------------------------------------------
# JVM output is platform-independent, so compile natively once and reuse it for every target platform.
FROM --platform=$BUILDPLATFORM gradle:9.9.2-jdk21 AS build
WORKDIR /src

# Resolve dependencies in their own layer so source edits don't re-download them.
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle dependencies --no-daemon -q > /dev/null

COPY src ./src
RUN gradle installDist --no-daemon -q

# ---- Runtime -------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system orbit \
 && useradd --system --gid orbit --home-dir /app --shell /usr/sbin/nologin orbit \
 && mkdir -p /data \
 && chown orbit:orbit /data

WORKDIR /app
COPY --from=build --chown=orbit:orbit /src/build/install/orbit ./

ENV ORBIT_DATA_DIR=/data \
    ORBIT_HTTP_PORT=7340 \
    ORBIT_DISCOVERY_PORT=7341 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Users, devices, settings backups (and later, Marketplace and file shares) live here.
VOLUME ["/data"]

# 7340/tcp: dashboard, device API and WebSocket signalling
# 7341/udp: LAN discovery ("who is Orbit?")
EXPOSE 7340/tcp 7341/udp

USER orbit

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${ORBIT_HTTP_PORT}/health" || exit 1

ENTRYPOINT ["/app/bin/orbit"]
