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
| `GET /health` | 健康检查 |
| `GET /fonts` | 返回可用字体列表（管理接口） |
| `GET /fonts.png` | 返回字体预览图（管理接口） |
| `GET /cacheInfo` | 查看网络图片缓存统计（管理接口） |
| `POST /cacheClear` | 清理网络图片缓存（管理接口） |

管理接口默认不注册。启用时必须同时设置强随机令牌，并通过
`Authorization: Bearer <token>` 访问：

```dotenv
SNAPSHOT_ADMIN_ENDPOINTS_ENABLED=true
SNAPSHOT_ADMIN_TOKEN=replace-with-a-long-random-token
```

## 运行配置

核心限制位于 `src/main/resources/application.yaml`：

```yaml
snapshot:
  trust-proxy-headers: false
  max-request-size: 1048576
  max-concurrent-renders: 4
  max-render-timeout-ms: 30000
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

发布前验证：

```bash
./gradlew test
./gradlew build
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
```

配置优先级为：非空环境变量 > 绑定挂载的 YAML > 镜像内默认 YAML。CORS 域名和字体族属于列表配置，建议直接修改挂载的 YAML。管理令牌可通过 `SNAPSHOT_ADMIN_TOKEN` 注入，不会被转换为 JVM 命令行参数。GitHub PAT 等构建秘密不要写入 `.env` 或 YAML，仍然使用 `.secrets` 中的 BuildKit secret。

容器拓扑为 `Internet -> Nginx -> open-snapshot:8080`，应用端口不会直接暴露到宿主机。Nginx 默认包含：

- 1 MiB 请求体限制；
- 每个客户端 IP 每分钟 6 个请求，允许瞬时突发 3 个；
- 35 秒上游读取超时；
- `X-Forwarded-*` 与 `X-Request-Id` 请求头；
- `/health` 健康检查。

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
