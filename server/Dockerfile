# Build stage
FROM clojure:temurin-21-tools-deps AS builder

WORKDIR /app
COPY deps.edn ./
RUN clojure -P

COPY src ./src
COPY resources ./resources

# Create uberjar
RUN clojure -M -e "(compile 'tramando.server.core)"

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -S tramando && adduser -S tramando -G tramando

# Copy application
COPY --from=builder /app /app

# Create data directories
RUN mkdir -p /app/data/projects && chown -R tramando:tramando /app/data

# Switch to non-root user
USER tramando

# Environment variables
ENV PORT=3000
ENV TRAMANDO_DB_PATH=/app/data/tramando.db
ENV TRAMANDO_PROJECTS_PATH=/app/data/projects

EXPOSE 3000

# Volume for persistent data
VOLUME ["/app/data"]

CMD ["clojure", "-M:run"]
