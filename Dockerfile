# =============================================================================
# NTS MCP FileSystem Server - Docker Image
# =============================================================================
# Multi-stage build for optimized image size
#
# IMPORTANT: Docker Mode and Roots
# --------------------------------
# In Docker, the server cannot access host filesystem paths directly.
# You MUST explicitly mount directories and specify them via NTS_DOCKER_ROOTS.
# These roots will OVERRIDE any roots sent by the MCP client.
#
# Usage Examples:
#
#   Single project:
#     docker run -i --rm \
#       -v /home/user/myproject:/mnt/project \
#       -e NTS_DOCKER_ROOTS=/mnt/project \
#       nts-mcp-fs
#
#   Multiple projects:
#     docker run -i --rm \
#       -v /home/user/project1:/mnt/p1 \
#       -v /home/user/project2:/mnt/p2 \
#       -e NTS_DOCKER_ROOTS=/mnt/p1:/mnt/p2 \
#       nts-mcp-fs
#
# For MCP clients (Claude Desktop, Cursor), configure:
#   {
#     "command": "docker",
#     "args": [
#       "run", "-i", "--rm",
#       "-v", "/home/user/project:/mnt/project",
#       "-e", "NTS_DOCKER_ROOTS=/mnt/project",
#       "nts-mcp-fs"
#     ]
#   }
#
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Build
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /build

# Copy Gradle wrapper and build files first (for layer caching)
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY settings.gradle.kts ./
COPY app/build.gradle.kts app/

# Download dependencies (cached layer)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Copy source code
COPY app/src/ app/src/

# Build fat JAR
RUN ./gradlew shadowJar --no-daemon

# -----------------------------------------------------------------------------
# Stage 2: Runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine

LABEL org.opencontainers.image.title="NTS MCP FileSystem Server"
LABEL org.opencontainers.image.description="Transactional File System server for Model Context Protocol"
LABEL org.opencontainers.image.source="https://github.com/Nefrols/nts-mcp-fs"
LABEL org.opencontainers.image.licenses="Apache-2.0"

# Create non-root user for security
RUN addgroup -g 1000 nts && \
    adduser -u 1000 -G nts -s /bin/sh -D nts

WORKDIR /app

# Copy JAR from builder
COPY --from=builder /build/app/build/libs/app-all.jar /app/nts-mcp-fs.jar

# Create default mount point
RUN mkdir -p /mnt && chown nts:nts /mnt

# Switch to non-root user
USER nts

# Environment variables:
#   NTS_DOCKER_ROOTS - Colon-separated list of root paths inside container.
#                      These OVERRIDE client-provided roots (required for Docker).
#                      Must match your -v volume mount points.
#                      Example: /mnt/project1:/mnt/project2
#
#   JAVA_OPTS        - JVM options (default: ZGC with 512MB heap)
#   MCP_DEBUG        - Set to "true" for debug logging to stderr
#   MCP_LOG_FILE     - Path to log file for debugging

# ZGC is generational by default in Java 25+
ENV JAVA_OPTS="-XX:+UseZGC -Xmx512m"

# Health check (optional, for orchestrators)
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD pgrep -f "nts-mcp-fs.jar" || exit 1

# MCP servers communicate via stdio, so we need interactive mode
# The entrypoint uses exec to properly handle signals
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/nts-mcp-fs.jar"]
