# Webhook 通知需求定义

## 背景

LinkPeek 已经支持分享总结任务，并可在分享总结报告成功后生成 AI 分享图、公开图片 URL 和 Open Graph 分享页 URL。对于外部协作工具、自动化工作流和运营渠道来说，只有分享图和公开分享页生成完成后，通知才具备直接转发和消费价值。

因此 Webhook 通知第一版不以“分享总结报告生成成功”为事件口径，而是以“分享总结图片和分享页生成成功”为事件口径。

## 目标

- 支持配置 Webhook 通知渠道。
- 支持配置通知任务，并将任务绑定到内部事件。
- 第一版只支持 `SHARE_SUMMARY_IMAGE_SUCCESS` 事件。
- 通知模板可使用事件内置数据占位符。
- 配置通知模板时，前端必须根据当前选择的事件类型展示可用占位符。
- 服务端必须按事件类型校验模板占位符，禁止使用与事件类型不匹配或不存在的占位符。
- 一个通知任务可以关联多个通知渠道。
- 分享总结 AI 图片生成成功后，自动触发匹配的通知任务。
- 每次通知发送都记录结果，便于排查失败原因。

## 非目标

- 第一版不支持 `SHARE_SUMMARY_SUCCESS`，因为报告生成成功但图片和分享页尚未准备好，业务价值不足。
- 第一版不支持复杂事件编排、条件表达式或脚本化模板。
- 第一版不支持除 Webhook 以外的通知渠道，例如邮件、企业微信、飞书或 Telegram 原生 Bot。
- 第一版不要求通知任务失败后影响分享总结或图片生成结果。
- 第一版不要求通知任务严格一次且仅一次送达；允许在失败重试、服务重启或人工重发场景下产生重复通知。

## 术语

| 术语 | 说明 |
| --- | --- |
| 通知渠道 | 一个可发送 Webhook 请求的目标配置，例如 URL、请求头和超时时间。 |
| 通知任务 | 一条事件匹配和消息模板配置，关联一个或多个通知渠道。 |
| 内部事件 | LinkPeek 内部发生的业务事件。第一版仅 `SHARE_SUMMARY_IMAGE_SUCCESS`。 |
| 事件数据 | 内部事件携带的结构化数据，用于模板渲染和条件匹配。 |
| 占位符 | 模板中的变量引用，例如 `{{image.ogPageUrl}}`。 |
| 占位符 Schema | 某个事件类型支持的占位符集合、类型和说明。 |
| 发送记录 | 一次通知任务向一个通知渠道发送 Webhook 的执行记录。 |

## 核心流程

```text
配置通知渠道
  -> 配置通知任务
      -> 选择事件类型
      -> 基于事件类型查看可用占位符
      -> 配置消息模板
      -> 关联一个或多个通知渠道
  -> 分享总结 AI 图片生成成功
  -> 系统产生 SHARE_SUMMARY_IMAGE_SUCCESS 事件
  -> 查找匹配的启用通知任务
  -> 渲染模板
  -> 向关联渠道发送 Webhook
  -> 记录每个渠道的发送结果
```

## 事件范围

### SHARE_SUMMARY_IMAGE_SUCCESS

触发时机：

- `share_summary_image.status` 从 `GENERATING` 或 `PENDING` 更新为 `SUCCESS` 后触发。
- 触发前必须已经保存以下字段：
  - `image_url`
  - `og_image_url`
  - `og_page_url`
  - `og_title`
  - `og_description`
  - `finished_at`

事件语义：

- 分享总结报告已经成功生成。
- AI 分享图已经成功生成并由 LinkPeek 自己存储。
- 公开图片 URL 可访问。
- 公开分享页 URL 可用于直接发送给聊天工具或社交平台抓取 Open Graph meta。

触发位置建议：

- 在 `ShareSummaryImageService.generateImageNow(...)` 中，图片记录成功 `updateImage(image)` 之后发布事件。
- 事件发布失败或通知发送失败不得回滚图片成功状态。

## 事件数据

`SHARE_SUMMARY_IMAGE_SUCCESS` 事件需要聚合分享总结运行记录和图片记录。

### 基础事件字段

| 占位符 | 类型 | 说明 |
| --- | --- | --- |
| `{{event.type}}` | string | 固定为 `SHARE_SUMMARY_IMAGE_SUCCESS`。 |
| `{{event.occurredAt}}` | number | 事件发生时间，epoch milliseconds。 |
| `{{event.occurredAtIso}}` | string | 事件发生时间，ISO-8601 字符串。 |

### 分享总结运行字段

| 占位符 | 类型 | 说明 |
| --- | --- | --- |
| `{{run.id}}` | number | 分享总结执行记录 ID。 |
| `{{run.taskId}}` | number | 分享总结任务 ID。 |
| `{{run.taskName}}` | string | 执行时任务名称快照。 |
| `{{run.triggerType}}` | string | 触发方式，例如 `SCHEDULED` 或 `MANUAL`。 |
| `{{run.periodType}}` | string | 周期类型，例如 `DAILY`、`WEEKLY`、`MONTHLY`。 |
| `{{run.windowStart}}` | number | 总结窗口开始时间，epoch milliseconds。 |
| `{{run.windowEnd}}` | number | 总结窗口结束时间，epoch milliseconds。 |
| `{{run.windowStartLabel}}` | string | 总结窗口开始日期标签，例如 `2026-05-01`。 |
| `{{run.windowEndLabel}}` | string | 总结窗口结束日期标签，例如 `2026-06-01`。 |
| `{{run.status}}` | string | 分享总结执行状态，应为 `SUCCESS`。 |
| `{{run.linkCount}}` | number | 原始链接创建记录数。 |
| `{{run.uniqueLinkCount}}` | number | 去重后的链接标题数。 |
| `{{run.inputLinkCount}}` | number | 实际输入 AI 总结的链接数量。 |
| `{{run.aiProviderNames}}` | string | 实际参与总结生成的 AI Provider 名称。 |
| `{{run.aiDurationMs}}` | number | 分享总结 AI 调用耗时。 |
| `{{run.report}}` | string | 分享总结报告正文。 |

### 分享图字段

| 占位符 | 类型 | 说明 |
| --- | --- | --- |
| `{{image.id}}` | number | 分享图记录 ID。 |
| `{{image.runId}}` | number | 关联的分享总结执行记录 ID。 |
| `{{image.attemptNo}}` | number | 同一报告的第几次生图。 |
| `{{image.status}}` | string | 图片状态，应为 `SUCCESS`。 |
| `{{image.providerType}}` | string | 生图 Provider 类型快照。 |
| `{{image.model}}` | string | 生图模型快照。 |
| `{{image.imageSize}}` | string | 上游生图尺寸配置快照。 |
| `{{image.outputFormat}}` | string | 最终输出格式，例如 `png` 或 `jpg`。 |
| `{{image.quality}}` | string | 图片质量配置快照。 |
| `{{image.imageUrl}}` | string | 后台或公开可访问图片 URL。 |
| `{{image.ogImageUrl}}` | string | 可用于 `og:image` 的公开图片 URL。 |
| `{{image.ogPageUrl}}` | string | 带完整 Open Graph meta 的公开分享页 URL。 |
| `{{image.ogShareUrl}}` | string | 推荐转发 URL，第一版等同于 `ogPageUrl`。 |
| `{{image.ogTitle}}` | string | Open Graph 标题。 |
| `{{image.ogDescription}}` | string | Open Graph 描述。 |
| `{{image.durationMs}}` | number | 生图耗时。 |
| `{{image.createdAt}}` | number | 图片记录创建时间，epoch milliseconds。 |
| `{{image.startedAt}}` | number | 生图开始时间，epoch milliseconds。 |
| `{{image.finishedAt}}` | number | 生图结束时间，epoch milliseconds。 |

### 系统字段

| 占位符 | 类型 | 说明 |
| --- | --- | --- |
| `{{system.baseUrl}}` | string | LinkPeek 对外基础 URL。 |
| `{{system.appName}}` | string | 应用名称，默认 `LinkPeek`。 |

## 占位符 Schema 要求

占位符必须与事件类型绑定。前端展示、模板编辑、模板校验和服务端渲染都必须使用同一份事件占位符 Schema。

### Schema 内容

每个事件类型至少提供：

| 字段 | 说明 |
| --- | --- |
| `eventType` | 事件类型，例如 `SHARE_SUMMARY_IMAGE_SUCCESS`。 |
| `placeholders` | 当前事件支持的占位符列表。 |
| `name` | 占位符变量名，例如 `image.ogPageUrl`。 |
| `type` | 数据类型，例如 `string`、`number`、`boolean`。 |
| `label` | 面向用户的中文名称。 |
| `description` | 字段说明。 |
| `example` | 示例值。 |
| `required` | 事件发生时是否必定有值。 |

### 前端交互要求

通知任务表单中：

1. 用户先选择事件类型。
2. 系统根据事件类型加载该事件的占位符 Schema。
3. 模板编辑区旁展示当前事件可用占位符。
4. 占位符按分组展示，例如“事件信息”“分享总结”“分享图”“系统信息”。
5. 点击占位符可插入到模板光标位置。
6. 切换事件类型时，必须重新校验当前模板。
7. 当前模板存在新事件不支持的占位符时，必须明确提示并阻止保存。

第一版虽然只有 `SHARE_SUMMARY_IMAGE_SUCCESS`，也必须按事件类型加载占位符，不能写死为全局占位符列表。这样后续新增事件时不会破坏模板配置模型。

### 服务端校验要求

创建或更新通知任务时：

- 服务端解析模板中的所有 `{{...}}` 占位符。
- 服务端根据任务的 `event_type` 获取允许的占位符集合。
- 模板中出现未知占位符时，返回 `400 Bad Request`。
- 模板中出现属于其他事件类型的占位符时，返回 `400 Bad Request`。
- 错误信息需要包含具体非法占位符，便于前端定位。

发送通知时：

- 服务端再次基于事件类型渲染模板。
- 如果模板在历史配置中已经不兼容当前 Schema，发送应标记为失败并记录错误，不应发送半成品消息。

## 消息模板

### 模板格式

第一版建议模板直接表示 Webhook JSON 请求体。

示例：

```json
{
  "event": "{{event.type}}",
  "title": "{{image.ogTitle}}",
  "description": "{{image.ogDescription}}",
  "shareUrl": "{{image.ogShareUrl}}",
  "imageUrl": "{{image.ogImageUrl}}",
  "taskName": "{{run.taskName}}",
  "periodType": "{{run.periodType}}",
  "window": "{{run.windowStartLabel}} 至 {{run.windowEndLabel}}",
  "linkCount": {{run.linkCount}},
  "uniqueLinkCount": {{run.uniqueLinkCount}},
  "report": "{{run.report}}"
}
```

### 渲染规则

- `{{name}}` 引用当前事件支持的占位符。
- 字符串占位符写在 JSON 字符串内时，必须执行 JSON 字符串转义。
- 数字占位符可以不加引号，渲染为 JSON number。
- 空值在字符串上下文中渲染为空字符串。
- 空值在非字符串上下文中渲染为 `null`。
- 模板渲染后必须是合法 JSON，否则本次发送失败并记录错误。

### 模板限制

建议限制：

| 项 | 限制 |
| --- | --- |
| 模板长度 | 最大 20 KB。 |
| 渲染后请求体 | 最大 256 KB。 |
| 占位符数量 | 最大 200 个。 |

第一版不支持：

- 条件语句。
- 循环。
- 函数管道。
- 默认值表达式。
- 任意脚本执行。

## 通知渠道

### 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | INTEGER | 主键。 |
| `name` | TEXT | 渠道名称。 |
| `enabled` | INTEGER | 是否启用。 |
| `type` | TEXT | 第一版固定为 `WEBHOOK`。 |
| `url` | TEXT | Webhook URL。 |
| `method` | TEXT | 第一版固定为 `POST`。 |
| `headers_json` | TEXT | 自定义请求头 JSON 对象。 |
| `secret` | TEXT | 可选签名密钥，保存后不明文回显。 |
| `timeout_seconds` | INTEGER | 请求超时，默认 10 秒，范围 1-60 秒。 |
| `created_at` | INTEGER | 创建时间。 |
| `updated_at` | INTEGER | 更新时间。 |

### Webhook 请求

默认请求：

```text
POST {channel.url}
Content-Type: application/json
User-Agent: LinkPeek-Webhook/1.0
```

用户配置的 `headers_json` 可以追加自定义请求头，但不允许覆盖以下安全相关请求头：

- `Host`
- `Content-Length`
- `Connection`
- `Transfer-Encoding`

### 签名

第一版可以先不做签名。如果实现签名，建议：

```text
X-LinkPeek-Event: SHARE_SUMMARY_IMAGE_SUCCESS
X-LinkPeek-Timestamp: {event.occurredAt}
X-LinkPeek-Signature: sha256={hmac_sha256(secret, timestamp + "." + body)}
```

签名密钥保存后不明文回显，只返回 `secretConfigured`。

### URL 安全

发送 Webhook 前应校验：

- URL 必须是 `http` 或 `https`。
- URL host 不能为空。
- 默认禁止访问回环地址、内网地址、link-local 地址和 multicast 地址，降低 SSRF 风险。
- 如确需通知内网服务，可后续增加明确的系统级开关，不在第一版默认开放。

## 通知任务

### 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | INTEGER | 主键。 |
| `name` | TEXT | 任务名称。 |
| `enabled` | INTEGER | 是否启用。 |
| `event_type` | TEXT | 第一版只允许 `SHARE_SUMMARY_IMAGE_SUCCESS`。 |
| `filters_json` | TEXT | 匹配条件 JSON。 |
| `template_json` | TEXT | Webhook JSON 请求体模板。 |
| `created_at` | INTEGER | 创建时间。 |
| `updated_at` | INTEGER | 更新时间。 |

### 任务与渠道关联

通知任务和渠道是多对多关系：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `task_id` | INTEGER | 通知任务 ID。 |
| `channel_id` | INTEGER | 通知渠道 ID。 |

同一通知任务必须至少关联一个通知渠道。

### 匹配条件

第一版支持以下过滤条件：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `shareSummaryTaskIds` | number[] | 只匹配指定分享总结任务 ID。为空表示全部。 |
| `periodTypes` | string[] | 只匹配指定周期，例如 `DAILY`、`WEEKLY`、`MONTHLY`。为空表示全部。 |
| `triggerTypes` | string[] | 只匹配指定触发方式，例如 `SCHEDULED`、`MANUAL`。为空表示全部。 |

示例：

```json
{
  "shareSummaryTaskIds": [1, 2],
  "periodTypes": ["WEEKLY", "MONTHLY"],
  "triggerTypes": ["SCHEDULED"]
}
```

匹配规则：

- 任务必须启用。
- 任务事件类型必须等于事件类型。
- 所有关联渠道中，只有启用渠道才发送。
- 任一过滤字段为空数组或缺失时，表示该字段不过滤。
- 所有非空过滤字段都满足时才匹配。

## 发送记录

每个“事件 + 通知任务 + 通知渠道”生成一条发送记录。

### 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | INTEGER | 主键。 |
| `event_type` | TEXT | 事件类型。 |
| `event_key` | TEXT | 事件唯一业务键。 |
| `notification_task_id` | INTEGER | 通知任务 ID。 |
| `notification_task_name` | TEXT | 发送时任务名称快照。 |
| `channel_id` | INTEGER | 渠道 ID。 |
| `channel_name` | TEXT | 发送时渠道名称快照。 |
| `status` | TEXT | `PENDING` / `SUCCESS` / `FAILED`。 |
| `attempt_count` | INTEGER | 尝试次数。 |
| `request_url` | TEXT | 请求 URL 快照。 |
| `request_body_snapshot` | TEXT | 请求体快照，建议限制长度。 |
| `response_status` | INTEGER | HTTP 状态码。 |
| `response_body_snapshot` | TEXT | 响应体摘要，建议限制长度。 |
| `error_message` | TEXT | 失败原因。 |
| `duration_ms` | INTEGER | 最后一次请求耗时。 |
| `created_at` | INTEGER | 创建时间。 |
| `finished_at` | INTEGER | 完成时间。 |

### event_key

`SHARE_SUMMARY_IMAGE_SUCCESS` 的事件唯一业务键建议：

```text
SHARE_SUMMARY_IMAGE_SUCCESS:{image.id}
```

用途：

- 排查同一图片成功事件触发了哪些通知。
- 后续支持人工重发或防重复发送。

第一版可以不做数据库唯一约束，但发送记录必须保存 `event_key`。

### 状态规则

| 状态 | 说明 |
| --- | --- |
| `PENDING` | 已创建发送记录，尚未完成发送。 |
| `SUCCESS` | 收到 HTTP `2xx` 响应。 |
| `FAILED` | 请求异常、超时、模板错误或收到非 `2xx` 响应。 |

## 发送策略

第一版建议采用异步发送，避免阻塞图片生成线程。

流程：

1. 图片成功后发布内部事件。
2. 通知服务查找匹配任务。
3. 为每个启用渠道创建发送记录。
4. 在线程池中发送 Webhook。
5. 更新发送记录状态。

### 重试

第一版建议支持简单重试：

- 最多尝试 3 次。
- 初始发送失败后重试。
- 重试间隔可以为 1 秒、5 秒、30 秒。
- 模板渲染失败、URL 校验失败不重试。
- HTTP `5xx` 和网络异常可以重试。
- HTTP `4xx` 默认不重试。

如果不实现延迟队列，允许在同一异步任务中完成短间隔重试。

### 超时

- 默认渠道超时 10 秒。
- 最大 60 秒。
- 单次发送超时后记录失败或进入下一次重试。

## 后台页面

新增“通知”管理模块。

### 通知渠道列表

支持：

- 新建渠道。
- 编辑渠道。
- 启用或停用渠道。
- 删除渠道。
- 测试发送。

列表展示：

- 渠道名称。
- 渠道类型。
- 启用状态。
- URL 脱敏展示。
- 超时时间。
- 最近更新时间。

### 通知任务列表

支持：

- 新建任务。
- 编辑任务。
- 启用或停用任务。
- 删除任务。

任务表单：

- 任务名称。
- 启用状态。
- 事件类型。
- 事件匹配条件。
- 消息模板。
- 关联通知渠道，多选。
- 当前事件可用占位符面板。

### 占位符面板

占位符面板必须由事件类型驱动：

- 未选择事件类型时，不展示占位符或提示先选择事件。
- 选择 `SHARE_SUMMARY_IMAGE_SUCCESS` 后，只展示该事件支持的占位符。
- 每个占位符展示变量名、中文名称、类型和示例。
- 点击占位符插入模板。
- 模板校验错误要明确标出非法占位符。

### 发送记录列表

支持筛选：

- 事件类型。
- 通知任务。
- 通知渠道。
- 发送状态。
- 时间范围。

展示：

- 事件类型。
- 事件业务键。
- 任务名称。
- 渠道名称。
- 状态。
- HTTP 状态码。
- 尝试次数。
- 耗时。
- 错误信息摘要。
- 创建时间。

详情中展示：

- 请求 URL。
- 请求体快照。
- 响应状态。
- 响应体摘要。
- 完整错误信息。

## API 建议

所有后台接口复用现有后台登录鉴权。

### 事件 Schema

```text
GET /api/admin/notifications/events
GET /api/admin/notifications/events/{eventType}/placeholders
```

`GET /api/admin/notifications/events` 返回支持的事件类型列表及基础说明。

`GET /api/admin/notifications/events/{eventType}/placeholders` 返回指定事件的占位符 Schema。

### 通知渠道

```text
GET    /api/admin/notifications/channels
POST   /api/admin/notifications/channels
PUT    /api/admin/notifications/channels/{channelId}
DELETE /api/admin/notifications/channels/{channelId}
POST   /api/admin/notifications/channels/{channelId}/test
```

测试发送可以使用固定测试事件，也可以使用用户提供的测试请求体。第一版建议使用固定测试请求体，避免把测试逻辑和事件模板耦合。

### 通知任务

```text
GET    /api/admin/notifications/tasks
POST   /api/admin/notifications/tasks
PUT    /api/admin/notifications/tasks/{taskId}
DELETE /api/admin/notifications/tasks/{taskId}
POST   /api/admin/notifications/tasks/validate-template
```

`validate-template` 请求参数：

```json
{
  "eventType": "SHARE_SUMMARY_IMAGE_SUCCESS",
  "templateJson": {
    "title": "{{image.ogTitle}}",
    "shareUrl": "{{image.ogShareUrl}}"
  }
}
```

返回：

```json
{
  "valid": true,
  "placeholders": ["image.ogTitle", "image.ogShareUrl"]
}
```

非法占位符返回：

```json
{
  "valid": false,
  "invalidPlaceholders": ["run.notExists"]
}
```

### 发送记录

```text
GET /api/admin/notifications/deliveries
GET /api/admin/notifications/deliveries/{deliveryId}
```

后续可扩展：

```text
POST /api/admin/notifications/deliveries/{deliveryId}/retry
POST /api/admin/notifications/events/{eventKey}/redeliver
```

## 数据库设计建议

### notification_channel

```sql
CREATE TABLE IF NOT EXISTS notification_channel (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL,
    type TEXT NOT NULL,
    url TEXT NOT NULL,
    method TEXT NOT NULL,
    headers_json TEXT,
    secret TEXT,
    timeout_seconds INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
```

建议索引：

```sql
CREATE INDEX IF NOT EXISTS idx_notification_channel_enabled ON notification_channel (enabled, type);
```

### notification_task

```sql
CREATE TABLE IF NOT EXISTS notification_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    filters_json TEXT,
    template_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
```

建议索引：

```sql
CREATE INDEX IF NOT EXISTS idx_notification_task_event_enabled ON notification_task (event_type, enabled);
```

### notification_task_channel

```sql
CREATE TABLE IF NOT EXISTS notification_task_channel (
    task_id INTEGER NOT NULL,
    channel_id INTEGER NOT NULL,
    PRIMARY KEY (task_id, channel_id)
);
```

### notification_delivery

```sql
CREATE TABLE IF NOT EXISTS notification_delivery (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type TEXT NOT NULL,
    event_key TEXT NOT NULL,
    notification_task_id INTEGER NOT NULL,
    notification_task_name TEXT NOT NULL,
    channel_id INTEGER NOT NULL,
    channel_name TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    request_url TEXT NOT NULL,
    request_body_snapshot TEXT,
    response_status INTEGER,
    response_body_snapshot TEXT,
    error_message TEXT,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    finished_at INTEGER
);
```

建议索引：

```sql
CREATE INDEX IF NOT EXISTS idx_notification_delivery_event_key ON notification_delivery (event_key);
CREATE INDEX IF NOT EXISTS idx_notification_delivery_status_created ON notification_delivery (status, created_at);
CREATE INDEX IF NOT EXISTS idx_notification_delivery_task_created ON notification_delivery (notification_task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_notification_delivery_channel_created ON notification_delivery (channel_id, created_at);
```

## 异常和边界

- 图片生成失败不触发 `SHARE_SUMMARY_IMAGE_SUCCESS`。
- 图片重复重新生成成功时，每张成功图片都可以触发一次 `SHARE_SUMMARY_IMAGE_SUCCESS`，因为 `image.id` 不同。
- 通知任务没有关联渠道时不允许保存。
- 通知任务关联的渠道被停用时，该渠道不发送。
- 所有关联渠道都停用时，事件处理不发送请求，但建议记录任务级跳过日志或在后台提示配置无有效渠道。
- 模板渲染失败时，不发送 Webhook，发送记录标记为 `FAILED`。
- Webhook 发送失败不影响分享总结图片状态。
- Webhook 响应体过大时只保存截断摘要。
- Webhook URL 解析失败或命中 SSRF 保护时，保存或发送阶段应明确报错。
- 删除通知渠道时，如果已有任务关联，应禁止删除或自动解除关联；第一版建议禁止删除并提示先解除关联。
- 删除通知任务不删除历史发送记录。

## 验收标准

1. 后台可以创建、编辑、启停 Webhook 通知渠道。
2. 后台可以测试 Webhook 通知渠道。
3. 后台可以创建、编辑、启停通知任务。
4. 通知任务第一版只能选择 `SHARE_SUMMARY_IMAGE_SUCCESS`。
5. 选择事件类型后，模板编辑区只展示该事件支持的占位符。
6. 模板保存时，服务端会校验占位符是否属于当前事件类型。
7. 使用不存在或不属于当前事件类型的占位符时，保存失败并返回具体占位符。
8. 通知任务可以关联多个启用渠道。
9. 分享总结 AI 图片生成成功后，会触发匹配的通知任务。
10. Webhook 请求体能正确渲染 `ogPageUrl`、`ogImageUrl`、`ogTitle`、`ogDescription` 等图片成功事件字段。
11. 单个渠道发送失败不影响其他渠道发送。
12. 通知发送失败不影响分享总结图片的 `SUCCESS` 状态。
13. 每次发送都会记录发送状态、HTTP 状态码、耗时、错误信息和请求体摘要。
14. 后台可以查看通知发送记录并筛选失败记录。
15. 重新生成分享图并成功后，会基于新的 `image.id` 再次触发 `SHARE_SUMMARY_IMAGE_SUCCESS`。
