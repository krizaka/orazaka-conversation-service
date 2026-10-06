# ==============================================================================
# ORAZAKA — Dockerfile (Conversation Service)
# ==============================================================================
# Multi-stage container build targeting Eclipse Temurin JRE 21.
# Compiles the service + its orazaka-libs dependencies hermetically.
# Respects zero-trust execution rules by enforcing a non-root user.
# NOTE: containerization is deferred (AGENTS.md §0). The reactor now spans
# multiple services; a full build needs every <module> present (e.g. COPY . .).
# ==============================================================================

# ── Build Stage ──────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Copy configuration and source files. The reactor aggregator (pom.xml) references
# every module, so the sibling modules must exist for reactor construction even
# though `-pl orazaka-apps/services/orazaka-conversation-service -am` only builds
# the service + its dependencies.
COPY pom.xml .
COPY orazaka-libs orazaka-libs
COPY orazaka-apps/services/orazaka-conversation-service orazaka-apps/services/orazaka-conversation-service
COPY orazaka-apps/workers orazaka-apps/workers
COPY orazaka-end2end orazaka-end2end

# Compile the conversation-service module and its local orazaka-libs dependencies
RUN mvn clean package -pl orazaka-apps/services/orazaka-conversation-service -am -DskipTests

# ── Runtime Stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create a non-privileged system group and user
RUN addgroup -S orazaka && adduser -S orazaka -G orazaka

# Configure secure JVM options for containerized environments
ENV JAVA_OPTS="-XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxRAMPercentage=75.0 -XX:MinRAMPercentage=50.0 -Djava.security.egd=file:/dev/./urandom"
ENV PORT=8080

# Expose HTTP port
EXPOSE 8080

# Copy executable jar from builder stage
COPY --from=builder /app/orazaka-apps/services/orazaka-conversation-service/target/orazaka-conversation-service-*.jar app.jar

# Enforce secure ownership on workspace files
RUN chown -R orazaka:orazaka /app

# Switch context to the non-root user
USER orazaka

# Launch the Spring Boot application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
