# =============================================================================
# Tramando - Dockerfile per webapp collaborativa
# =============================================================================
# Questo Dockerfile costruisce un'immagine che contiene sia il frontend
# ClojureScript che il backend Clojure. NON usare per la versione Tauri.
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Build frontend ClojureScript
# -----------------------------------------------------------------------------
# Nota: shadow-cljs richiede Java, quindi usiamo eclipse-temurin con Node
FROM eclipse-temurin:21-jdk-alpine AS frontend-builder

WORKDIR /app

# Install Node.js
RUN apk add --no-cache nodejs npm

# Install dependencies
COPY package*.json ./
RUN npm ci

# Copy frontend source
COPY shadow-cljs.edn ./
COPY src ./src
COPY public ./public

# Build for production (release mode)
RUN npx shadow-cljs release app

# -----------------------------------------------------------------------------
# Stage 2: Build backend Clojure
# -----------------------------------------------------------------------------
FROM clojure:temurin-21-tools-deps AS backend-builder

WORKDIR /app

# Download dependencies first (better caching)
COPY server/deps.edn ./
RUN clojure -P

# Copy backend source
COPY server/src ./src
COPY server/resources ./resources

# Pre-compile for faster startup
RUN clojure -M -e "(compile 'tramando.server.core)" || true

# -----------------------------------------------------------------------------
# Stage 3: Runtime image
# -----------------------------------------------------------------------------
# Nota: usiamo l'immagine clojure alpine per avere il comando clojure funzionante
FROM clojure:temurin-21-tools-deps-alpine

WORKDIR /app

# Install required tools
RUN apk add --no-cache bash curl

# Create non-root user
RUN addgroup -S tramando && adduser -S tramando -G tramando

# Copy Clojure deps and application
COPY --from=backend-builder /root/.m2 /root/.m2
COPY --from=backend-builder /app /app/server
RUN chown -R tramando:tramando /app/server

# Copy built frontend
COPY --from=frontend-builder /app/public /app/public
RUN chown -R tramando:tramando /app/public

# Create data directory
RUN mkdir -p /data/projects && chown -R tramando:tramando /data

# Copy startup script
COPY docker-entrypoint.sh /app/
RUN chmod +x /app/docker-entrypoint.sh

# Environment variables with defaults
ENV PORT=3000
ENV BASE_PATH=""
ENV DATA_DIR=/data
ENV TRAMANDO_DB_PATH=/data/tramando.db
ENV TRAMANDO_PROJECTS_PATH=/data/projects
ENV TRAMANDO_JWT_SECRET=change-me-in-production
ENV TRAMANDO_ALLOW_REGISTRATION=true

EXPOSE 3000

VOLUME ["/data"]

# Healthcheck (start-period aumentato per dare tempo al server di avviarsi)
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:${PORT}/api/me || exit 1

ENTRYPOINT ["/app/docker-entrypoint.sh"]
