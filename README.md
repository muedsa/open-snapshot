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

内嵌图片可使用 `dataUri`（PNG、JPEG、WebP），不能与 `url` 同时出现：

```html
<Snapshot type="png">
    <Image width="32" height="32" dataUri="data:image/png;base64,iVBORw0KGgo..."/>
</Snapshot>
```

URL 与 Data URI 共用每次渲染的图片数量和总解码像素预算。Data URI 还会在解码前检查 Base64
长度，并校验声明 MIME 与实际文件签名，防止压缩图片或伪造格式造成内存放大。

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
| `FONT_NOT_FOUND` | 400 | `/fonts.png` 的 `family` 中包含不存在的字体族 |
| `INVALID_QUERY` | 400 | 查询参数不合法（例如 `offset`/`limit` 不是非负整数） |
| `RATE_LIMITED` | 429 | 触发限流（Ktor 限流插件直接返回 429，带 `Retry-After`） |
| `QUEUE_FULL` | 503 | 等待渲染槽位的请求数超过 `max-render-queue`，带 `Retry-After` |
| `QUEUE_TIMEOUT` | 503 | 排队等待超过 `render-queue-timeout-ms`，带 `Retry-After` |
| `SERVICE_UNAVAILABLE` | 503 | 服务正在排水，不再接受新渲染 |
| `NOT_READY` | 503 | `/ready` 探测未通过 |
| `UNAUTHORIZED` | 401 | 管理接口缺少或提供了错误的 Bearer 令牌 |
| `INTERNAL_ERROR` | 500 | 未预期的内部错误，`message` 不包含内部细节 |

#### 解析错误高亮图

请求带 `?errorImage=png` 时，解析失败会返回一张错误卡片（PNG）而不是 JSON，便于在调试工具里直接查看：

```bash
curl -X POST "http://localhost:8080/snapshot?errorImage=png" `
  -H "Content-Type: text/plain" `
  --data '<Snapshot><Container width="1"height="1"/></Snapshot>' `
  --output error.png
```

卡片包含：错误标题、带行号的源码摘录（错误行浅红底、`^` 指向出错列）、错误消息、位置与 `requestId`。

- 只在解析失败时生效；解析成功时该参数被忽略，正常出图；
- HTTP 状态仍为 `400`，并额外返回 `X-Snapshot-Error-Code`、`X-Snapshot-Error-Position`、`X-Snapshot-Error-Location`；
- **不带该参数时行为完全不变**（仍是 JSON 错误）；`errorImage` 只支持 `png`，其他值回退 JSON；
- 错误卡片渲染失败或超时（含渲染队列已满）时**回退 JSON**，绝不掩盖原始解析错误；
- 摘录范围由 `error-image.max-lines`、`max-columns`、`context-lines` 控制，`error-image.enabled=false` 可关闭；
- 非解析类错误（画布超限、图片加载失败等）仍返回 JSON。

#### 响应耗时元数据

成功与失败响应都带标准 `Server-Timing`，调用方与浏览器 DevTools 的 Timing 面板可直接看到耗时构成：

```http
Server-Timing: queue;dur=0.3, render;dur=12.4, image;dur=8.1, total;dur=14.2
X-Snapshot-Cache: miss
X-Snapshot-Image-Count: 2
```

| 段 / 头 | 含义 | 出现条件 |
|---|---|---|
| `queue;dur` | 等待渲染槽位的耗时（含被拒绝前的等待） | 经历过排队 |
| `render;dur` | 纯渲染耗时（解析 + 布局 + 光栅化 + 编码） | 实际执行了渲染 |
| `image;dur` | 本请求在远程图片上消耗的时间（含等待同 URL 并发去重） | 请求中加载过网络图片 |
| `cache;desc=hit` | 渲染结果缓存命中 | 命中时（此时没有 `render` 段） |
| `total;dur` | 服务端处理总耗时 | 能进入路由的所有响应 |
| `X-Snapshot-Cache` | `hit` / `miss` | `/snapshot` 且启用渲染结果缓存 |
| `X-Snapshot-Image-Count` | 本请求真正发起的图片下载次数（304 复用不计入） | 有下载时 |

说明：

- 耗时单位为毫秒、保留 1 位小数；错误响应（如 `400 PARSE_ERROR`）同样带 `total`，但不含未发生的阶段；
- `429` 由限流插件在进入路由前产生，无法附加这些头，请通过 `snapshot_rate_limited_total` 指标观察；
- 浏览器 JS 读取需要服务端显式暴露（已加入 `Access-Control-Expose-Headers`）；
- `timing-headers.enabled=false` 可关闭全部耗时头。

#### 渲染执行模型

- 渲染在专用线程池上执行，线程名为 `snapshot-render-*`，不会占用 Netty 连接处理线程；
- 线程池大小与并发上限都由 `max-concurrent-renders` 决定；有空闲槽位时立即执行，
  否则进入等待队列（上限 `max-render-queue`，等待上限 `render-queue-timeout-ms`）；
- 队列已满返回 `503 QUEUE_FULL`，排队超时返回 `503 QUEUE_TIMEOUT`，两者都带 `Retry-After`；
- `max-render-timeout-ms` 是排队加渲染的总预算；阻塞式渲染无法被中途打断，超时会在渲染返回后生效；
- 相同 DSL 的重复请求可由渲染结果缓存直接返回，不占用渲染槽位；
- 网络图片缓存按 URL 去重，同一地址的并发请求只下载与解码一次。

### 其他接口

| 接口 | 说明 |
|---|---|
| `GET /health` | 存活检查：进程可响应即返回 `OK` |
| `GET /ready` | 就绪检查：渲染链路可用且未在排水时返回 `READY`，否则 `503` + `NOT_READY` |
| `GET /metrics` | Prometheus 文本指标（`text/plain; version=0.0.4`） |
| `GET /fonts` | 返回可用字体列表（管理接口） |
| `GET /fonts.png` | 字体预览图（管理接口），支持 `family`、`offset`、`limit` |
| `GET /cacheInfo` | 查看网络图片缓存与渲染结果缓存统计（管理接口） |
| `POST /cacheClear` | 清理网络图片缓存与渲染结果缓存（管理接口） |

所有响应都会带有 `X-Request-Id`（调用方传入的合法值会被沿用，否则生成 UUID），错误响应体中的
`requestId` 与之相同。

完整的接口定义见 [`docs/openapi.yaml`](docs/openapi.yaml)（OpenAPI 3.0，包含全部接口、错误码与响应示例）。

### 可选 API Key 鉴权

默认情况下 `/snapshot` 开放调用，仅受限流保护。需要限制调用方时，配置任意一种凭据来源即可启用鉴权：

```dotenv
# 单个 Key
SNAPSHOT_API_KEY=replace-with-a-long-random-key
# 多个 Key（名称:密钥，逗号分隔）；名称会出现在日志、指标与限流桶键中
SNAPSHOT_API_KEYS=web-frontend:key-0123456789abcdef,partner-a:key-fedcba9876543210
```

也可以在 YAML 中配置，并给需要访问管理接口的 Key 加 `admin: true`：

```yaml
snapshot:
  api-keys:
    - name: web-frontend
      key: "key-0123456789abcdef"
    - name: ops
      key: "key-fedcba9876543210"
      admin: true
```

约束：每个 Key 至少 16 位；名称需匹配 `[A-Za-z0-9._-]{1,32}` 且不可重复；最多 32 个 Key。

**轮换方式**：把新 Key 与旧 Key 同时列在配置里，等调用方切换完成后删除旧 Key，全程无需停机。

启用后 `/snapshot` 默认必须携带以下任一种凭据，否则返回 `401 UNAUTHORIZED`：

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

凭据按 SHA-256 摘要查找（不逐字符比较密钥），管理令牌使用定长比较。建议通过环境变量注入，而不是写入配置文件或命令行参数。

如果希望保留 API Key 的高配额通道，同时继续开放匿名访问，可显式开启：

```dotenv
SNAPSHOT_ANONYMOUS_ACCESS_ENABLED=true
```

匿名请求仍按来源 IP 使用 `rate-limit.requests` 配额；有效 API Key 使用独立的凭据配额。
显式携带错误 API Key 的请求仍返回 `401`，不会无声降级为匿名访问。该开关默认 `false`。

管理接口接受带 `admin: true` 的 API Key 或管理令牌；凭据有效但非管理员返回 `403 FORBIDDEN`，未认证返回 `401 UNAUTHORIZED`：

```dotenv
SNAPSHOT_ADMIN_ENDPOINTS_ENABLED=true
SNAPSHOT_ADMIN_TOKEN=replace-with-a-long-random-token
```

### 分层限流

| 调用方 | 计桶方式 | 配置项 | 默认值 |
|---|---|---|---|
| 匿名（未携带凭据） | 按来源 IP | `rate-limit.requests` / `window-ms` | 6 次 / 60 秒 |
| 已认证（有效 API Key） | 按凭据名称 | `rate-limit.credential-requests` / `credential-window-ms` | 60 次 / 60 秒 |
| 管理接口（字体预览图等） | 按管理凭据或来源 IP | `rate-limit.admin-requests` / `admin-window-ms` | 6 次 / 60 秒 |

三层互不影响：已认证调用方不再占用匿名 IP 桶，匿名流量也不会吃掉凭据配额。

- 未超限的响应带 `X-RateLimit-Limit`、`X-RateLimit-Remaining`、`X-RateLimit-Reset`；
- 超限返回 `429` 并带 `Retry-After`，同时计入 `snapshot_rate_limited_total{scope="anonymous|credential|admin"}`。

### `/metrics` 访问控制

```dotenv
SNAPSHOT_METRICS_ACCESS=open        # 默认：任何调用方都可读取
SNAPSHOT_METRICS_ACCESS=credential  # 需要有效 API Key
SNAPSHOT_METRICS_ACCESS=admin       # 需要管理凭据
```

`/health` 与 `/ready` 探针始终开放，不受该配置影响。

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
  max-document-elements: 4096
  max-document-depth: 128
  # 渲染队列背压：0 表示不排队、直接拒绝
  max-render-queue: 32
  render-queue-timeout-ms: 5000
  # 渲染结果缓存：相同 DSL 直接返回上次输出
  render-cache:
    enabled: true
    max-entries: 256
    max-bytes: 67108864
    ttl-ms: 60000
  # 解析错误高亮图（?errorImage=png）
  error-image:
    enabled: true
    max-lines: 8
    max-columns: 80
    context-lines: 2
  # 耗时响应头：Server-Timing 与 X-Snapshot-Cache / X-Snapshot-Image-Count
  timing-headers:
    enabled: true
  # 留空表示开放调用；填写后 /snapshot 需要 API Key，多 Key 见 api-keys 列表。
  api-key: ""
  # 配置 API Key 后是否仍允许匿名调用；默认关闭。
  anonymous-access-enabled: false
  access-log-enabled: true
  access-log-skip-paths:
    - /health
    - /ready
    - /metrics
  metrics-enabled: true
  # /metrics 访问控制：open / credential / admin
  metrics-access: open
  rate-limit:
    # 匿名按来源 IP
    requests: 6
    window-ms: 60000
    # 已认证按凭据
    credential-requests: 60
    credential-window-ms: 60000
    # 管理接口
    admin-requests: 6
    admin-window-ms: 60000
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
    max-total-image-pixels: 16777216
    allow-private-hosts: false
    connect-timeout-ms: 10000
    read-timeout-ms: 10000
    # 图片缓存 TTL：0 表示永不过期
    cache-ttl-ms: 600000
    # 瞬时失败重试
    max-retries: 1
    retry-backoff-ms: 200
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
- `snapshot.admin-endpoints-enabled=true` 时必须提供 `snapshot.admin-token`、`SNAPSHOT_ADMIN_TOKEN`，或一个 `admin: true` 的 API Key；
- 环境变量优先于配置文件：`SNAPSHOT_API_KEY`、`SNAPSHOT_API_KEYS`、`SNAPSHOT_ADMIN_TOKEN`、`SNAPSHOT_METRICS_ACCESS`；
- **列表配置**（CORS 域名、字体族、访问日志跳过路径）在 YAML 中写成列表，在环境变量 / `-P:` 覆盖中用**英文逗号**分隔，例如
  `SNAPSHOT_CORS_ALLOWED_HOSTS="localhost:3000,127.0.0.1:3000"`。

### 完整配置对照表

下表列出 `application.yaml` 中的全部配置项、默认值，以及对应的容器环境变量（`docker/entrypoint.sh` 会转成 Ktor `-P:` 覆盖参数）。

| 配置项 | 默认值 | 环境变量 |
|---|---|---|
| `ktor.deployment.host` | `0.0.0.0` | `SNAPSHOT_SERVER_HOST` |
| `ktor.deployment.port` | `8080` | `SNAPSHOT_SERVER_PORT` |
| `ktor.deployment.shutdownGracePeriod` | `10000` | `SNAPSHOT_SHUTDOWN_GRACE_MS` |
| `ktor.deployment.shutdownTimeout` | `15000` | `SNAPSHOT_SHUTDOWN_TIMEOUT_MS` |
| `snapshot.trust-proxy-headers` | `false` | `SNAPSHOT_TRUST_PROXY_HEADERS` |
| `snapshot.max-request-size` | `1048576` | `SNAPSHOT_MAX_REQUEST_SIZE` |
| `snapshot.max-concurrent-renders` | `4` | `SNAPSHOT_MAX_CONCURRENT_RENDERS` |
| `snapshot.max-render-timeout-ms` | `30000` | `SNAPSHOT_MAX_RENDER_TIMEOUT_MS` |
| `snapshot.max-document-elements` | `4096` | `SNAPSHOT_MAX_DOCUMENT_ELEMENTS` |
| `snapshot.max-document-depth` | `128` | `SNAPSHOT_MAX_DOCUMENT_DEPTH` |
| `snapshot.max-render-queue` | `32` | `SNAPSHOT_MAX_RENDER_QUEUE` |
| `snapshot.render-queue-timeout-ms` | `5000` | `SNAPSHOT_RENDER_QUEUE_TIMEOUT_MS` |
| `snapshot.render-cache.enabled` | `true` | `SNAPSHOT_RENDER_CACHE_ENABLED` |
| `snapshot.render-cache.max-entries` | `256` | `SNAPSHOT_RENDER_CACHE_MAX_ENTRIES` |
| `snapshot.render-cache.max-bytes` | `67108864` | `SNAPSHOT_RENDER_CACHE_MAX_BYTES` |
| `snapshot.render-cache.ttl-ms` | `60000` | `SNAPSHOT_RENDER_CACHE_TTL_MS` |
| `snapshot.error-image.enabled` | `true` | `SNAPSHOT_ERROR_IMAGE_ENABLED` |
| `snapshot.error-image.max-lines` | `8` | `SNAPSHOT_ERROR_IMAGE_MAX_LINES` |
| `snapshot.error-image.max-columns` | `80` | `SNAPSHOT_ERROR_IMAGE_MAX_COLUMNS` |
| `snapshot.error-image.context-lines` | `2` | `SNAPSHOT_ERROR_IMAGE_CONTEXT_LINES` |
| `snapshot.timing-headers.enabled` | `true` | `SNAPSHOT_TIMING_HEADERS_ENABLED` |
| `snapshot.api-key` | 空（开放调用） | `SNAPSHOT_API_KEY` |
| `snapshot.api-keys`（列表） | 空 | `SNAPSHOT_API_KEYS`（`名称:密钥[:admin]`） |
| `snapshot.anonymous-access-enabled` | `false` | `SNAPSHOT_ANONYMOUS_ACCESS_ENABLED` |
| `snapshot.admin-endpoints-enabled` | `false` | `SNAPSHOT_ADMIN_ENDPOINTS_ENABLED` |
| `snapshot.admin-token` | 空 | `SNAPSHOT_ADMIN_TOKEN` |
| `snapshot.metrics-enabled` | `true` | `SNAPSHOT_METRICS_ENABLED` |
| `snapshot.metrics-access` | `open` | `SNAPSHOT_METRICS_ACCESS` |
| `snapshot.access-log-enabled` | `true` | `SNAPSHOT_ACCESS_LOG_ENABLED` |
| `snapshot.access-log-skip-paths`（列表） | `/health`、`/ready`、`/metrics` | `SNAPSHOT_ACCESS_LOG_SKIP_PATHS` |
| `snapshot.cors.allowed-hosts`（列表） | `localhost:3000`、`127.0.0.1:3000` | `SNAPSHOT_CORS_ALLOWED_HOSTS` |
| `snapshot.font-family-names`（列表） | `Inter`、`Noto Serif SC`、`DejaVu Serif`、`Noto Color Emoji` | `SNAPSHOT_FONT_FAMILY_NAMES` |
| `snapshot.rate-limit.requests` | `6` | `SNAPSHOT_RATE_LIMIT_REQUESTS` |
| `snapshot.rate-limit.window-ms` | `60000` | `SNAPSHOT_RATE_LIMIT_WINDOW_MS` |
| `snapshot.rate-limit.credential-requests` | `60` | `SNAPSHOT_RATE_LIMIT_CREDENTIAL_REQUESTS` |
| `snapshot.rate-limit.credential-window-ms` | `60000` | `SNAPSHOT_RATE_LIMIT_CREDENTIAL_WINDOW_MS` |
| `snapshot.rate-limit.admin-requests` | `6` | `SNAPSHOT_RATE_LIMIT_ADMIN_REQUESTS` |
| `snapshot.rate-limit.admin-window-ms` | `60000` | `SNAPSHOT_RATE_LIMIT_ADMIN_WINDOW_MS` |
| `snapshot.max-canvas-width` | `4096` | `SNAPSHOT_MAX_CANVAS_WIDTH` |
| `snapshot.max-canvas-height` | `4096` | `SNAPSHOT_MAX_CANVAS_HEIGHT` |
| `snapshot.max-canvas-pixels` | `16777216` | `SNAPSHOT_MAX_CANVAS_PIXELS` |
| `snapshot.image.max-image-num-once` | `10` | `SNAPSHOT_MAX_IMAGE_NUM` |
| `snapshot.image.max-single-image-size` | `5242880` | `SNAPSHOT_MAX_SINGLE_IMAGE_SIZE` |
| `snapshot.image.memory-cache-num-limit` | `100` | `SNAPSHOT_MEMORY_CACHE_NUM_LIMIT` |
| `snapshot.image.max-cache-bytes` | `268435456` | `SNAPSHOT_MAX_CACHE_BYTES` |
| `snapshot.image.max-image-width` | `4096` | `SNAPSHOT_MAX_IMAGE_WIDTH` |
| `snapshot.image.max-image-height` | `4096` | `SNAPSHOT_MAX_IMAGE_HEIGHT` |
| `snapshot.image.max-image-pixels` | `16777216` | `SNAPSHOT_MAX_IMAGE_PIXELS` |
| `snapshot.image.max-total-image-pixels` | `16777216` | `SNAPSHOT_MAX_TOTAL_IMAGE_PIXELS` |
| `snapshot.image.allow-private-hosts` | `false` | `SNAPSHOT_ALLOW_PRIVATE_HOSTS` |
| `snapshot.image.connect-timeout-ms` | `10000` | `SNAPSHOT_IMAGE_CONNECT_TIMEOUT_MS` |
| `snapshot.image.read-timeout-ms` | `10000` | `SNAPSHOT_IMAGE_READ_TIMEOUT_MS` |
| `snapshot.image.cache-ttl-ms` | `600000` | `SNAPSHOT_IMAGE_CACHE_TTL_MS` |
| `snapshot.image.max-retries` | `1` | `SNAPSHOT_IMAGE_MAX_RETRIES` |
| `snapshot.image.retry-backoff-ms` | `200` | `SNAPSHOT_IMAGE_RETRY_BACKOFF_MS` |

密钥类变量（`SNAPSHOT_API_KEY`、`SNAPSHOT_API_KEYS`、`SNAPSHOT_ADMIN_TOKEN`）由应用直接读取环境变量，
不会转成 JVM 命令行参数，因此不会出现在进程列表里；其余变量由 `docker/entrypoint.sh` 转成 `-P:` 覆盖参数。

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
| `snapshot_render_pending` | gauge | 等待渲染槽位的请求数 |
| `snapshot_render_queue_wait_seconds` | histogram | 排队等待槽位的耗时分布 |
| `snapshot_image_fetch_seconds` | histogram | 单次请求在远程图片上消耗的时间分布 |
| `snapshot_render_queue_rejected_total{reason}` | counter | 背压拒绝数，`reason` 为 `full` 或 `timeout` |
| `snapshot_render_cache_hits_total` / `_misses_total` / `_evictions_total` | counter | 渲染结果缓存命中、未命中与淘汰 |
| `snapshot_render_cache_entries` / `_bytes` | gauge | 渲染结果缓存条目数与占用字节 |
| `snapshot_renders_total{outcome}` | counter | 渲染结果：`success`、`empty_request`、`parse_error`、`render_error`、`image_error`、`too_large`、`timeout`、`rate_limited`、`unavailable`、`internal_error` |
| `snapshot_render_duration_seconds` | histogram | 渲染耗时分布 |
| `snapshot_render_output_bytes_total` | counter | 输出图片总字节数 |
| `snapshot_http_requests_total{path,method,status}` | counter | 各路由请求数与状态码 |
| `snapshot_http_request_duration_seconds{path}` | summary | 各路由请求耗时 |
| `snapshot_image_cache_hits_total` / `snapshot_image_cache_misses_total` | counter | 网络图片缓存命中与未命中 |
| `snapshot_image_cache_evictions_total` / `_expirations_total` | counter | 图片缓存容量淘汰与 TTL 过期次数 |
| `snapshot_image_revalidations_total{result}` | counter | 条件请求结果：`not_modified`（复用）或 `updated` |
| `snapshot_image_downloads_total` / `snapshot_image_download_failures_total` / `_download_retries_total` | counter | 图片下载次数、失败次数与重试次数 |
| `snapshot_rate_limited_total{scope}` | counter | 被限流拒绝的请求数，按层级（`anonymous`/`credential`/`admin`）区分 |
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

### 图片安全与网络图片缓存

- URL 与 Data URI 图片共用 `max-image-num-once` 和 `max-total-image-pixels`，缓存命中也计入预算；
- 两种来源都受单图编码大小、解码宽高和像素数限制；
- Data URI 仅接受 PNG、JPEG、WebP 的严格 Base64，并校验声明格式与文件签名；
- URL 图片额外限制为 HTTP(S)，默认阻止本机、私网、链路本地、保留网段及云元数据地址。

- 全局 LRU 缓存同时受 `memory-cache-num-limit`（数量）与 `max-cache-bytes`（总字节）约束；
- 淘汰与清空只丢弃引用，不会关闭可能正被其他并发渲染绘制的图片：
  Skiko 的 `Managed` 在对象不可达后由 Reference Cleaner 回收本地内存；
- 同一 URL 的并发请求只下载与解码一次，其余调用共享同一结果；
- 文档树还受 `max-document-elements` 与 `max-document-depth` 限制，防止递归构建 Widget 时栈溢出或长时间占用渲染槽位。

`cache-ttl-ms` 控制缓存新鲜度（默认 10 分钟，`0` 表示永不过期）：

- 条目未过期 → 直接命中；
- 条目过期且响应带 `ETag` → 发起 `If-None-Match` 条件请求，`304` 时复用原图片并刷新过期时间，`200` 时替换为新图片；
- 条目过期且没有 `ETag` → 重新下载。

瞬时故障（5xx、408、429、连接或读取失败）会按 `retry-backoff-ms × 重试次数` 退避重试，
最多 `max-retries` 次；4xx 等确定性失败不重试。

图片相关指标：`snapshot_image_cache_hits_total`、`_misses_total`、`_entries`、`_bytes`、
`_evictions_total`（容量淘汰）、`_expirations_total`（TTL 过期）、
`snapshot_image_revalidations_total{result="not_modified|updated"}`、
`snapshot_image_downloads_total`、`_download_failures_total`、`_download_retries_total`。

### 字体预览

`GET /fonts.png` 支持按需渲染，避免系统字体很多时一次性出图过大：

```bash
# 只渲染指定字体（逗号分隔，名称大小写不敏感）
curl -H "Authorization: Bearer $SNAPSHOT_ADMIN_TOKEN" \
  --get --data-urlencode "family=DejaVu Serif,Inter" \
  http://localhost:8080/fonts.png --output fonts.png

# 分页：每页 10 个字体，第 2 页
curl -H "Authorization: Bearer $SNAPSHOT_ADMIN_TOKEN" \
  "http://localhost:8080/fonts.png?offset=10&limit=10" --output fonts-page2.png
```

- `family` 中有不存在的字体族时返回 `400 FONT_NOT_FOUND`（消息里列出未知名称）；
- `offset`/`limit` 不是非负整数时返回 `400 INVALID_QUERY`；`limit=0` 表示不限制；
- 相同查询会命中渲染结果缓存，重复拉取不会再次渲染。

### 渲染结果缓存与背压

渲染结果缓存把「相同 DSL」的重复请求直接变成一次内存读取，跳过解析、布局与光栅化，也不占用渲染槽位：

- 键为 DSL 文本的 SHA-256；`render-cache.enabled` 可整体关闭；
- 同时受 `max-entries` 与 `max-bytes` 约束，按 LRU 淘汰；
- `ttl-ms` 到期后重新渲染：DSL 中引用的网络图片内容可能变化；
- DSL 中出现 `noCache="true"` 时跳过缓存，尊重调用方要最新图片的意图；
- `POST /cacheClear` 会同时清空图片缓存与渲染结果缓存，`GET /cacheInfo` 返回两者的条目与字节数；
- 命中情况见 `snapshot_render_cache_hits_total` / `_misses_total` / `_evictions_total` 与 `_entries` / `_bytes`。

队列背压让过载表现为快速失败而不是请求堆积：

- `max-render-queue`：等待渲染槽位的请求数上限，超出返回 `503 QUEUE_FULL`；设为 `0` 表示不排队；
- `render-queue-timeout-ms`：排队等待上限，超出返回 `503 QUEUE_TIMEOUT`；
- 两者都带 `Retry-After`，并计入 `snapshot_render_queue_rejected_total{reason="full|timeout"}`；
- 排队耗时分布见 `snapshot_render_queue_wait_seconds`，当前等待数见 `snapshot_render_pending`。

容量建议：`max-concurrent-renders` 按 CPU 核数设置，`max-render-queue` 取并发数的 4–8 倍，
`render-queue-timeout-ms` 不超过调用方可接受的延迟（例如 1–5 秒）。

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
SNAPSHOT_API_KEYS=web-frontend:key-0123456789abcdef,partner-a:key-fedcba9876543210
SNAPSHOT_ANONYMOUS_ACCESS_ENABLED=true
SNAPSHOT_METRICS_ACCESS=open
SNAPSHOT_RATE_LIMIT_CREDENTIAL_REQUESTS=120
SNAPSHOT_RATE_LIMIT_ADMIN_REQUESTS=4
SNAPSHOT_MAX_RENDER_QUEUE=16
SNAPSHOT_RENDER_QUEUE_TIMEOUT_MS=3000
SNAPSHOT_RENDER_CACHE_TTL_MS=300000
SNAPSHOT_IMAGE_CACHE_TTL_MS=600000
SNAPSHOT_IMAGE_MAX_RETRIES=2
```

配置优先级为：非空环境变量 > 绑定挂载的 YAML > 镜像内默认 YAML。CORS 域名、字体族与 `api-keys` 列表属于列表配置，建议直接修改挂载的 YAML。`SNAPSHOT_ADMIN_TOKEN`、`SNAPSHOT_API_KEY` 与 `SNAPSHOT_API_KEYS` 由应用直接读取环境变量，不会被转换为 JVM 命令行参数，因此不会出现在容器的进程列表里；其余标量配置由 `docker/entrypoint.sh` 转换为 `-P:` 覆盖参数。GitHub PAT 等构建秘密不要写入 `.env` 或 YAML，仍然使用 `.secrets` 中的 BuildKit secret。

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
