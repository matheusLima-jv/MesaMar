# BuildKit padrão; insumos base identificados por digest.

# ---------------------------------------------------------------------------
# Estágio 1: build dos fronts (Node)
# ---------------------------------------------------------------------------
FROM node:22-bookworm-slim@sha256:c3de60bf2f9dd0ac6370e6117950ff62d6e339527e7472301c9c78a017978392 AS frontend
WORKDIR /src

COPY web-staff/package.json web-staff/package-lock.json web-staff/
COPY web-customer/package.json web-customer/package-lock.json web-customer/
RUN cd web-staff && npm ci --no-audit --no-fund \
 && cd ../web-customer && npm ci --no-audit --no-fund

COPY eslint.config.mjs ./
COPY backend/src/main/resources/static/js backend/src/main/resources/static/js
COPY web-shared web-shared
COPY web-staff web-staff
COPY web-customer web-customer
RUN cd web-staff && npm run build \
 && cd ../web-customer && npm run build

# ---------------------------------------------------------------------------
# Estágio 2: build do backend (Gradle + JDK 21)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b AS backend
WORKDIR /src

# Wrapper e scripts de build primeiro, para aproveitar o cache de dependências.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY backend/build.gradle.kts backend/
RUN chmod +x gradlew && ./gradlew --no-daemon :backend:dependencies > /dev/null

COPY backend backend
COPY web-shared web-shared
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
FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
ARG MESAMAR_REVISION=desenvolvimento
LABEL org.opencontainers.image.revision=$MESAMAR_REVISION
ENV MESAMAR_REVISION=$MESAMAR_REVISION
WORKDIR /app
COPY deploy/compatibilidade.json /app/compatibilidade.json

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system mesamar \
 && useradd --system --gid mesamar --home /app mesamar \
 && mkdir -p /app/logs \
 && chown mesamar:mesamar /app/logs
COPY --from=backend /src/app.jar app.jar
USER mesamar

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
