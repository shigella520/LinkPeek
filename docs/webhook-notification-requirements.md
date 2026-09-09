# Webhook 通知现状说明

本文说明当前代码中的 Webhook 通知系统。文件名保留为 `webhook-notification-requirements.md`，用于兼容既有链接。

通知系统把 LinkPeek 内部事件渲染为 Webhook 请求，并记录每次发送结果。它由事件 Schema、通知渠道、通知任务和发送记录组成。

## 功能范围

当前实现支持：

- 查询事件类型和占位符 Schema。
- 创建、编辑、启停、删除 Webhook 通知渠道。
- 测试通知渠道。
- 创建、编辑、启停、删除通知任务。
- 通知任务绑定一个或多个渠道。
- 模板保存时按事件类型校验占位符。
- 事件触发后异步发送 Webhook。
- 请求失败后按规则重试。
- HMAC-SHA256 签名。
- URL SSRF 防护。
- 保存完整请求体和请求体快照。
- 查看、删除、重试发送记录。

## 事件类型

当前事件枚举：

| 事件 | 触发时机 |
| --- | --- |
| `SHARE_SUMMARY_IMAGE_SUCCESS` | 分享总结图片生成成功，并已写入公开图片 URL 和分享页 URL。 |
| `SHARE_SUMMARY_IMAGE_FAILED` | 分享总结图片生成记录进入失败状态。 |
| `SHARE_SUMMARY_AUDIO_FAILED` | 分享总结音频生成记录进入失败状态。 |
| `AI_PROVIDER_REQUEST_FAILED` | AI Provider 请求失败，包括标题、总结等 AI 调用失败。 |
| `AI_PROVIDER_AUTO_DOWNGRADED` | AI Provider 连续失败达到阈值并执行自动降级。 |
| `DATA_CRAWL_REQUEST_FAILED` | 已匹配内容 provider 后，上游数据抓取失败并返回失败预览。 |

事件业务键示例：

```text
SHARE_SUMMARY_IMAGE_SUCCESS:{imageId}
SHARE_SUMMARY_IMAGE_FAILED:{imageId}
SHARE_SUMMARY_AUDIO_FAILED:{audioId}
AI_PROVIDER_REQUEST_FAILED:{providerId}:{occurredAt}
AI_PROVIDER_AUTO_DOWNGRADED:{providerId}:{occurredAt}
DATA_CRAWL_REQUEST_FAILED:{previewKey}:{occurredAt}
```

## 核心流程

```text
内部事件产生
        |
        v
查询启用且事件类型匹配的通知任务
        |
        v
按任务过滤条件筛选
        |
        v
用事件数据渲染任务消息模板
        |
        v
用渠道 Body 模板组装最终请求体
        |
        v
为每个启用渠道创建 notification_delivery
        |
        v
异步发送 Webhook，失败时按规则重试
        |
        v
更新发送记录状态、响应、错误和耗时
```

如果没有启用任务或没有启用渠道，系统只记录日志，不影响原业务流程。

## 占位符 Schema

接口：

```text
GET /api/admin/notifications/events
GET /api/admin/notifications/events/{eventType}/placeholders
```

每个事件都有独立 Schema，包含：

| 字段 | 说明 |
| --- | --- |
| `eventType` | 事件类型。 |
| `name` | 事件中文名。 |
| `description` | 事件说明。 |
| `placeholders` | 当前事件可用占位符列表。 |

占位符字段：

| 字段 | 说明 |
| --- | --- |
| `group` | 分组，例如 event、run、image、audio、provider、request、error、system。 |
| `name` | 占位符变量名，例如 `image.ogPageUrl`。 |
| `type` | `string`、`number`、`boolean` 等。 |
| `label` | 中文名称。 |
| `description` | 字段说明。 |
| `example` | 示例值。 |
| `required` | 事件发生时是否必定有值。 |

通用事件占位符：

| 占位符 | 说明 |
| --- | --- |
| `event.type` | 事件类型。 |
| `event.key` | 事件业务键。 |
| `event.occurredAt` | 事件时间，epoch milliseconds。 |
| `event.occurredAtIso` | 事件 ISO 时间。 |
| `system.baseUrl` | LinkPeek 对外基础 URL。 |
| `system.appName` | 应用名称。 |

## 分享总结图片事件字段

图片成功和失败事件会携带分享总结执行字段和图片字段。

分享总结字段：

| 占位符 | 说明 |
| --- | --- |
| `run.id` | 执行记录 ID。 |
| `run.taskId` | 分享总结任务 ID。 |
| `run.taskName` | 执行时任务名称快照。 |
| `run.triggerType` | `SCHEDULED` 或 `MANUAL`。 |
| `run.periodType` | `DAILY`、`WEEKLY` 或 `MONTHLY`。 |
| `run.windowStart` / `run.windowEnd` | 窗口时间。 |
| `run.windowStartLabel` / `run.windowEndLabel` | 窗口日期标签。 |
| `run.status` | 总结状态。 |
| `run.linkCount` | 原始链接创建记录数。 |
| `run.uniqueLinkCount` | 去重链接标题数。 |
| `run.inputLinkCount` | 实际输入 AI 的链接数。 |
| `run.aiProviderNames` | 实际参与总结生成的 AI Provider 名称。 |
| `run.aiDurationMs` | 分享总结 AI 耗时。 |
| `run.report` | 分享总结报告正文。 |

图片字段：

| 占位符 | 说明 |
| --- | --- |
| `image.id` | 图片记录 ID。 |
| `image.runId` | 关联执行 ID。 |
| `image.attemptNo` | 同一报告第几次生图。 |
| `image.status` | 图片状态。 |
| `image.providerType` | 生图 Provider 类型。 |
| `image.model` | 生图模型。 |
| `image.imageSize` | 上游尺寸配置。 |
| `image.outputFormat` | 输出格式。 |
| `image.quality` | 图片质量。 |
| `image.imageUrl` | 图片 URL。 |
| `image.ogImageUrl` | OG 图片 URL。 |
| `image.ogPageUrl` | 公开分享页 URL。 |
| `image.ogShareUrl` | 推荐转发 URL，当前等同于 `ogPageUrl`。 |
| `image.ogTitle` | OG 标题。 |
| `image.ogDescription` | OG 描述。 |
| `image.durationMs` | 生图耗时。 |
| `image.createdAt` / `image.startedAt` / `image.finishedAt` | 图片任务时间。 |
| `image.errorMessage` | 失败事件中可用的图片错误信息。 |

失败事件还包含：

| 占位符 | 说明 |
| --- | --- |
| `error.type` | 错误类型。 |
| `error.message` | 错误信息。 |

## 其他事件字段

音频失败事件包含 `run.*`、`audio.*` 和 `error.*`。

AI Provider 请求失败事件包含：

- `provider.id`
- `provider.name`
- `provider.enabled`
- `provider.sortOrder`
- `provider.baseUrl`
- `provider.apiKind`
- `provider.model`
- `provider.requestTimeoutSeconds`
- `request.operation`
- `request.durationMs`
- `error.type`
- `error.message`
- `downgrade.enabled`
- `downgrade.failureCount`
- `downgrade.failureThreshold`
- `downgrade.triggered`

AI Provider 自动降级事件包含 Provider、request、error 字段，以及：

- `downgrade.failureCount`
- `downgrade.failureThreshold`
- `downgrade.oldSortOrder`
- `downgrade.newSortOrder`
- `downgrade.alreadyLowest`
- `downgrade.providerCount`

数据抓取失败事件包含：

- `preview.previewKey`
- `preview.providerId`
- `preview.sourceUrl`
- `preview.canonicalUrl`
- `request.clientType`
- `request.httpStatus`
- `request.durationMs`
- `request.requestedStyle`
- `error.code`
- `error.type`
- `error.message`

## 模板规则

任务消息模板：

- 保存字段是 `notification_task.template_json`。
- 模板必须非空，最大 20 KB。
- 占位符最多 200 个。
- 只能使用任务事件类型对应 Schema 中的占位符。
- 渲染后最大 256 KB。

渠道 Body 模板：

- 保存字段是 `notification_channel.body_template`。
- 默认值是 `{{message.bodyJson}}`。
- 只允许使用 `message.body` 和 `message.bodyJson`。
- `message.body` 会作为字符串插入。
- `message.bodyJson` 会作为原始 JSON 插入。

渲染行为：

- `"{{number.placeholder}}"` 这类完整 JSON 字符串位置会被替换为 JSON 值。
- 字符串片段中的占位符会做 JSON 字符串转义。
- 空值在字符串片段中渲染为空字符串。

模板校验接口：

```text
POST /api/admin/notifications/tasks/validate-template
```

## 通知渠道

后台接口：

```text
GET    /api/admin/notifications/channels
POST   /api/admin/notifications/channels
PUT    /api/admin/notifications/channels/{channelId}
DELETE /api/admin/notifications/channels/{channelId}
POST   /api/admin/notifications/channels/{channelId}/test
```

渠道字段：

| 字段 | 说明 |
| --- | --- |
| `name` | 渠道名称。 |
| `enabled` | 是否启用。 |
| `type` | 当前固定为 `WEBHOOK`。 |
| `url` | Webhook URL。 |
| `method` | 当前固定为 `POST`。 |
| `headersJson` | 自定义 Header JSON 对象。 |
| `bodyTemplate` | Body 模板，默认 `{{message.bodyJson}}`。 |
| `secret` | 可选签名密钥；保存后不明文回显。 |
| `timeoutSeconds` | 请求超时，允许 `1..60`。 |

删除渠道时，如果已有通知任务关联该渠道，会返回冲突，需要先解除关联。

测试渠道使用固定测试消息，不依赖具体事件数据。

## 通知任务

后台接口：

```text
GET    /api/admin/notifications/tasks
POST   /api/admin/notifications/tasks
PUT    /api/admin/notifications/tasks/{taskId}
DELETE /api/admin/notifications/tasks/{taskId}
```

任务字段：

| 字段 | 说明 |
| --- | --- |
| `name` | 任务名称。 |
| `enabled` | 是否启用。 |
| `eventType` | 事件类型。 |
| `filters` | 匹配条件 JSON。 |
| `templateJson` | 消息正文模板。 |
| `channelIds` | 关联渠道 ID 列表，至少一个。 |

当前只有 `SHARE_SUMMARY_IMAGE_SUCCESS` 会使用分享总结过滤条件：

```json
{
  "shareSummaryTaskIds": [1, 2],
  "periodTypes": ["WEEKLY", "MONTHLY"],
  "triggerTypes": ["SCHEDULED"]
}
```

其他事件类型保存时会忽略 filters。

匹配规则：

- 任务必须启用。
- 任务事件类型必须等于事件类型。
- 关联渠道中只有启用渠道会发送。
- 任一过滤字段为空或缺失时表示不过滤。
- 非空过滤字段全部满足才匹配。

## Webhook 请求

发送时固定使用：

```text
POST {channel.url}
Content-Type: application/json
User-Agent: LinkPeek-Webhook/1.0
X-LinkPeek-Event: {eventType}
X-LinkPeek-Timestamp: {occurredAt}
```

如果配置了 `secret`，会增加：

```text
X-LinkPeek-Signature: sha256={hmac_sha256(secret, occurredAt + "." + body)}
```

自定义 Header 限制：

- Header 值必须是字符串、数字或布尔。
- 不允许覆盖 `Host`、`Content-Length`、`Connection`、`Transfer-Encoding`。

URL 安全：

- URL 必须是 `http` 或 `https`。
- host 必须存在。
- 解析出的地址不允许是 any-local、loopback、link-local、site-local 或 multicast。

## 发送记录

后台接口：

```text
GET    /api/admin/notifications/deliveries
GET    /api/admin/notifications/deliveries/{deliveryId}
DELETE /api/admin/notifications/deliveries/{deliveryId}
POST   /api/admin/notifications/deliveries/{deliveryId}/retry
```

发送记录字段对应 `notification_delivery`：

| 字段 | 说明 |
| --- | --- |
| `event_type` | 事件类型。 |
| `event_key` | 事件业务键。 |
| `notification_task_id` / `notification_task_name` | 发送时任务快照。 |
| `channel_id` / `channel_name` | 发送时渠道快照。 |
| `status` | `PENDING`、`SUCCESS`、`FAILED`。 |
| `attempt_count` | 尝试次数。 |
| `request_url` | 请求 URL。 |
| `request_body` | 完整请求体，用于重试。 |
| `request_body_snapshot` | 请求体快照，限制 8000 字符。 |
| `response_status` | HTTP 状态码。 |
| `response_body_snapshot` | 响应体摘要。 |
| `error_message` | 失败原因。 |
| `duration_ms` | 最后一次发送耗时。 |
| `created_at` / `finished_at` | 创建和完成时间。 |

分页列表支持按事件类型、任务、渠道和状态筛选。

## 发送与重试策略

- 通知发送使用单线程 executor 和有界队列。
- 每条发送记录最多尝试 3 次。
- HTTP `2xx` 视为成功。
- HTTP `5xx` 和网络异常可重试。
- HTTP `4xx`、模板错误、URL 校验失败和签名生成失败不重试。
- 重试间隔依次为 1 秒、5 秒、30 秒。
- 队列满时，发送记录会标记为 `FAILED`，错误为 `NOTIFICATION_QUEUE_FULL`。

手动重试发送记录时：

- 会重新读取当前渠道配置。
- 会复用原始请求体。
- 如果只有可能被截断的请求体快照，则拒绝重试。

## 异常和边界

- 通知失败不影响原业务状态，例如图片成功或报告成功不会回滚。
- 单个渠道失败不影响同一任务的其他渠道发送。
- 模板渲染失败时不发送 Webhook，发送记录标记为失败。
- 没有启用渠道时不创建发送记录，只记录跳过日志。
- 删除通知任务不会删除历史发送记录。
- 删除通知渠道前必须解除任务关联。
- 事件发布被 executor 拒绝时会记录日志，不回滚业务流程。
