# 分享总结 AI 生图与 Open Graph 需求定义

## 背景

分享总结模块已经支持按日报、周报、月报等固定周期生成总结报告，并在后台历史记录中查看执行结果。为了提升报告在社交平台、IM、论坛和内部协作场景中的传播效果，需要为每份分享总结生成一张适合 Open Graph 展示的分享图，并提供可复制、可长期访问的 OG 链接。

本需求重点增强“分享总结”模块，而不是重做现有分享总结任务、调度和历史记录逻辑。

## 目标

- 支持基于分享总结报告内容生成 AI 分享图。
- 在“分享总结”模块内提供全局 AI 生图配置弹窗。
- 支持配置图片风格提示词，并在生成图片时与报告内容共同组装 prompt。
- 支持对接 OpenAI 格式图片生成接口，并为后续不同图片生成接口格式预留适配层。
- 生成后的图片由 LinkPeek 自己存储和分发，提供可用于 `og:image` 的公开图片 URL。
- 按报告时间范围生成 Open Graph 标题，例如 `LinkPeek - 2026年5月月报`。
- 历史记录中可查看生成图片、生成状态、错误信息，并复制图片 OG URL 或完整分享页 URL。
- AI 生图失败时不影响分享总结报告本身保存和查看。

## 非目标

- 第一版不要求图片内嵌准确的中文标题或大量文字。
- 第一版不要求一次生成多张图片并人工选择。
- 第一版不要求图片编辑、局部重绘、多轮修图。
- 第一版不要求接入所有非 OpenAI 协议的异步生图平台。
- 第一版不要求将历史报告全部自动补生成图片。
- 第一版不要求替换现有 AI 总结 Provider 配置。

## 术语

| 术语 | 说明 |
| --- | --- |
| 分享总结报告 | `share_summary_run` 中一次成功生成的报告内容。 |
| AI 分享图 | 基于报告内容和风格提示词生成的封面图片。 |
| 图片 OG URL | 可直接作为 `<meta property="og:image" content="...">` 使用的公开图片直链。 |
| 分享页 URL | 带有完整 Open Graph meta 的 HTML 页面 URL。该页面的 `og:image` 指向图片 OG URL。 |
| 风格提示词 | 后台全局配置的图片风格要求，例如科技感、简洁、数据报告风格。 |
| 生图 Provider | 实际处理图片生成请求的上游 AI 服务，例如 OpenAI Images API、OpenAI Responses API 或 OpenAI-compatible 服务。 |

## 产品范围

### 全局 AI 生图配置

“分享总结”后台模块增加一个全局配置入口，建议放在分享总结页面顶部操作区：

```text
AI 生图配置
```

点击后打开弹窗。配置对后续所有分享总结任务生效。

基础配置项：

| 字段 | 说明 | MVP |
| --- | --- | --- |
| `enabled` | 是否启用分享总结 AI 生图能力 | 是 |
| `auto_generate` | 分享总结成功后是否自动生成图片 | 是 |
| `style_prompt` | 全局风格提示词 | 是 |
| `negative_prompt` | 负面提示词，用于描述不希望出现的元素 | 否 |
| `image_size` | 上游生图尺寸，默认 `auto`；第一版按 OpenAI Images 支持 `auto` / `1024x1024` / `1536x1024` / `1024x1536` | 是 |
| `output_format` | 最终输出格式，默认 `jpg` 或 `png` | 是 |

Provider 配置项：

| 字段 | 说明 | MVP |
| --- | --- | --- |
| `provider_type` | 生图接口类型 | 是 |
| `base_url` | 上游 API Base URL | 是 |
| `api_key` | 上游 API Key，保存后不明文回显 | 是 |
| `model` | 生图模型或 Responses API 主模型 | 是 |
| `request_timeout_seconds` | 单次请求超时时间，默认 300 秒，范围 1-1800 秒 | 是 |
| `retry_count` | 失败重试次数 | 否 |
| `quality` | 图片质量，例如 `low`、`medium`、`high`、`auto` | 否 |
| `provider_extra_json` | 供应商扩展参数 JSON | 否 |

`provider_type` 第一版建议支持：

| 类型 | 说明 |
| --- | --- |
| `OPENAI_IMAGES` | OpenAI Images API 风格，通常请求 `/v1/images/generations`。 |
| `OPENAI_RESPONSES` | OpenAI Responses API 加 `image_generation` tool。 |
| `OPENAI_COMPATIBLE` | OpenAI-compatible 同步生图接口，优先兼容 `data[0].url` 和 `data[0].b64_json`。 |

后续可扩展：

| 类型 | 说明 |
| --- | --- |
| `ASYNC_COMPATIBLE` | 提交任务后返回 `task_id`，需要轮询任务状态再获取图片。 |
| `CUSTOM_JSON_PATH` | 允许通过配置 JSONPath 提取图片 URL 或 base64。 |

### 自动生成流程

当分享总结执行记录进入 `SUCCESS` 状态后，如果全局配置满足以下条件，则自动触发 AI 生图：

- `enabled = true`
- `auto_generate = true`
- 报告正文非空
- 当前报告没有成功的最新图片，且没有正在进行的生图任务

自动生图不应该阻塞分享总结报告保存。推荐流程：

1. 分享总结报告先保存为 `SUCCESS`。
2. 创建图片生成记录，状态为 `PENDING` 或 `GENERATING`。
3. 后台异步执行生图。
4. 生图成功后保存图片文件，更新图片记录为 `SUCCESS`。
5. 生图失败后更新图片记录为 `FAILED`，报告仍保持 `SUCCESS`。

### 手动生成与重新生成

历史记录中支持对单条分享总结执行以下操作：

- 生成图片：报告尚无图片时可用。
- 重新生成：报告已有图片时可用。
- 查看图片：已有成功图片时可用。
- 复制图片 OG URL：已有成功图片时可用。
- 复制分享页 URL：已有公开分享页时可用。

重新生成规则：

- 每次重新生成都创建新的图片生成记录，保留旧记录用于追溯。
- 如果旧图片已成功，新图片生成中或失败时，历史记录仍展示最近一次成功图片。
- 同一报告同一时间只允许一个进行中的生图任务。
- 如果已有进行中的任务，再次触发应返回明确错误，例如 `IMAGE_GENERATION_IN_PROGRESS`。

### 历史记录展示

分享总结历史记录列表增加图片相关列或信息块：

| 展示项 | 说明 |
| --- | --- |
| 图片缩略图 | 最近一次成功图片；没有成功图片时展示占位状态。 |
| 图片状态 | `未启用`、`未生成`、`生成中`、`生成成功`、`生成失败`。 |
| OG 标题 | 根据报告时间范围生成的标题。 |
| 图片操作 | 查看、生成、重新生成、复制图片 OG URL、复制分享页 URL。 |

历史详情页增加：

- 完整图片预览。
- 图片 OG URL。
- 分享页 URL。
- OG 标题。
- OG 描述。
- 生图 Provider 类型。
- 生图模型。
- 风格提示词快照。
- 最终 prompt 快照。
- 生成时间。
- 失败原因。

## Open Graph 规则

### OG 标题

OG 标题使用报告时间范围和周期类型生成。默认模板：

```text
LinkPeek - {report_time_range_label}{report_type_label}
```

推荐规则：

| 周期类型 | 示例标题 |
| --- | --- |
| `DAILY` | `LinkPeek - 2026-05-29 日报` |
| `WEEKLY` | `LinkPeek - 2026年第22周周报` |
| `MONTHLY` | `LinkPeek - 2026年5月月报` |
| `CUSTOM` | `LinkPeek - 2026-05-01 至 2026-05-07 报告` |

周报使用自然周，周一为一周开始。跨年或周序号存在歧义时，允许回退为范围标题：

```text
LinkPeek - 2026-12-28 至 2027-01-03 周报
```

标题生成时使用服务端配置的业务时区，默认与分享总结窗口计算时区保持一致。

### OG 描述

默认描述模板：

```text
本报告汇总了 {window_start_label} 至 {window_end_label} 的链接分享与内容洞察。
```

如果报告有摘要字段，可优先使用摘要前 120 个字符。描述不得包含 HTML。

### OG 图片

生成图片必须提供公开直链，作为：

```html
<meta property="og:image" content="{og_image_url}">
```

图片要求：

| 项 | 要求 |
| --- | --- |
| 尺寸 | 最终输出固定为 `1200x630` 或等比例 1.91:1。 |
| 格式 | `jpg` 或 `png`。 |
| 访问 | 不需要后台登录，不依赖 Cookie。 |
| 稳定性 | URL 长期有效，不直接使用上游临时 URL。 |
| 响应头 | 返回正确 `Content-Type`，例如 `image/jpeg` 或 `image/png`。 |
| 安全 | URL 应使用不可枚举 ID 或 token，避免简单遍历历史图片。 |

上游生图尺寸不直接使用 `1200x630`。服务端需要对原图做标准化处理：

1. 下载或解码上游图片。
2. 校验 MIME、大小和像素尺寸。
3. 裁剪、缩放或补边到 `1200x630`。
4. 重新编码为目标格式。
5. 存储到 LinkPeek 本地或对象存储。

### 分享页

如果系统提供公开分享页，则分享页 HTML 至少包含：

```html
<meta property="og:title" content="{og_title}">
<meta property="og:description" content="{og_description}">
<meta property="og:image" content="{og_image_url}">
<meta property="og:type" content="article">
<meta property="og:url" content="{og_page_url}">
```

历史记录中应区分两个复制动作：

- 复制图片 OG URL：复制 `{og_image_url}`，用于 `og:image`。
- 复制分享页 URL：复制 `{og_page_url}`，用于直接分享给平台抓取完整 OG meta。

## Prompt 规则

### 最终 prompt 组装

最终生图 prompt 不直接等于用户配置的风格提示词，而是由系统根据报告信息组装。

建议模板：

```text
请生成一张适合 Open Graph 分享卡片的横版封面图。

报告标题：
{og_title}

报告时间范围：
{window_start_label} 至 {window_end_label}

报告类型：
{report_type_label}

报告摘要：
{report_summary}

报告重点：
{report_highlights}

风格要求：
{style_prompt}

图片要求：
- 横版构图，适合社交平台预览卡片。
- 主题体现数据分析、链接洞察、内容报告和 LinkPeek。
- 画面简洁，主体明确，适合作为报告封面。
- 不要生成复杂 UI 截图。
- 不要包含大量文字、小字或错误文字。
- 不要包含真实个人信息、密钥、二维码或水印。
```

`report_summary` 可从报告正文中截取，建议限制在 1000 到 2000 字以内，避免生图 prompt 过长。

`report_highlights` 可以由分享总结报告已有结构提取。如果报告没有结构化重点，则可为空。

### 风格提示词示例

默认风格提示词建议：

```text
现代数据报告封面，清晰、有层次，科技感但不过度炫光，适合产品运营和内容分析场景，画面干净，色彩专业。
```

不建议要求模型在图片中写完整中文标题。标题由 Open Graph 的 `og:title` 表达。

## 生图 Provider 适配

### 统一适配接口

业务层只依赖统一的生图请求和结果，不直接读取上游返回结构。

```text
ImageGenerationRequest
- prompt
- negative_prompt
- size
- quality
- output_format
- report_run_id
- timeout_seconds
- provider_extra

ImageGenerationResult
- status: SUCCESS / PENDING / FAILED
- image_url
- image_base64
- mime_type
- external_task_id
- revised_prompt
- raw_response_snapshot
- error_message
```

适配层职责：

1. 根据配置选择上游请求格式。
2. 发送请求并处理超时、重试和错误。
3. 从不同响应结构中提取图片 URL 或 base64。
4. 返回统一结果给业务服务。

业务服务职责：

1. 根据报告生成最终 prompt。
2. 调用适配层。
3. 对图片做下载、解码、校验和标准化。
4. 存储图片。
5. 生成 LinkPeek 自己的公开图片 URL。
6. 写入图片生成记录。

### OpenAI Images API 格式

`OPENAI_IMAGES` 适配器用于 OpenAI Images API 风格的同步生图接口。

请求特征：

- 通常为 `POST {base_url}/v1/images/generations`。
- 请求体包含 `model`、`prompt`、`size`、`quality`、`n`、`response_format` 等字段。

响应提取优先级：

1. `data[0].b64_json`
2. `data[0].url`

如果上游返回 URL，LinkPeek 必须下载并重新存储，不得将该 URL 直接作为最终 OG 图片 URL。

### OpenAI Responses API 格式

`OPENAI_RESPONSES` 适配器用于 Responses API 加 `image_generation` 工具的格式。

请求特征：

- 通常为 `POST {base_url}/v1/responses`。
- `model` 是可调用图片生成工具的主模型。
- 请求中包含 `tools: [{ "type": "image_generation", ... }]`。
- 可通过 tool 参数传递 `size`、`quality`、`format`、`background` 等输出选项。

响应提取规则：

- 从 `response.output` 中查找 `type = image_generation_call` 的结果。
- 优先提取图片 base64 字段，例如 `result`。
- 如果响应包含 revised prompt，应保存到 `revised_prompt` 字段用于排查。

### OpenAI-compatible 同步格式

`OPENAI_COMPATIBLE` 用于兼容第三方 OpenAI 风格图片接口。

第一版最低兼容：

- 鉴权方式：`Authorization: Bearer {api_key}`。
- 请求路径：默认 `/v1/images/generations`，允许通过配置覆盖。
- 返回 URL：`data[0].url`。
- 返回 base64：`data[0].b64_json`。

兼容注意事项：

- 第三方服务可能不支持 `quality`、`response_format`、`size` 等全部字段。
- 对不支持的字段，允许通过 `provider_extra_json` 控制是否发送。
- 上游返回字段不符合 OpenAI 格式时，应标记失败并记录错误，不应猜测解析。

### 异步生图扩展

异步生图不作为 MVP 必做，但设计需要预留：

```text
SUBMITTED -> POLLING -> SUCCESS / FAILED / TIMEOUT
```

异步适配器需要支持：

- 提交任务接口。
- 任务 ID 提取。
- 状态轮询接口。
- 成功图片 URL 或 base64 提取。
- 最大轮询次数和轮询间隔。

## 数据设计建议

### share_summary_image_config

全局 AI 生图配置表。也可以使用现有 `provider_config` 做 KV 存储，但独立表更利于校验、脱敏和后续扩展。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `INTEGER` | 固定单例主键，建议为 `1`。 |
| `enabled` | `INTEGER` | 是否启用 AI 生图。 |
| `auto_generate` | `INTEGER` | 是否自动生成。 |
| `provider_type` | `TEXT` | `OPENAI_IMAGES` / `OPENAI_RESPONSES` / `OPENAI_COMPATIBLE`。 |
| `base_url` | `TEXT` | 上游 API Base URL。 |
| `api_key` | `TEXT` | 加密或脱敏存储的 API Key。 |
| `model` | `TEXT` | 模型名称。 |
| `endpoint_path` | `TEXT` | 可选，兼容服务的自定义路径。 |
| `image_size` | `TEXT` | 上游生图尺寸，默认 `auto`。 |
| `quality` | `TEXT` | 可选，默认 `auto`。 |
| `output_format` | `TEXT` | `jpg` / `png`。 |
| `style_prompt` | `TEXT` | 风格提示词。 |
| `negative_prompt` | `TEXT` | 负面提示词。 |
| `request_timeout_seconds` | `INTEGER` | 请求超时，默认 300 秒。 |
| `retry_count` | `INTEGER` | 重试次数。 |
| `provider_extra_json` | `TEXT` | 扩展配置 JSON。 |
| `updated_at` | `INTEGER` | 更新时间。 |

### share_summary_image

分享总结图片生成记录表。每次生成或重新生成新增一条记录。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `INTEGER` | 主键。 |
| `run_id` | `INTEGER` | 关联分享总结执行记录。 |
| `attempt_no` | `INTEGER` | 同一报告的第几次生图。 |
| `status` | `TEXT` | `PENDING` / `GENERATING` / `SUCCESS` / `FAILED` / `TIMEOUT`。 |
| `provider_type` | `TEXT` | 生图 Provider 类型快照。 |
| `model` | `TEXT` | 模型快照。 |
| `image_size` | `TEXT` | 目标尺寸快照。 |
| `output_format` | `TEXT` | 输出格式快照。 |
| `style_prompt_snapshot` | `TEXT` | 风格提示词快照。 |
| `prompt_snapshot` | `TEXT` | 最终 prompt 快照。 |
| `negative_prompt_snapshot` | `TEXT` | 负面提示词快照。 |
| `revised_prompt` | `TEXT` | 上游返回的修订 prompt。 |
| `storage_key` | `TEXT` | LinkPeek 内部图片存储路径或对象 key。 |
| `image_url` | `TEXT` | 后台可访问图片 URL。 |
| `og_image_url` | `TEXT` | 公开图片 OG URL。 |
| `og_page_url` | `TEXT` | 公开分享页 URL。 |
| `og_title` | `TEXT` | OG 标题。 |
| `og_description` | `TEXT` | OG 描述。 |
| `raw_response_snapshot` | `TEXT` | 脱敏后的上游响应摘要。 |
| `error_message` | `TEXT` | 失败原因。 |
| `duration_ms` | `INTEGER` | 生图耗时。 |
| `created_at` | `INTEGER` | 创建时间。 |
| `started_at` | `INTEGER` | 开始时间。 |
| `finished_at` | `INTEGER` | 结束时间。 |

建议索引：

- `idx_share_summary_image_run_id(run_id)`
- `idx_share_summary_image_status(status)`
- `idx_share_summary_image_created_at(created_at)`

应用层约束：

- 同一 `run_id` 只允许一条 `PENDING` 或 `GENERATING` 记录。
- 历史记录默认展示同一 `run_id` 最近一条 `SUCCESS` 图片。

## 存储与公开访问

### 图片存储

推荐默认存储路径：

```text
{CACHE_DIR}/share-summary/images/{run_id}/{image_id}.{ext}
```

公开访问路径示例：

```text
/share-summary/og-images/{public_token}.{ext}
```

`public_token` 必须不可枚举，不能直接使用连续自增 ID。

### 图片校验

服务端在保存图片前必须做校验：

- 图片大小不得超过配置上限，建议默认 10 MB。
- 只允许 `image/jpeg`、`image/png`、`image/webp` 输入。
- 最终输出只允许 `jpg` 或 `png`。
- 解码失败、像素尺寸异常、空文件都标记为失败。
- 重新编码时去除上游图片元数据。

### 下载安全

如果上游返回图片 URL，服务端下载时需要：

- 只允许 `http` 或 `https`。
- 设置连接超时和读取超时。
- 限制最大响应大小。
- 校验响应 `Content-Type`。
- 防止下载内网地址、回环地址和 link-local 地址，降低 SSRF 风险。

## API 建议

后台配置接口：

```text
GET  /api/admin/share-summary/image-config
PUT  /api/admin/share-summary/image-config
POST /api/admin/share-summary/image-config/test
```

图片生成接口：

```text
POST /api/admin/share-summary/runs/{runId}/image
POST /api/admin/share-summary/runs/{runId}/image/regenerate
GET  /api/admin/share-summary/runs/{runId}/images
GET  /api/admin/share-summary/images/{imageId}
```

公开访问接口：

```text
GET /share-summary/og-images/{publicToken}.{ext}
GET /share-summary/reports/{publicToken}
```

说明：

- 后台接口需要复用现有后台鉴权。
- 公开图片接口不能要求登录，否则社交平台无法抓取 `og:image`。
- 公开报告页是否展示完整报告，需要由分享总结模块的公开分享策略控制。

## 状态定义

图片生成状态：

| 状态 | 说明 |
| --- | --- |
| `DISABLED` | 全局未启用 AI 生图，仅用于前端展示。 |
| `NOT_GENERATED` | 当前报告尚未生成图片。 |
| `PENDING` | 已创建生成任务，等待执行。 |
| `GENERATING` | 正在调用上游或处理图片。 |
| `SUCCESS` | 图片生成成功且已有 LinkPeek OG URL。 |
| `FAILED` | 生图或图片处理失败。 |
| `TIMEOUT` | 上游请求或异步轮询超时。 |

## 异常与边界

- 分享总结报告为 `EMPTY` 时不自动生成图片。
- 分享总结报告为 `FAILED` 时不生成图片。
- 全局配置关闭时，不自动生成新图片，但历史图片仍可查看和复制。
- Provider 未配置、API Key 为空、模型为空时，手动生成应返回配置错误。
- 上游返回 URL 但下载失败时，图片记录为 `FAILED`。
- 上游返回 base64 但解码失败时，图片记录为 `FAILED`。
- 图片生成失败不回滚分享总结报告。
- 重新生成失败不删除上一张成功图片。
- 配置变更只影响后续生成，历史图片保留生成时的配置快照。
- 删除分享总结任务不应删除历史图片，除非明确执行历史清理。
- 清理历史记录时，应同步清理对应图片文件或对象存储资源。

## 后台交互细节

### 配置弹窗

弹窗建议分区：

1. 基础开关
   - 启用 AI 生图
   - 自动生成
2. 风格配置
   - 风格提示词
   - 负面提示词
3. Provider 配置
   - Provider 类型
   - Base URL
   - API Key
   - Model
   - Timeout，默认 300 秒
4. 输出配置
   - 图片尺寸
   - 输出格式
   - 图片质量

保存行为：

- 表单校验通过后保存。
- API Key 为空且已有旧值时，表示不修改旧 Key。
- API Key 输入新值时覆盖旧 Key。
- 保存后不明文回显 API Key。

测试行为：

- `测试配置` 可选。
- 测试使用一段固定 prompt 生成测试图。
- 测试图不进入分享总结历史记录。
- 测试失败时展示错误原因。

### 历史记录

历史列表中：

- 没有图片时显示 `未生成`。
- 正在生成时显示加载状态，但不阻塞报告查看。
- 成功时显示缩略图。
- 失败时显示失败标记和重试入口。

历史详情中：

- 图片区域展示最近一次成功图。
- 生成记录列表展示每次尝试的状态、模型、时间、错误。
- 复制按钮成功后给出轻量提示。

## 权限与安全

- AI 生图配置只能由后台管理员查看和修改。
- API Key 不得通过 GET 接口明文返回。
- `raw_response_snapshot` 必须脱敏，不能保存 API Key、鉴权 header 或完整敏感响应。
- 公开图片 URL 不需要登录，但必须不可枚举。
- 公开报告页如果展示完整报告，需要确认报告内容允许公开。
- Prompt 中不应注入 API Key、Cookie、内部路径等敏感信息。

## MVP 范围

第一版建议实现：

1. 全局 AI 生图配置弹窗。
2. 支持 `OPENAI_IMAGES` 或 `OPENAI_COMPATIBLE` 同步格式。
3. 支持解析 `data[0].url` 和 `data[0].b64_json`。
4. 分享总结成功后可自动生成图片。
5. 历史记录可手动生成、重新生成、查看图片。
6. 图片落到 LinkPeek 自己的存储路径。
7. 生成公开图片 OG URL。
8. 按报告时间范围生成 `og_title`。
9. 分享页或详情数据中返回 `og_title`、`og_description`、`og_image_url`。
10. 生图失败可查看错误并重试。

第二期再考虑：

- `OPENAI_RESPONSES` 的完整支持。
- 异步生图 Provider。
- 自定义 JSONPath 响应提取。
- 多张候选图选择。
- 风格预设管理。
- 品牌 Logo、水印和模板化排版。
- 历史报告批量补图。

## 验收标准

1. 后台“分享总结”模块存在 AI 生图配置入口，并以弹窗形式打开。
2. 管理员可以配置启用状态、自动生成、风格提示词、Provider、Base URL、API Key、模型和输出尺寸。
3. 保存配置后刷新页面仍能读取配置，API Key 不明文回显。
4. 启用自动生成后，新的成功分享总结会触发图片生成。
5. 图片生成过程中，分享总结报告已可正常查看。
6. OpenAI-compatible 接口返回 `data[0].url` 时，系统能下载图片并保存为 LinkPeek 自己的 OG 图片。
7. OpenAI-compatible 接口返回 `data[0].b64_json` 时，系统能解码图片并保存为 LinkPeek 自己的 OG 图片。
8. 最终图片 URL 可以在未登录状态下访问，并返回正确图片内容。
9. 历史记录中可以看到图片缩略图、图片状态和失败原因。
10. 历史记录中可以复制图片 OG URL。
11. 如果提供分享页，则历史记录中可以复制分享页 URL。
12. `og:title` 能根据报告时间范围生成，例如 `LinkPeek - 2026年5月月报`。
13. 分享页 HTML 中包含 `og:title`、`og:description`、`og:image`、`og:type`、`og:url`。
14. 生图失败不会改变分享总结报告的 `SUCCESS` 状态。
15. 重新生成失败时，上一张成功图片仍可查看和复制。

## 参考

- OpenAI Image generation guide: https://platform.openai.com/docs/guides/image-generation
- OpenAI image generation tool guide: https://platform.openai.com/docs/guides/tools-image-generation
- OpenAI Images API reference: https://platform.openai.com/docs/api-reference/images/generate
