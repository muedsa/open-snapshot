#!/bin/sh
set -eu

set -- "-config=${APP_CONFIG_FILE:-/app/config/application.yaml}"

if [ -n "${SNAPSHOT_MAX_REQUEST_SIZE:-}" ]; then
    set -- "$@" "-P:snapshot.max-request-size=${SNAPSHOT_MAX_REQUEST_SIZE}"
fi
if [ -n "${SNAPSHOT_MAX_CONCURRENT_RENDERS:-}" ]; then
    set -- "$@" "-P:snapshot.max-concurrent-renders=${SNAPSHOT_MAX_CONCURRENT_RENDERS}"
fi
if [ -n "${SNAPSHOT_MAX_RENDER_TIMEOUT_MS:-}" ]; then
    set -- "$@" "-P:snapshot.max-render-timeout-ms=${SNAPSHOT_MAX_RENDER_TIMEOUT_MS}"
fi
if [ -n "${SNAPSHOT_MAX_RENDER_QUEUE:-}" ]; then
    set -- "$@" "-P:snapshot.max-render-queue=${SNAPSHOT_MAX_RENDER_QUEUE}"
fi
if [ -n "${SNAPSHOT_RENDER_QUEUE_TIMEOUT_MS:-}" ]; then
    set -- "$@" "-P:snapshot.render-queue-timeout-ms=${SNAPSHOT_RENDER_QUEUE_TIMEOUT_MS}"
fi
if [ -n "${SNAPSHOT_RENDER_CACHE_ENABLED:-}" ]; then
    set -- "$@" "-P:snapshot.render-cache.enabled=${SNAPSHOT_RENDER_CACHE_ENABLED}"
fi
if [ -n "${SNAPSHOT_RENDER_CACHE_MAX_ENTRIES:-}" ]; then
    set -- "$@" "-P:snapshot.render-cache.max-entries=${SNAPSHOT_RENDER_CACHE_MAX_ENTRIES}"
fi
if [ -n "${SNAPSHOT_RENDER_CACHE_TTL_MS:-}" ]; then
    set -- "$@" "-P:snapshot.render-cache.ttl-ms=${SNAPSHOT_RENDER_CACHE_TTL_MS}"
fi
if [ -n "${SNAPSHOT_ACCESS_LOG_ENABLED:-}" ]; then
    set -- "$@" "-P:snapshot.access-log-enabled=${SNAPSHOT_ACCESS_LOG_ENABLED}"
fi
if [ -n "${SNAPSHOT_METRICS_ENABLED:-}" ]; then
    set -- "$@" "-P:snapshot.metrics-enabled=${SNAPSHOT_METRICS_ENABLED}"
fi
if [ -n "${SNAPSHOT_SHUTDOWN_GRACE_MS:-}" ]; then
    set -- "$@" "-P:ktor.deployment.shutdownGracePeriod=${SNAPSHOT_SHUTDOWN_GRACE_MS}"
fi
if [ -n "${SNAPSHOT_SHUTDOWN_TIMEOUT_MS:-}" ]; then
    set -- "$@" "-P:ktor.deployment.shutdownTimeout=${SNAPSHOT_SHUTDOWN_TIMEOUT_MS}"
fi
if [ -n "${SNAPSHOT_RATE_LIMIT_REQUESTS:-}" ]; then
    set -- "$@" "-P:snapshot.rate-limit.requests=${SNAPSHOT_RATE_LIMIT_REQUESTS}"
fi
if [ -n "${SNAPSHOT_RATE_LIMIT_WINDOW_MS:-}" ]; then
    set -- "$@" "-P:snapshot.rate-limit.window-ms=${SNAPSHOT_RATE_LIMIT_WINDOW_MS}"
fi
if [ -n "${SNAPSHOT_RATE_LIMIT_CREDENTIAL_REQUESTS:-}" ]; then
    set -- "$@" "-P:snapshot.rate-limit.credential-requests=${SNAPSHOT_RATE_LIMIT_CREDENTIAL_REQUESTS}"
fi
if [ -n "${SNAPSHOT_RATE_LIMIT_CREDENTIAL_WINDOW_MS:-}" ]; then
    set -- "$@" "-P:snapshot.rate-limit.credential-window-ms=${SNAPSHOT_RATE_LIMIT_CREDENTIAL_WINDOW_MS}"
fi
if [ -n "${SNAPSHOT_RATE_LIMIT_ADMIN_REQUESTS:-}" ]; then
    set -- "$@" "-P:snapshot.rate-limit.admin-requests=${SNAPSHOT_RATE_LIMIT_ADMIN_REQUESTS}"
fi
if [ -n "${SNAPSHOT_RATE_LIMIT_ADMIN_WINDOW_MS:-}" ]; then
    set -- "$@" "-P:snapshot.rate-limit.admin-window-ms=${SNAPSHOT_RATE_LIMIT_ADMIN_WINDOW_MS}"
fi
if [ -n "${SNAPSHOT_METRICS_ACCESS:-}" ]; then
    set -- "$@" "-P:snapshot.metrics-access=${SNAPSHOT_METRICS_ACCESS}"
fi
if [ -n "${SNAPSHOT_MAX_CANVAS_WIDTH:-}" ]; then
    set -- "$@" "-P:snapshot.max-canvas-width=${SNAPSHOT_MAX_CANVAS_WIDTH}"
fi
if [ -n "${SNAPSHOT_MAX_CANVAS_HEIGHT:-}" ]; then
    set -- "$@" "-P:snapshot.max-canvas-height=${SNAPSHOT_MAX_CANVAS_HEIGHT}"
fi
if [ -n "${SNAPSHOT_MAX_CANVAS_PIXELS:-}" ]; then
    set -- "$@" "-P:snapshot.max-canvas-pixels=${SNAPSHOT_MAX_CANVAS_PIXELS}"
fi
if [ -n "${SNAPSHOT_TRUST_PROXY_HEADERS:-}" ]; then
    set -- "$@" "-P:snapshot.trust-proxy-headers=${SNAPSHOT_TRUST_PROXY_HEADERS}"
fi
if [ -n "${SNAPSHOT_ADMIN_ENDPOINTS_ENABLED:-}" ]; then
    set -- "$@" "-P:snapshot.admin-endpoints-enabled=${SNAPSHOT_ADMIN_ENDPOINTS_ENABLED}"
fi
if [ -n "${SNAPSHOT_MAX_IMAGE_NUM:-}" ]; then
    set -- "$@" "-P:snapshot.image.max-image-num-once=${SNAPSHOT_MAX_IMAGE_NUM}"
fi
if [ -n "${SNAPSHOT_MAX_SINGLE_IMAGE_SIZE:-}" ]; then
    set -- "$@" "-P:snapshot.image.max-single-image-size=${SNAPSHOT_MAX_SINGLE_IMAGE_SIZE}"
fi
if [ -n "${SNAPSHOT_MEMORY_CACHE_NUM_LIMIT:-}" ]; then
    set -- "$@" "-P:snapshot.image.memory-cache-num-limit=${SNAPSHOT_MEMORY_CACHE_NUM_LIMIT}"
fi
if [ -n "${SNAPSHOT_MAX_CACHE_BYTES:-}" ]; then
    set -- "$@" "-P:snapshot.image.max-cache-bytes=${SNAPSHOT_MAX_CACHE_BYTES}"
fi
if [ -n "${SNAPSHOT_MAX_IMAGE_WIDTH:-}" ]; then
    set -- "$@" "-P:snapshot.image.max-image-width=${SNAPSHOT_MAX_IMAGE_WIDTH}"
fi
if [ -n "${SNAPSHOT_MAX_IMAGE_HEIGHT:-}" ]; then
    set -- "$@" "-P:snapshot.image.max-image-height=${SNAPSHOT_MAX_IMAGE_HEIGHT}"
fi
if [ -n "${SNAPSHOT_MAX_IMAGE_PIXELS:-}" ]; then
    set -- "$@" "-P:snapshot.image.max-image-pixels=${SNAPSHOT_MAX_IMAGE_PIXELS}"
fi
if [ -n "${SNAPSHOT_ALLOW_PRIVATE_HOSTS:-}" ]; then
    set -- "$@" "-P:snapshot.image.allow-private-hosts=${SNAPSHOT_ALLOW_PRIVATE_HOSTS}"
fi
if [ -n "${SNAPSHOT_IMAGE_CONNECT_TIMEOUT_MS:-}" ]; then
    set -- "$@" "-P:snapshot.image.connect-timeout-ms=${SNAPSHOT_IMAGE_CONNECT_TIMEOUT_MS}"
fi
if [ -n "${SNAPSHOT_IMAGE_READ_TIMEOUT_MS:-}" ]; then
    set -- "$@" "-P:snapshot.image.read-timeout-ms=${SNAPSHOT_IMAGE_READ_TIMEOUT_MS}"
fi
if [ -n "${SNAPSHOT_IMAGE_CACHE_TTL_MS:-}" ]; then
    set -- "$@" "-P:snapshot.image.cache-ttl-ms=${SNAPSHOT_IMAGE_CACHE_TTL_MS}"
fi
if [ -n "${SNAPSHOT_IMAGE_MAX_RETRIES:-}" ]; then
    set -- "$@" "-P:snapshot.image.max-retries=${SNAPSHOT_IMAGE_MAX_RETRIES}"
fi
if [ -n "${SNAPSHOT_IMAGE_RETRY_BACKOFF_MS:-}" ]; then
    set -- "$@" "-P:snapshot.image.retry-backoff-ms=${SNAPSHOT_IMAGE_RETRY_BACKOFF_MS}"
fi
# SNAPSHOT_API_KEY、SNAPSHOT_API_KEYS 与 SNAPSHOT_ADMIN_TOKEN 一样，由应用直接读取环境变量，
# 不转换为命令行参数，避免密钥出现在进程列表中。

exec java -jar /app/open-snapshot.jar "$@"
