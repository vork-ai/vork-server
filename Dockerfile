# =============================================================================
# Vork — Dockerfile
#
# Build context must be the PARENT directory (vork/) so that jadaptive-orm/
# and vork-prototype/ are both available:
#
#   docker build -f vork-prototype/Dockerfile -t vork ..
#
# Or simply: docker compose up --build  (docker-compose.yml sets context: ..)
# =============================================================================

# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build

# Install Maven (Ubuntu Noble base)
RUN apt-get update -q && apt-get install -y -q maven && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# ── jadaptive-orm — install into the local Maven repo ────────────────────────
COPY jadaptive-orm/pom.xml jadaptive-orm/pom.xml
COPY jadaptive-orm/src     jadaptive-orm/src
RUN cd jadaptive-orm && mvn -q -Dmaven.test.skip=true install

# ── vork-prototype — resolve dependencies then build ─────────────────────────
COPY vork-prototype/pom.xml vork-prototype/pom.xml
# Pre-fetch deps as a separate layer so source changes don't re-download
RUN cd vork-prototype && mvn -q -Dmaven.test.skip=true dependency:resolve || true

COPY vork-prototype/src vork-prototype/src
RUN cd vork-prototype && mvn -q -Dmaven.test.skip=true package

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
# Full JDK required at runtime — javax.tools.JavaCompiler is used to compile
# user-defined types on the fly.
FROM eclipse-temurin:25-jdk

WORKDIR /app

COPY --from=build /workspace/vork-prototype/target/vork-*.jar app.jar

# conf.d/ holds database.properties and the ssl/ sub-directory with PEM certs.
# Mount a volume or use MONGO_* / VORK_SSL_CERT_DIR env vars to override.
RUN mkdir -p conf.d/ssl

# HTTP (redirect to HTTPS) + HTTPS
EXPOSE 8080 8443

# Set VORK_SSL_CERT_DIR to override the certificate directory (default: conf.d/ssl).
# Set SERVER_PORT to override the HTTPS port (default: 8443).
# Set VORK_SSL_HTTP_PORT to override the HTTP redirect port (default: 8080).
ENV VORK_SSL_CERT_DIR=""

ENTRYPOINT ["java", "-jar", "app.jar"]
