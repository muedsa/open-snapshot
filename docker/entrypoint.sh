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
if [ -n "${SNAPSHOT_MAX_CANVAS_WIDTH:-}" ]; then
    set -- "$@" "-P:snapshot.max-canvas-width=${SNAPSHOT_MAX_CANVAS_WIDTH}"
fi
if [ -n "${SNAPSHOT_MAX_CANVAS_HEIGHT:-}" ]; then
    set -- "$@" "-P:snapshot.max-canvas-height=${SNAPSHOT_MAX_CANVAS_HEIGHT}"
fi
if [ -n "${SNAPSHOT_MAX_CANVAS_PIXELS:-}" ]; then
    set -- "$@" "-P:snapshot.max-canvas-pixels=${SNAPSHOT_MAX_CANVAS_PIXELS}"
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

exec java -jar /app/open-snapshot.jar "$@"
