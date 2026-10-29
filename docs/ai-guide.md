# Open Snapshot：AI 使用指南

使用此服务将 Snapshot DSL 文本渲染为 PNG、JPEG 或 WebP 图片。接口为 `POST /snapshot`，请求体是 UTF-8 纯文本，不是 JSON；成功时响应体是图片二进制。完整接口定义见同源的 `/openapi.yaml`。

## 建议的文档阅读顺序

1. 先阅读本指南和当前服务同源的 `/openapi.yaml`，确认调用方式、鉴权、资源限制、字体查询及错误处理。服务契约以当前部署为准，框架文档不能替代它。
2. 再读取 Snapshot 文档站的 [AI 文档索引 llms.txt](https://snapshot.muedsa.com/llms.txt)，按任务选择单页 Markdown；无需先加载全部框架文档。
3. 生成提交到 `/snapshot` 的模板时，优先查 [Parser 模板语法](https://snapshot.muedsa.com/guides/parser.md)、[标签与属性参考](https://snapshot.muedsa.com/reference/parser-tags.md) 和 [完整模板示例](https://snapshot.muedsa.com/guides/examples.md)。文本排版查 [Parser 文本属性](https://snapshot.muedsa.com/reference/parser-text.md)，错误含义查 [Parser 错误处理](https://snapshot.muedsa.com/reference/parser-errors.md)；字体和媒体概念可参考 [字体指南](https://snapshot.muedsa.com/guides/fonts.md) 与 [图片、文本和富文本](https://snapshot.muedsa.com/guides/media-text.md)，其中 Kotlin 示例不能直接作为请求体提交。

需要更完整上下文时再选择合集：

- [DSL 专题合集](https://snapshot.muedsa.com/_llms-txt/dsl.txt)：包含 Parser 模板语法、标签属性、错误处理和语言服务，最适合本服务的模板生成任务。
- [Kotlin 专题合集](https://snapshot.muedsa.com/_llms-txt/kotlin.txt)：仅在需要 Kotlin 接入或理解底层 Widget 行为时参考，不是 `/snapshot` 请求语法。
- [完整合集](https://snapshot.muedsa.com/llms-full.txt) 与 [精简合集](https://snapshot.muedsa.com/llms-small.txt)：用于跨专题查询。`llms-small.txt` 只去掉部分页面，仍保留完整代码和提示，并不是短摘要；不要默认加载。

文档页通常有对应的 `.md` 地址，例如 [快速开始 Markdown](https://snapshot.muedsa.com/guides/quickstart.md) 对应网页 `/guides/quickstart/`，首页对应 `/index.md`；以索引列出的链接为准，不要假定 Playground 或 404 页面也有 Markdown。文档站从同一套源文件自动生成这些内容，服务不重复维护整套标签参考。

以上绝对链接属于独立文档站，不是构图服务接口，也不受本服务公开文档开关控制。读取它们不需要服务 API Key；不要向文档站发送服务凭据、用户模板或图片。无法访问时应说明未能核实相关文档，不要臆造属性。

### 语法与版本边界

- Kotlin DSL 与 Parser 文本模板的参数名、默认值和颜色写法不完全相同。服务接收的文本以 Parser 文档为准，不能直接照搬 Kotlin 示例。
- 文档站跟随 Snapshot 快照更新；文档站上线不代表当前 Open Snapshot 已部署同版本引擎。新标签或属性须结合服务实际响应确认，不能仅凭文档或补全结果宣称支持。
- LSP 补全和静态诊断不等于服务端解析、布局或渲染验证。最终还需提交模板，检查响应状态、类型及实际图片；静态检查通过不保证字体、图片资源或布局可用。

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
- 字体、标签和属性的完整说明通过上面的 AI 文档索引按需查阅；不要臆造未知属性。

## 资源限制

请求体的 UTF-8 字节数、文档元素数量与嵌套深度、画布宽高与总像素、图片数量、单图编码大小与解码尺寸、单次渲染总图片像素均有上限。Data URI 也计入请求体大小，并与 URL 图片共用图片资源预算。远程图片只允许 HTTP(S)，默认拒绝本机、私网及其他受限地址。

具体上限可由部署方配置，本指南和框架示例不能代表当前实例的实际配额。优先生成紧凑模板，避免不必要的深层嵌套和大图；遇到限制时根据错误信息减少资源，必要时向服务维护者确认配置，不要原样反复重试。并发、排队、超时和限流规则也以当前服务为准。

## 获取可用字体

可直接调用 `GET /fonts` 获取当前服务实际安装的字体族；它与 `/snapshot` 使用相同的访问规则和限流配额。未配置 API Key 时可以匿名调用；若配置了 API Key，通常需要普通 API Key，只有服务显式启用匿名访问时才能不带凭据调用。响应是 `text/plain`，每行一个字体族名称，不是 JSON：

```sh
curl --fail-with-body "${SNAPSHOT_URL:-http://localhost:8080}/fonts"
```

需要凭据时，与渲染请求一样添加 `-H "X-API-Key: $SNAPSHOT_API_KEY"`，或使用 `Authorization: Bearer`。不要将密钥写入提示词。`/fonts` 不依赖本指南的公开开关，也不需要启用管理接口；若未授权，返回 401。

从返回列表中选择字体族后，可在文本元素中写 `fontFamily="Noto Serif SC,Noto Color Emoji"`；多个名称用英文逗号分隔，名称本身要与 `/fonts` 返回值一致。如需查看字体效果，可调用 `GET /fonts.png`，使用 `family`、`offset`、`limit` 查询参数；每页默认 10 个，最多 20 个，同样占用渲染接口配额。`snapshot.font-family-names` 配置的是默认字体偏好，不等于系统全部已安装字体。若无法访问 `/fonts`，请让使用者提供可用字体列表，不要声称某种字体已安装。

## 错误处理

非成功响应通常是包含 `code`、`message`、`requestId` 的 JSON。以 `code` 判断错误类型；`message` 是可变化、可多行、可截断的可读说明，不要按文案关键词分类或假定固定格式。渲染错误消息最多 1024 个 UTF-16 代码单元。成功和失败都可用 `X-Request-Id` 关联服务日志。

属性错误可能额外返回 `diagnostic`。修正 DSL 时优先读取 `attributeName`、可选的 `elementName`、`position`、`format`、`allowedValues`、`examples`，无需从消息中提取这些信息。`position.offset` 从 0 开始，`line` 和 `column` 从 1 开始；偏移和列按 UTF-16 代码单元计数，未知字段会省略。`allowedValues` 对简单枚举表示候选，对边框等复合格式可能仅描述其中一个字段，仍须遵循 `format`。

`valueState` 区分 `missing`（缺失）、`unassigned`（只写属性名）、`empty`（空字符串）和 `present`（其他已赋值输入）。服务不返回原始属性值或预览，请根据自己提交的 DSL 定位修改。候选和示例可能为空，`truncated: true` 表示部分提示已裁剪或省略。结构错误、组件组合约束等可能完全没有 `diagnostic`，此时参考 `message` 和自己提交的源码。每次只报告首个错误。

`400 PARSE_ERROR` 应修正 DSL 后再提交；`IMAGE_LOAD_ERROR` 应检查图片来源、格式和资源限制，即使附带属性诊断也不改变这个错误码；`413 REQUEST_TOO_LARGE` 需缩小请求体；`401` 需检查凭据；`429` 和部分 `503` 响应可参考 `Retry-After` 延迟重试。不要把相同的格式错误原样重试。

默认不要使用 `?errorImage=png`：它会把解析错误改为图片响应，不利于程序读取错误消息。需要直观查看错误时才加上该参数。

上述 `curl` 命令在错误时仍可能把 JSON 响应写入 `result.png`；使用生成的文件前，请检查退出码、HTTP 状态和响应 `Content-Type`。
