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

根标签 `<Snapshot>` 的属性：

| 属性 | 说明 |
|---|---|
| `type` | 输出格式：`png`（默认）、`jpg`、`webp`，其他值会被解析器拒绝 |
| `background` | 画布背景色，`#AARRGGBB` |
| `debug` | 绘制布局辅助信息，默认 `false`，需显式写 `debug="true"` |

画布尺寸由 DSL 内容布局决定，并受 `max-canvas-width`、`max-canvas-height`、`max-canvas-pixels` 限制。

#### 请求示例

容器与文本：

```html
<Snapshot type="png" background="#FF102030">
    <Column>
        <Row>
            <Container color="#FF0000" width="200" height="200"/>
            <Container color="#FFFFFF" width="200" height="200">
                <Text color="#0000FF" fontSize="20">hello snapshot 🤣</Text>
            </Container>
        </Row>
    </Column>
</Snapshot>
```

网络图片（受数量、单图大小、解码尺寸与私网拦截限制）：

```html
<Snapshot type="webp">
    <Image width="200" height="200" url="https://example.com/cover.png" fit="COVER" noCache="true"/>
</Snapshot>
```

`fit` 可选 `FILL`、`CONTAIN`、`COVER`、`FIT_WIDTH`、`FIT_HEIGHT`、`NONE`、`SCALE_DOWN`。
`noCache="true"` 会跳过缓存，默认 `false`（布尔属性必须写出值，裸写属性会回落到默认值）。

指定字体族，避免中文或 Emoji 缺字（多个字体用英文逗号分隔，且**不能带空格**）：

```html
<Snapshot type="png">
    <Text fontSize="24" fontFamily="Noto Serif SC,Noto Color Emoji">原神，启动！🤣</Text>
</Snapshot>
```

更多标签与属性见 snapshot 框架的[使用说明](https://github.com/muedsa/snapshot)（`docs/usage`）。

#### 错误响应

错误统一为 JSON，并带同样的 `requestId`：

```json
{"code":"PARSE_ERROR","message":"...","requestId":"..."}
```

| `code` | HTTP | 说明 |
|---|---|---|
| `EMPTY_REQUEST` | 400 | 请求体为空或只有空白字符 |
| `PARSE_ERROR` | 400 | DSL 语法错误，`message` 含出错位置与附近的源码片段 |
| `RENDER_ERROR` | 400 | 超出画布限制或布局失败 |
| `IMAGE_LOAD_ERROR` | 400 | 图片地址非法、非 HTTP(S)、私网地址、超时或格式错误 |
| `REQUEST_TOO_LARGE` | 413 | 请求体超过 `max-request-size` |
| `RENDER_TIMEOUT` | 504 | 渲染超过 `max-render-timeout-ms` |
| `RATE_LIMITED` | 429 | 触发限流（Ktor 限流插件直接返回 429，带 `Retry-After`） |
| `SERVICE_UNAVAILABLE` | 503 | 服务正在排水，不再接受新渲染 |
| `NOT_READY` | 503 | `/ready` 探测未通过 |
| `UNAUTHORIZED` | 401 | 管理接口缺少或提供了错误的 Bearer 令牌 |
| `INTERNAL_ERROR` | 500 | 未预期的内部错误，`message` 不包含内部细节 |

#### 渲染执行模型

- 渲染在专用线程池上执行，线程名为 `snapshot-render-*`，不会占用 Netty 连接处理线程；
- 线程池大小与并发上限都由 `max-concurrent-renders` 决定，超出上限的请求排队等待；
- `max-render-timeout-ms` 是排队加渲染的总预算；阻塞式渲染无法被中途打断，超时会在渲染返回后生效；
- 网络图片缓存按 URL 去重，同一地址的并发请求只下载与解码一次。

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

完整的接口定义见 [`docs/openapi.yaml`](docs/openapi.yaml)（OpenAPI 3.0，包含全部接口、错误码与响应示例）。

### 可选 API Key 鉴权

默认情况下 `/snapshot` 开放调用，仅受按来源 IP 的限流保护。需要限制调用方时，配置一个至少
16 位的 API Key 即可启用鉴权：

```dotenv
SNAPSHOT_API_KEY=replace-with-a-long-random-key
```

启用后 `/snapshot` 必须携带以下任一种凭据，否则返回 `401 UNAUTHORIZED`：

```bash
curl -X POST http://localhost:8080/snapshot `
  -H "X-API-Key: $SNAPSHOT_API_KEY" `
  -H "Content-Type: text/plain" `
  --data '<Snapshot type="png"><Container width="200" height="100" color="#FFFF0000"/></Snapshot>' `
  --output result.png
```

```bash
curl -X POST http://localhost:8080/snapshot `
  -H "Authorization: Bearer $SNAPSHOT_API_KEY" `
  -H "Content-Type: text/plain" `
  --data '<Snapshot type="png"><Container width="200" height="100" color="#FFFF0000"/></Snapshot>' `
  --output result.png
```

`/health`、`/ready`、`/metrics` 始终不需要凭据，便于探针与监控直接访问。密钥比较使用定长比较；
建议通过 `SNAPSHOT_API_KEY` 注入而不是写入配置文件或命令行参数。

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
  # 留空表示开放调用；填写后 /snapshot 需要 API Key。
  api-key: ""
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
    connect-timeout-ms: 10000
    read-timeout-ms: 10000
```

配置只在一处解析（`SnapshotConfig`），并在启动时一次性校验：非法值会让服务启动失败，
错误信息会同时列出全部问题，而不是修一个报一个。例如：

```text
Invalid snapshot configuration:
  - snapshot.max-request-size must be a positive integer, but was '-1'
  - snapshot.metrics-enabled must be 'true' or 'false', but was 'maybe'
```

几项容易踩错的约束：

- `snapshot.api-key` 至少 16 位；
- `snapshot.admin-endpoints-enabled=true` 时必须提供 `snapshot.admin-token` 或 `SNAPSHOT_ADMIN_TOKEN`；
- 环境变量优先于配置文件：`SNAPSHOT_API_KEY`、`SNAPSHOT_ADMIN_TOKEN`。

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

### 网络图片缓存

- 全局 LRU 缓存同时受 `memory-cache-num-limit`（数量）与 `max-cache-bytes`（总字节）约束；
- 淘汰与清空只丢弃引用，不会关闭可能正被其他并发渲染绘制的图片：
  Skiko 的 `Managed` 在对象不可达后由 Reference Cleaner 回收本地内存；
- 同一 URL 的并发请求只下载与解码一次，其余调用共享同一结果；
- 每张图片仍受单图大小、解码宽高、像素数与私网地址限制，单次渲染请求数受 `max-image-num-once` 限制。

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
SNAPSHOT_IMAGE_CONNECT_TIMEOUT_MS=5000
SNAPSHOT_IMAGE_READ_TIMEOUT_MS=10000
SNAPSHOT_ACCESS_LOG_ENABLED=true
SNAPSHOT_METRICS_ENABLED=true
SNAPSHOT_SHUTDOWN_GRACE_MS=10000
SNAPSHOT_SHUTDOWN_TIMEOUT_MS=15000
SNAPSHOT_API_KEY=replace-with-a-long-random-key
```

配置优先级为：非空环境变量 > 绑定挂载的 YAML > 镜像内默认 YAML。CORS 域名和字体族属于列表配置，建议直接修改挂载的 YAML。`SNAPSHOT_ADMIN_TOKEN` 与 `SNAPSHOT_API_KEY` 由应用直接读取环境变量，不会被转换为 JVM 命令行参数，因此不会出现在容器的进程列表里；其余标量配置由 `docker/entrypoint.sh` 转换为 `-P:` 覆盖参数。GitHub PAT 等构建秘密不要写入 `.env` 或 YAML，仍然使用 `.secrets` 中的 BuildKit secret。

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
