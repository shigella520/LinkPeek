# 分享总结 AI 生图与 Open Graph 现状说明

本文说明当前代码中的分享总结 AI 生图和 Open Graph 分享页实现。文件名保留为 `share-summary-ai-image-requirements.md`，用于兼容既有链接。

## 功能范围

当前实现支持：

- 全局 AI 生图配置。
- 成功分享总结报告的自动生图。
- 单条报告的手动生成和重新生成。
- OpenAI-compatible 图片生成接口。
- 解析上游返回的 `data[0].b64_json` 或 `data[0].url`。
- 下载/解码上游图片并标准化为 `1200x630`。
- 保存到 LinkPeek 自己的存储路径。
- 公开 OG 图片 URL 和公开报告分享页 URL。
- 图片成功和失败通知事件。
- 后台查看图片状态、生成记录、OG 字段、错误信息和复制链接。

当前图片 Provider 类型只有：

```text
OPENAI_COMPATIBLE
```

## 配置

配置表是单例 `share_summary_image_config`，后台接口为：

```text
GET  /api/admin/share-summary/image-config
PUT  /api/admin/share-summary/image-config
POST /api/admin/share-summary/image-config/test
```

配置字段：

| 字段 | 说明 |
| --- | --- |
| `enabled` | 是否启用 AI 生图。 |
| `autoGenerate` | 分享总结成功后是否自动生成图片。 |
| `providerType` | 当前为 `OPENAI_COMPATIBLE`。 |
| `baseUrl` | 上游 API Base URL。 |
| `endpointPath` | 请求路径，默认 `/v1/images/generations`。 |
| `apiKey` | 上游 API Key；保存后不明文回显，空值表示保留旧值。 |
| `model` | 生图模型。 |
| `imageSize` | 上游生图尺寸：`auto`、`1024x1024`、`1536x1024`、`1024x1536`。 |
| `quality` | 图片质量，默认 `auto`。 |
| `outputFormat` | 最终输出格式：`png` 或 `jpg`。 |
| `stylePrompt` | 全局风格提示词。 |
| `requestTimeoutSeconds` | 单次请求超时，允许 `1..1800` 秒。 |

默认风格提示词由服务端提供。保存时会校验 Provider 类型、尺寸、输出格式和超时范围。

## 自动生成

分享总结执行记录进入 `SUCCESS` 后，服务会调用图片自动生成检查。

满足以下条件时创建图片任务：

- 图片配置 `enabled = true`
- 图片配置 `auto_generate = true`
- 分享总结报告状态为 `SUCCESS`
- 当前报告没有成功图片
- 当前报告没有 `PENDING` 或 `GENERATING` 图片任务

自动生成不会阻塞分享总结报告保存。图片任务先保存为 `PENDING`，再由单线程 executor 异步执行。

如果任务队列满或执行异常，图片记录会进入 `FAILED`，报告仍保持 `SUCCESS`。

## 手动生成和重新生成

后台接口：

```text
POST /api/admin/share-summary/runs/{runId}/image
POST /api/admin/share-summary/runs/{runId}/image/regenerate
GET  /api/admin/share-summary/runs/{runId}/images
GET  /api/admin/share-summary/images/{imageId}
```

规则：

- 只有 `SUCCESS` 报告可以生成图片。
- 普通生成在已有成功图片时会返回冲突。
- 重新生成会创建新的图片记录，旧图片保留。
- 同一报告同一时间只允许一个 `PENDING` 或 `GENERATING` 图片任务。
- 当前展示优先使用最近一次成功图片。
- 新图生成失败不会删除上一张成功图片。

## 图片生成流程

```text
创建 PENDING 记录
        |
        v
异步任务更新为 GENERATING
        |
        v
调用 OpenAI-compatible 图片接口
        |
        +--> 读取 data[0].b64_json -> base64 解码
        |
        +--> 读取 data[0].url -> 下载图片
        |
        v
解码图片 -> 裁剪/缩放到 1200x630 -> 重新编码
        |
        v
保存到 CACHE_DIR/share-summary/images/{run_id}/{image_id}.{ext}
        |
        v
写入 image_url / og_image_url / og_page_url
        |
        v
更新为 SUCCESS 并发布通知事件
```

失败时：

- 图片记录进入 `FAILED`。
- 保存错误信息。
- 发布图片失败通知事件。
- 不回滚分享总结报告。

## 上游请求格式

当前客户端使用 OpenAI-compatible 同步图片接口：

```text
POST {baseUrl}{endpointPath}
Authorization: Bearer {apiKey}
Content-Type: application/json
```

请求体包含：

- `model`
- `prompt`
- `size`
- `quality`
- `n = 1`
- `response_format = b64_json`

响应提取优先级：

1. `data[0].b64_json`
2. `data[0].url`

如果上游返回 URL，LinkPeek 必须下载并重新存储，不会把上游临时 URL 直接作为最终 OG 图片 URL。

## Prompt 组装

最终 prompt 由服务端根据报告信息组装，不直接等于用户配置的风格提示词。

Prompt 内容包含：

- OG 标题
- 报告时间范围
- 报告类型
- 报告正文摘要
- 风格提示词
- 图片要求

报告正文会被截断到适合生图 prompt 的长度，避免请求体过大。提示词会要求横版构图、适合社交平台预览、主题体现数据分析和链接洞察，并避免生成复杂 UI 截图、大量文字、个人信息、密钥、二维码或水印。

## Open Graph 规则

### OG 标题

OG 标题由报告周期和窗口生成：

| 周期 | 示例 |
| --- | --- |
| `DAILY` | `LinkPeek - 2026-05-29 日报` |
| `WEEKLY` | `LinkPeek - 2026年第22周周报` |
| `MONTHLY` | `LinkPeek - 2026年5月月报` |

周报使用自然周，周一为一周开始。

### OG 描述

默认描述：

```text
本报告汇总了 {window_start_label} 至 {window_end_label} 的链接分享与内容洞察。
```

描述不包含 HTML。

### OG 图片

最终图片要求：

| 项 | 当前实现 |
| --- | --- |
| 尺寸 | 固定输出 `1200x630`。 |
| 格式 | `png` 或 `jpg`。 |
| 访问 | 公开访问，不要求后台登录。 |
| URL | 使用不可枚举 public token。 |
| 响应头 | 根据格式返回 `image/png` 或 `image/jpeg`。 |
| 存储 | 保存到 LinkPeek 自己的 `CACHE_DIR`。 |

公开图片接口：

```text
GET /share-summary/og-images/{publicToken}.{ext}
```

### 分享页

公开报告页接口：

```text
GET /share-summary/reports/{publicToken}
```

分享页包含：

```html
<meta property="og:title" content="{og_title}">
<meta property="og:description" content="{og_description}">
<meta property="og:image" content="{og_image_url}">
<meta property="og:type" content="article">
<meta property="og:url" content="{og_page_url}">
```

页面会展示报告标题、描述和报告正文。

## 下载与图片安全

如果上游返回图片 URL，服务端下载时会校验：

- URL 必须是 `http` 或 `https`。
- host 必须存在。
- 解析出的 IP 不允许是 any-local、loopback、link-local、site-local 或 multicast。
- 最多跟随有限次数重定向。
- 响应必须是 `2xx`。
- 响应大小不能超过上限。

保存前会：

- 校验图片可解码。
- 裁剪/缩放到 `1200x630`。
- 重新编码，去除上游图片元数据。
- 将文件写入规范化后的存储根目录内，避免路径穿越。

## 状态

图片状态：

| 状态 | 说明 |
| --- | --- |
| `NOT_GENERATED` | 当前报告尚未生成图片；主要用于前端展示。 |
| `PENDING` | 已创建生成任务，等待执行。 |
| `GENERATING` | 正在调用上游或处理图片。 |
| `SUCCESS` | 图片生成成功且已有 LinkPeek 公开 URL。 |
| `FAILED` | 生图、下载、解码、转码、存储或队列提交失败。 |
| `TIMEOUT` | 状态枚举保留；当前超时通常以失败记录。 |

调度扫描会将长时间处于 `PENDING` 或 `GENERATING` 的图片任务标记为失败。

## 通知事件

图片成功后触发：

```text
SHARE_SUMMARY_IMAGE_SUCCESS
```

图片失败后触发：

```text
SHARE_SUMMARY_IMAGE_FAILED
```

事件发布失败不会回滚图片记录状态。

## 异常和边界

- 配置关闭时不自动生成新图片，历史图片仍可查看。
- Provider、Base URL、API Key、模型为空或无效时，手动生成会返回配置错误。
- 分享总结报告不是 `SUCCESS` 时不能生成图片。
- 同一报告已有进行中任务时，再次触发会返回冲突。
- 上游返回 URL 但下载失败时，图片记录为 `FAILED`。
- 上游返回 base64 但解码失败时，图片记录为 `FAILED`。
- 转码、写文件、队列提交失败时，图片记录为 `FAILED`。
- 重新生成失败不删除上一张成功图片。
- 删除分享总结执行记录时，会同步删除关联图片记录和图片文件。
