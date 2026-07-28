# syntax=docker/dockerfile:1
#
# Build context is the repository root (see deploy/compose.yaml), because
# this image needs the multi-module Maven project, the tracked Android
# template catalog (local-hub/templates/android), and the pinned template
# compiler tooling (deploy/tooling) alongside the backend module.

# --- Build stage -------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY backend/pom.xml backend/pom.xml
RUN ./mvnw -q -pl backend -am -DskipTests dependency:go-offline

COPY backend backend
RUN ./mvnw -q -pl backend -am -DskipTests package \
    && cp backend/target/opshub-backend-*.jar /workspace/app.jar

# --- Runtime stage -------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# Node.js + npm are required at runtime: the generation package
# (com.opshub.generation.application.FileSystemTemplateReadinessValidator)
# shells out to `node <tsc.js> --noEmit` to type-check every rendered
# Android template before a generated case is allowed to become READY.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && apt-get purge -y gnupg \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

# Pinned uid/gid (not just -r's "next available system id") so bind-mounted host
# directories (OPSHUB_DATA_DIR, see deploy/compose.yaml) can be chowned to a stable
# owner across image rebuilds - see deploy/scripts/preflight.sh section that chowns
# to this same 999:999.
RUN groupadd -r -g 999 opshub && useradd -r -u 999 -g opshub -d /opt/opshub -s /usr/sbin/nologin opshub

WORKDIR /opt/opshub

COPY --from=build /workspace/app.jar /opt/opshub/app.jar

# Tracked, checksum-verified Android and Web template catalogs — mounted
# read-only at runtime so FileSystemTemplateReadinessValidator can render
# and type-check each of the five controlled templates per platform.
COPY local-hub/templates/android /opt/opshub/templates/android
COPY local-hub/templates/web /opt/opshub/templates/web

# Pinned template-compiler tooling (TypeScript + WebdriverIO ambient
# types). Installed once at image build time so no dependency resolution
# happens at request time in production.
COPY deploy/tooling/package.json /opt/opshub/tooling/package.json
RUN cd /opt/opshub/tooling \
    && npm install --omit=dev --no-audit --no-fund \
    && npm cache clean --force

RUN mkdir -p /var/lib/opshub/evidence \
    && chown -R opshub:opshub /opt/opshub /var/lib/opshub

ENV OPSHUB_TEMPLATE_ROOT=/opt/opshub/templates/android \
    OPSHUB_WEB_TEMPLATE_ROOT=/opt/opshub/templates/web \
    OPSHUB_NODE_EXECUTABLE=/usr/bin/node \
    OPSHUB_TSC_ENTRY=/opt/opshub/tooling/node_modules/typescript/lib/tsc.js \
    OPSHUB_TSC_TIMEOUT=30s \
    OPSHUB_TEMPLATE_CATALOG_VERSION=android-v1 \
    OPSHUB_WEB_TEMPLATE_CATALOG_VERSION=web-v1 \
    OPSHUB_EVIDENCE_ROOT=/var/lib/opshub/evidence

USER opshub

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=6 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/opt/opshub/app.jar"]
