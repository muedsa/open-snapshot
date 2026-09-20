# Open Snapshot

Open Snapshot 是基于 Ktor 和 snapshot parser 的开放构图服务。

## API

### `POST /snapshot`

请求体为 snapshot DSL 文本，响应为 `image/png`、`image/jpeg` 或 `image/webp`。

```bash
curl -X POST http://localhost:8080/snapshot `
  -H "Content-Type: text/plain" `
  --data '<Snapshot type="png"><Container width="200" height="100" color="#FFFF0000"/></Snapshot>' `
  --output result.png
```

错误响应为 JSON，并包含 `code`、`message` 和 `requestId` 字段。服务支持空输入、请求体大小、并发渲染和网络图片资源限制。

### 其他接口

| 接口 | 说明 |
|---|---|
| `GET /health` | 存活检查：进程可响应即返回 `OK` |
| `GET /ready` | 就绪检查：渲染链路可用且未在排水时返回 `READY`，否则 `503` + `NOT_READY` |
| `GET /metrics` | Prometheus 文本指标（`text/plain; version=0.0.4`） |
| `GET /fonts` | 返回可用字体列表（管理接口） |
| `GET /fonts.png` | 返回字体预览图（管理接口） |
| `GET /cacheInfo` | 查看网络图片缓存统计（管理接口） |
| `POST /cacheClear` | 清理网络图片缓存（管理接口） |

所有响应都会带有 `X-Request-Id`（调用方传入的合法值会被沿用，否则生成 UUID），错误响应体中的
`requestId` 与之相同。

管理接口默认不注册。启用时必须同时设置强随机令牌，并通过
`Authorization: Bearer <token>` 访问：

```dotenv
SNAPSHOT_ADMIN_ENDPOINTS_ENABLED=true
SNAPSHOT_ADMIN_TOKEN=replace-with-a-long-random-token
```

## 运行配置

核心限制位于 `src/main/resources/application.yaml`：

```yaml
ktor:
  deployment:
    shutdownGracePeriod: 10000
    shutdownTimeout: 15000

snapshot:
  trust-proxy-headers: false
  max-request-size: 1048576
  max-concurrent-renders: 4
  max-render-timeout-ms: 30000
  access-log-enabled: true
  access-log-skip-paths:
    - /health
    - /ready
    - /metrics
  metrics-enabled: true
  rate-limit:
    requests: 6
    window-ms: 60000
  max-canvas-width: 4096
  max-canvas-height: 4096
  max-canvas-pixels: 16777216
  image:
    max-image-num-once: 10
    max-single-image-size: 5242880
    memory-cache-num-limit: 100
    max-cache-bytes: 268435456
    max-image-width: 4096
    max-image-height: 4096
    max-image-pixels: 16777216
    allow-private-hosts: false
```

配置在启动时校验，非法值会让服务直接启动失败，而不是运行中才暴露问题。

发布前验证：

```bash
./gradlew test
./gradlew build
```

## 可观测性与运维

### 存活与就绪

- `/health` 只表示进程还活着，不触发重启以外的判断，容器 `HEALTHCHECK` 使用它；
- `/ready` 会验证渲染链路：首次调用执行一次 1×1 冒烟渲染（解析 → 布局 → Skia → 编码），
  并检查字体环境与网络图片缓存是否就绪，通过后缓存结果。

因此负载均衡应使用 `/ready` 判断是否继续转发流量，用 `/health` 判断是否需要重启容器。

### 优雅停机与排水

应用监听 Ktor 的停机事件：

1. 收到 `SIGTERM`（`docker stop`、Kubernetes 终止）后先进入排水状态；
2. 排水期间 `/ready` 返回 `503 NOT_READY`，新的 `/snapshot` 请求返回 `503 SERVICE_UNAVAILABLE`
   并带 `Retry-After: 5`，已在执行的渲染继续完成；
3. 等待时间由 `ktor.deployment.shutdownGracePeriod` 决定，超时上限为
   `ktor.deployment.shutdownTimeout`。

Netty 引擎至少会等待 `shutdownGracePeriod`，所以不要把它设置得远大于实际渲染耗时。
默认值为 10s + 15s，Compose 中对应 `stop_grace_period: 30s`。若调大了
`snapshot.max-render-timeout-ms`，应同步调大排水时间与容器停止超时。

由于 Netty 在停机开始时就会关闭监听端口，单实例部署仍可能出现连接失败。
要做到零中断滚动更新，请运行两个及以上副本，并让负载均衡依据 `/ready` 摘除节点。

### 访问日志

`snapshot.access-log-enabled: true` 时每个请求输出一行结构化日志，探针路径默认不记录：

```text
event=snapshot.access requestId=df6577b3-... method=POST path=/snapshot status=200 durationMs=19 bytes=93
```

日志只包含请求 ID、方法、路径、状态码、耗时和响应字节数，不记录 DSL 与图片地址；
路径中的控制字符会被替换，避免日志注入。

### 指标

`GET /metrics` 输出 Prometheus 文本格式。`snapshot.metrics-enabled: false` 可以关闭该接口。

| 指标 | 类型 | 说明 |
|---|---|---|
| `snapshot_ready` / `snapshot_draining` | gauge | 当前就绪与排水状态 |
| `snapshot_uptime_seconds` | gauge | 进程运行时长 |
| `snapshot_render_in_flight` | gauge | 正在执行的渲染数 |
| `snapshot_renders_total{outcome}` | counter | 渲染结果：`success`、`empty_request`、`parse_error`、`render_error`、`image_error`、`too_large`、`timeout`、`rate_limited`、`unavailable`、`internal_error` |
| `snapshot_render_duration_seconds` | histogram | 渲染耗时分布 |
| `snapshot_render_output_bytes_total` | counter | 输出图片总字节数 |
| `snapshot_http_requests_total{path,method,status}` | counter | 各路由请求数与状态码 |
| `snapshot_http_request_duration_seconds{path}` | summary | 各路由请求耗时 |
| `snapshot_image_cache_hits_total` / `snapshot_image_cache_misses_total` | counter | 网络图片缓存命中与未命中 |
| `snapshot_image_downloads_total` / `snapshot_image_download_failures_total` | counter | 图片下载次数与失败次数 |
| `snapshot_image_cache_entries` / `snapshot_image_cache_bytes` | gauge | 缓存图片数量与估算占用 |

路径标签收敛在固定集合内（未知路径记为 `/other`），不会因外部输入产生高基数。

Prometheus 抓取示例（容器拓扑下走内部网络，Nginx 默认对公网返回 404）：

```yaml
scrape_configs:
  - job_name: open-snapshot
    metrics_path: /metrics
    static_configs:
      - targets: ["snapshot:8080"]
```

## Docker 部署

镜像在 Linux 构建阶段重新解析 Skiko 平台依赖，不能直接把 Windows 上生成的 fat JAR 放进 Linux 容器。
`snapshot` 当前从 GitHub Packages 获取，即使是公开包也需要拥有 `read:packages` 权限的 classic PAT。

先准备仅用于构建的 BuildKit secret：

```bash
mkdir -p .secrets
printf '%s' 'YOUR_GITHUB_USERNAME' > .secrets/gpr_user
printf '%s' 'YOUR_GITHUB_CLASSIC_PAT' > .secrets/gpr_key
```

`.secrets` 已被 Docker 构建上下文和 Git 忽略，凭据不会复制到镜像层。启动服务和 Nginx 反代：

```bash
docker compose up -d --build
docker compose ps
curl http://127.0.0.1/health
```

默认监听宿主机 `80` 端口。需要改端口时：

```bash
HTTP_PORT=8080 docker compose up -d
```

### 修改容器内配置

Compose 默认把仓库中的 `src/main/resources/application.yaml` 只读挂载到容器的
`/app/config/application.yaml`。可以复制一份生产配置并通过环境变量切换挂载文件：

```bash
cp src/main/resources/application.yaml docker/application.production.yaml
SNAPSHOT_CONFIG_FILE=./docker/application.production.yaml docker compose up -d
```

修改挂载文件后需要重启容器：

```bash
docker compose restart snapshot
```

标量配置也可以通过环境变量覆盖。先复制示例文件：

```bash
cp .env.example .env
```

例如：

```dotenv
HTTP_PORT=8080
SNAPSHOT_MAX_CONCURRENT_RENDERS=2
SNAPSHOT_MAX_RENDER_TIMEOUT_MS=20000
SNAPSHOT_RATE_LIMIT_REQUESTS=6
SNAPSHOT_RATE_LIMIT_WINDOW_MS=60000
SNAPSHOT_MAX_CANVAS_PIXELS=8388608
SNAPSHOT_MAX_IMAGE_NUM=5
SNAPSHOT_MAX_CACHE_BYTES=134217728
SNAPSHOT_ALLOW_PRIVATE_HOSTS=false
SNAPSHOT_ACCESS_LOG_ENABLED=true
SNAPSHOT_METRICS_ENABLED=true
SNAPSHOT_SHUTDOWN_GRACE_MS=10000
SNAPSHOT_SHUTDOWN_TIMEOUT_MS=15000
```

配置优先级为：非空环境变量 > 绑定挂载的 YAML > 镜像内默认 YAML。CORS 域名和字体族属于列表配置，建议直接修改挂载的 YAML。管理令牌可通过 `SNAPSHOT_ADMIN_TOKEN` 注入，不会被转换为 JVM 命令行参数。GitHub PAT 等构建秘密不要写入 `.env` 或 YAML，仍然使用 `.secrets` 中的 BuildKit secret。

容器拓扑为 `Internet -> Nginx -> open-snapshot:8080`，应用端口不会直接暴露到宿主机。Nginx 默认包含：

- 1 MiB 请求体限制；
- 每个客户端 IP 每分钟 6 个请求，允许瞬时突发 3 个；
- 35 秒上游读取超时；
- `X-Forwarded-*` 与 `X-Request-Id` 请求头；
- `/health` 与 `/ready` 探针转发（均不写访问日志）；
- `/metrics` 对公网返回 404，仅供内部网络抓取。

容器停机时 Docker 先发送 `SIGTERM`，Compose 通过 `stop_grace_period: 30s`
为应用的排水窗口留出时间；Kubernetes 部署时请相应设置 `terminationGracePeriodSeconds`。

应用默认不信任客户端传入的 Forwarded Header。Compose 会在应用端口仅对内部网络可见时设置
`SNAPSHOT_TRUST_PROXY_HEADERS=true`，同时 Nginx 会覆盖而不是追加客户端提供的
`X-Forwarded-For`。如果直接将应用端口暴露到公网，请保持该选项为 `false`。

生产环境建议在 Nginx 前或 Nginx 内配置 TLS。若由云负载均衡器或 CDN 终止 TLS，当前配置可以直接作为内部 HTTP 反代使用。

### GitHub Actions 发布镜像

工作流 `.github/workflows/publish-container.yml` 会先运行测试，再构建并发布多架构镜像到：

```text
ghcr.io/<owner>/<repository>
```

触发规则：

- `main` 分支提交：推送 `main`、`latest` 和 `sha-*` 标签；
- `v1.2.3` 标签：推送 `1.2.3`、`1.2`、`1` 和 `sha-*` 标签；
- Pull Request：只测试和构建 `linux/amd64`，不推送镜像；
- 手动执行：通过 GitHub Actions 的 `workflow_dispatch`。

如果 `open-snapshot` 仓库已经被授予读取 `muedsa/snapshot` GitHub Package 的权限，工作流会直接使用 `GITHUB_TOKEN`。否则需要在仓库 Actions Secrets 中配置：

```text
SNAPSHOT_GPR_USER
SNAPSHOT_GPR_KEY
```

其中 `SNAPSHOT_GPR_KEY` 应为具有 `read:packages` 权限的 classic PAT。首次推送后可在 GHCR Package 设置中把镜像调整为公开可见。

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:
 * [Ktor Documentation](https://ktor.io/docs/home.html)
 * [Ktor GitHub page](https://github.com/ktorio/ktor)
 * [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).


## Features
Here's a list of features included in this project:

| Name | Description |
|------|-------------|
| [Forwarded Headers](https://start.ktor.io/p/io.ktor/server-forwarded-header-support) | Allows handling proxied headers (X-Forwarded-*) |
| [Rate Limiting](https://ktor.io/docs/server-rate-limit.html) | Ktor 官方按来源 IP 的请求限流 |
| [Content Negotiation](https://start.ktor.io/p/io.ktor/server-content-negotiation) | Provides automatic content conversion according to Content-Type and Accept headers |
| [CORS](https://start.ktor.io/p/io.ktor/server-cors) | Enables Cross-Origin Resource Sharing (CORS) |


## Building & Running
To build or run the project, use one of the following tasks:


| Task | Description |
|------|-------------|
| `./gradlew test`    | Run the tests     |
| `./gradlew build`   | Build the project |
| `./gradlew run`     | Run the server    |

If the server starts successfully, you'll see the following output:
```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```
