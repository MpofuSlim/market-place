# syntax=docker/dockerfile:1.7
# Build from repo root:
#     docker build -t marketplace-service .

FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN for i in 1 2 3; do ./mvnw -B -ntp -version && break || sleep 15; done

RUN ./mvnw -B -ntp -DskipTests dependency:go-offline || true

COPY src src
RUN ./mvnw -B -ntp -DskipTests package spring-boot:repackage \
    && cp target/marketplace-service-*.jar /workspace/app.jar

FROM eclipse-temurin:25-jre-alpine AS runtime
# Blanket upgrade inside the pinned Alpine branch so security point-releases
# flow without hand-listing packages (fleet convention; the Release Trivy gate
# still fails the build on anything CRITICAL/HIGH left unfixed).
# PLUS a version FLOOR per CVE the gate has caught: the blanket RUN string never
# changes, so buildx replays the CACHED layer (GHA build cache) and the upgrade
# never re-runs when Alpine ships a fix. Raising a floor changes the
# instruction (busting the cache) AND makes apk fail loudly if the fix is not
# reachable. Raise the floor when Trivy reports a new CVE.
# libexpat >= 2.8.5-r0
#   CVE-2026-93990 (HIGH) — XML injection via malformed UTF-16 input. The
#   Release for the #64 merge failed Trivy on the cached 2.8.4-r0.
RUN apk update && apk --no-cache upgrade \
 && apk add --no-cache --upgrade 'libexpat>=2.8.5-r0'
RUN addgroup -S app && adduser -S -G app app \
    && mkdir -p /app \
    && chown -R app:app /app
WORKDIR /app
USER app

COPY --from=builder --chown=app:app /workspace/app.jar /app/app.jar

EXPOSE 8087

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8087/actuator/health || exit 1

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
