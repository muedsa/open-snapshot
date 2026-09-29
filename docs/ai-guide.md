# Open Snapshot：AI 使用指南

使用此服务将 Snapshot DSL 文本渲染为 PNG、JPEG 或 WebP 图片。接口为 `POST /snapshot`，请求体是 UTF-8 纯文本，不是 JSON；成功时响应体是图片二进制。完整接口定义见同源的 `/openapi.yaml`。

## 最小示例

将以下文本保存为 `snapshot.dsl`：

```xml
<Snapshot type="png">
    <Container width="200" height="100" color="#FF0000FF"/>
</Snapshot>
```

向服务提交，并将图片保存为文件：

```sh
curl --fail-with-body -X POST "${SNAPSHOT_URL:-http://localhost:8080}/snapshot" \
  -H "Content-Type: text/plain; charset=utf-8" \
  --data-binary @snapshot.dsl -o result.png
```

若服务配置了 API Key，请额外添加 `-H "X-API-Key: $SNAPSHOT_API_KEY"`，并通过环境变量向命令提供密钥；不要将密钥写入 DSL 或提示词。上面的变量展开是 POSIX shell 语法；在 Windows PowerShell 中请使用 `curl.exe`，并将 URL 和环境变量引用改为 PowerShell 语法。

## 生成 DSL 时的要点

- 根节点是 `<Snapshot>`；`type` 可为 `png`（默认）、`jpg`、`webp`。输出文件扩展名应与实际格式一致。
- 可使用 `<Column>`、`<Row>`、`<Container>`、`<Text>` 等元素。画布尺寸由布局决定，受服务端限制。
- 颜色使用 CSS 语法，包括 `#RGB`、`#RGBA`、`#RRGGBB`、`#RRGGBBAA`；8 位格式的透明度在最后两位。例如不透明红色为 `#FF0000FF`，也可简写为 `#FF0000`。
- `<Image>` 可使用 HTTP(S) `url` 或 `dataUri`，二者不能同时指定；远程图片和内嵌图片都受安全与资源限制。
- 字体、标签和属性的完整说明见 Snapshot 框架文档；不要臆造未知属性。

## 获取可用字体

可直接调用 `GET /fonts` 获取当前服务实际安装的字体族；它与 `/snapshot` 使用相同的访问规则和限流配额。未配置 API Key 时可以匿名调用；若配置了 API Key，通常需要普通 API Key，只有服务显式启用匿名访问时才能不带凭据调用。响应是 `text/plain`，每行一个字体族名称，不是 JSON：

```sh
curl --fail-with-body "${SNAPSHOT_URL:-http://localhost:8080}/fonts"
```

需要凭据时，与渲染请求一样添加 `-H "X-API-Key: $SNAPSHOT_API_KEY"`，或使用 `Authorization: Bearer`。不要将密钥写入提示词。`/fonts` 不依赖本指南的公开开关，也不需要启用管理接口；若未授权，返回 401。

从返回列表中选择字体族后，可在文本元素中写 `fontFamily="Noto Serif SC,Noto Color Emoji"`；多个名称用英文逗号分隔，名称本身要与 `/fonts` 返回值一致。如需查看字体效果，可调用 `GET /fonts.png`，使用 `family`、`offset`、`limit` 查询参数；每页默认 10 个，最多 20 个，同样占用渲染接口配额。`snapshot.font-family-names` 配置的是默认字体偏好，不等于系统全部已安装字体。若无法访问 `/fonts`，请让使用者提供可用字体列表，不要声称某种字体已安装。

## 错误处理

非成功响应通常是包含 `code`、`message`、`requestId` 的 JSON。`400 PARSE_ERROR` 应根据消息中的位置修正 DSL 后再提交；`413 REQUEST_TOO_LARGE` 需缩小请求体；`401` 需检查凭据；`429` 和部分 `503` 响应可参考 `Retry-After` 延迟重试。成功和失败都可用 `X-Request-Id` 关联服务日志。

默认不要使用 `?errorImage=png`：它会把解析错误改为图片响应，不利于程序读取错误消息。需要直观查看错误时才加上该参数。

上述 `curl` 命令在错误时仍可能把 JSON 响应写入 `result.png`；使用生成的文件前，请检查退出码、HTTP 状态和响应 `Content-Type`。
