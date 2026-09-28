# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Estágio 1: build dos fronts (Node)
# ---------------------------------------------------------------------------
FROM node:22-bookworm-slim AS frontend
WORKDIR /src

COPY web-staff/package.json web-staff/package-lock.json web-staff/
COPY web-customer/package.json web-customer/package-lock.json web-customer/
RUN cd web-staff && npm ci --no-audit --no-fund \
 && cd ../web-customer && npm ci --no-audit --no-fund

COPY web-staff web-staff
COPY web-customer web-customer
RUN cd web-staff && npm run build \
 && cd ../web-customer && npm run build

# ---------------------------------------------------------------------------
# Estágio 2: build do backend (Gradle + JDK 21)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /src

# Wrapper e scripts de build primeiro, para aproveitar o cache de dependências.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY backend/build.gradle.kts backend/
RUN chmod +x gradlew && ./gradlew --no-daemon :backend:dependencies > /dev/null

COPY backend backend
COPY web-staff web-staff
COPY web-customer web-customer
# Os fronts já foram buildados no estágio 1: o Gradle só copia o dist.
COPY --from=frontend /src/web-staff/dist web-staff/dist
COPY --from=frontend /src/web-customer/dist web-customer/dist
RUN ./gradlew --no-daemon :backend:bootJar -PskipFrontendBuild \
 && cp backend/build/libs/backend-*.jar /src/app.jar

# ---------------------------------------------------------------------------
# Estágio 3: imagem final (somente JRE, usuário sem privilégios)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system mesamar \
 && useradd --system --gid mesamar --home /app mesamar
COPY --from=backend /src/app.jar app.jar
USER mesamar

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
