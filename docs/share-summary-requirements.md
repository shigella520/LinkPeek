# 分享总结需求定义

## 背景

LinkPeek 已经在 SQLite 中持久化链接创建记录和链接标题。分享总结模块用于按固定周期读取一段完整自然窗口内的链接创建记录标题，结合后台配置的总结提示词调用 AI，生成可查询的分享总结报告。

该模块不依赖 Meta 磁盘缓存。标题数据统一来自数据库，避免缓存过期、手动清理或容量淘汰影响历史总结。

## 目标

- 支持创建多条分享总结任务。
- 每条任务可独立配置周期、执行时间、提示词和最大链接数。
- 按每日、每周、每月的完整自然窗口生成总结。
- 定时执行结果持久化到数据库，后台可查询历史记录和报告详情。
- 服务重启后根据历史执行记录继续执行，避免重复窗口，并可有限补跑遗漏窗口。
- 支持手动执行一次，用于测试配置、补跑或临时总结。

## 非目标

- 不做滚动窗口总结，例如“从执行时间往前推 24 小时”。
- 不从 Meta 缓存读取标题。
- 不要求不同任务之间去重。不同任务即使窗口重叠，也各自生成报告。
- 不在第一版支持复杂 Cron 表达式。
- 不在第一版支持报告自动外发到第三方平台。

## 核心概念

### 分享总结任务

分享总结任务是一条可启停的后台配置。每日、每周、每月任务可以同时存在并同时启用。

每条任务独立维护：

- 任务名称
- 启用状态
- 周期类型
- 执行时间
- 每周执行日或每月执行日
- 分享总结提示词
- 最大链接数

### 执行窗口

执行窗口是一次总结覆盖的数据范围。窗口采用左闭右开：

```text
window_start <= occurred_at < window_end
```

窗口只由周期类型决定，执行时间只决定什么时候生成报告，不改变总结范围。

### 执行记录

执行记录是任务的一次运行结果，包含窗口范围、状态、输入链接数量、AI Provider、总结报告、错误信息和提示词快照。

## 任务配置

### 配置字段

| 字段 | 说明 | 约束 |
| --- | --- | --- |
| `name` | 任务名称 | 必填，后台展示用 |
| `enabled` | 是否启用定时执行 | 布尔值 |
| `period_type` | 周期类型 | `DAILY` / `WEEKLY` / `MONTHLY` |
| `run_time` | 执行时间 | `HH:mm`，例如 `09:00` |
| `day_of_week` | 每周执行日 | 仅 `WEEKLY` 使用，建议 `1..7` 表示周一到周日 |
| `day_of_month` | 每月执行日 | 仅 `MONTHLY` 使用，建议 `1..28` |
| `prompt` | 分享总结提示词 | 必填，执行时保存快照 |
| `max_links` | 最大链接数 | 默认 `100`，用于限制 AI 输入大小 |
| `updated_at` | 最近更新时间 | epoch milliseconds |

### 每日任务

配置示例：

```text
周期类型：每日
执行时间：09:00
```

语义：

- 每天 `09:00` 执行。
- 总结上一个完整自然日。
- 例如 `2026-05-30 09:00` 执行时，窗口为：

```text
2026-05-29 00:00:00 <= occurred_at < 2026-05-30 00:00:00
```

### 每周任务

配置示例：

```text
周期类型：每周
周几：周一
执行时间：09:00
```

语义：

- 每周指定星期几的指定时间执行。
- 总结上一个完整自然周。
- 自然周固定为周一到周日。
- 周几只决定报告生成时间，不改变总结窗口。
- 例如 `2026-06-01 周一 09:00` 执行时，窗口为：

```text
2026-05-25 00:00:00 <= occurred_at < 2026-06-01 00:00:00
```

如果配置为周三执行，也仍然总结上一个完整自然周，而不是从周三到周三。

### 每月任务

配置示例：

```text
周期类型：每月
几号：1
执行时间：09:00
```

语义：

- 每月指定日期的指定时间执行。
- 总结上一个完整自然月。
- 几号只决定报告生成时间，不改变总结窗口。
- 例如 `2026-06-01 09:00` 执行时，窗口为：

```text
2026-05-01 00:00:00 <= occurred_at < 2026-06-01 00:00:00
```

建议第一版将 `day_of_month` 限制为 `1..28`，避免 29、30、31 号在部分月份不存在时产生歧义。

## 数据来源

分享总结只读取数据库中的链接创建记录。

查询来源：

- `stats_event`
- `stats_link`

查询条件：

- `stats_event.event_type = 'PREVIEW_CREATED'`
- `stats_event.occurred_at` 落在执行窗口内
- `stats_event.preview_key = stats_link.preview_key`
- `stats_link.title` 非空

建议提取字段：

| 字段 | 来源 | 用途 |
| --- | --- | --- |
| `occurred_at` | `stats_event.occurred_at` | 排序和窗口校验 |
| `preview_key` | `stats_event.preview_key` | 去重和关联 |
| `source_url` | `stats_event.source_url` | 报告详情和排查 |
| `canonical_url` | `stats_link.canonical_url` | 报告详情和排查 |
| `provider_id` | `stats_event.provider_id` | 来源平台统计 |
| `title` | `stats_link.title` | AI 总结输入 |
| `requested_style` | `stats_event.requested_style` | AI 标题来源排查 |
| `actual_style` | `stats_event.actual_style` | AI 标题来源排查 |
| `ai_requested` | `stats_event.ai_requested` | 统计和排查 |
| `ai_succeeded` | `stats_event.ai_succeeded` | 统计和排查 |

## 标题集合规则

- 标题为空的记录跳过。
- 默认按 `preview_key` 去重。
- 同一个 `preview_key` 在窗口内出现多次时，保留出现次数。
- 去重后的标题数量超过 `max_links` 时截断。
- 排序建议：
  - 出现次数倒序。
  - 首次出现时间升序。

这样可以让高频链接更靠前，同时保持同频链接的时间顺序稳定。

## AI 总结请求

### Prompt 组装

AI 请求输入由任务提示词和窗口内标题集合组成。

建议结构：

```text
分享总结提示词：
{task.prompt}

总结窗口：
{window_start} ~ {window_end}

链接标题列表：
1. [3次] 标题 A
2. [2次] 标题 B
3. [1次] 标题 C
```

### AI Provider

分享总结复用现有后台 AI Provider 配置：

- 按启用状态和排序依次尝试。
- 记录实际尝试或成功的 Provider 名称。
- 记录 AI 调用耗时。
- AI Provider 全部失败时，执行记录标记为 `FAILED`。

第一版可以复用现有 AI 请求客户端和超时配置，不单独为分享总结配置 AI Provider。

## 定时执行

### 调度原则

- 定时任务按任务配置计算应执行窗口。
- 同一任务的同一窗口只允许成功生成一次定时总结。
- 不使用“当前时间往前推 N 小时/天”的滚动窗口。
- 执行时间只决定触发时间，窗口始终是上一个完整自然日、自然周或自然月。

### 服务重启恢复

服务启动后：

1. 加载启用的分享总结任务。
2. 根据任务周期计算当前时间之前已经应该执行的窗口。
3. 查询该任务最近成功执行的窗口。
4. 从最近成功窗口之后开始，按时间顺序补跑遗漏窗口。
5. 默认最多补跑最近 `7` 个窗口，避免长时间停机后一次性触发大量 AI 请求。

补跑上限第一版可以写死，不作为后台配置项。

### 幂等规则

定时执行记录应以任务和窗口作为幂等依据：

```text
task_id + window_start + window_end + trigger_type=SCHEDULED
```

同一任务、同一窗口的定时执行只保留一条成功记录。手动执行允许重复，但必须标记为 `MANUAL`。

## 手动执行

后台支持从任务列表手动执行一次。

建议支持以下方式：

- 点击某个任务的执行按钮，执行该任务最近一个完整窗口。

手动执行语义：

- 使用所选任务的提示词和最大链接数。
- 执行记录 `trigger_type = MANUAL`。
- 可以重复执行同一个窗口。
- 手动执行不影响定时任务的最近成功窗口推进。

## 执行状态

建议状态：

| 状态 | 说明 |
| --- | --- |
| `RUNNING` | 已创建执行记录，正在查询数据或调用 AI |
| `SUCCESS` | AI 总结成功并保存报告 |
| `EMPTY` | 窗口内没有可总结标题，不调用 AI |
| `FAILED` | 查询、组装或 AI 调用失败 |
| `SKIPPED` | 因补跑上限或幂等规则跳过 |

RUNNING 记录如果长时间未完成，应允许后续任务将其标记为 `FAILED` 或重新执行同一窗口。第一版可使用固定超时时间，例如 30 分钟。

## 数据库设计建议

### share_summary_task

分享总结任务配置表。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `INTEGER` | 主键 |
| `name` | `TEXT` | 任务名称 |
| `enabled` | `INTEGER` | 是否启用 |
| `period_type` | `TEXT` | `DAILY` / `WEEKLY` / `MONTHLY` |
| `run_time` | `TEXT` | `HH:mm` |
| `day_of_week` | `INTEGER` | 每周执行日，仅周任务使用 |
| `day_of_month` | `INTEGER` | 每月执行日，仅月任务使用 |
| `prompt` | `TEXT` | 分享总结提示词 |
| `max_links` | `INTEGER` | 最大标题数量 |
| `created_at` | `INTEGER` | 创建时间 |
| `updated_at` | `INTEGER` | 更新时间 |

### share_summary_run

分享总结执行记录表。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `INTEGER` | 主键 |
| `task_id` | `INTEGER` | 任务 ID |
| `task_name` | `TEXT` | 执行时任务名称快照 |
| `trigger_type` | `TEXT` | `SCHEDULED` / `MANUAL` / `RETRY` |
| `period_type` | `TEXT` | 执行时周期类型快照 |
| `window_start` | `INTEGER` | 窗口开始，epoch milliseconds |
| `window_end` | `INTEGER` | 窗口结束，epoch milliseconds |
| `status` | `TEXT` | 执行状态 |
| `link_count` | `INTEGER` | 原始链接创建记录数 |
| `unique_link_count` | `INTEGER` | 去重后标题数 |
| `input_link_count` | `INTEGER` | 实际输入 AI 的标题数 |
| `prompt_snapshot` | `TEXT` | 执行时提示词快照 |
| `ai_provider_names` | `TEXT` | 实际调用的 AI Provider 名称 |
| `ai_duration_ms` | `INTEGER` | AI 调用耗时 |
| `report` | `TEXT` | 总结报告正文 |
| `error_message` | `TEXT` | 失败原因 |
| `started_at` | `INTEGER` | 执行开始时间 |
| `finished_at` | `INTEGER` | 执行结束时间 |

建议索引：

- `idx_share_summary_run_task_window(task_id, window_start, window_end)`
- `idx_share_summary_run_started_at(started_at)`
- `idx_share_summary_run_status(status)`

建议唯一约束：

```text
task_id + window_start + window_end + trigger_type
```

如需允许手动重复执行，可只对 `trigger_type = SCHEDULED` 做应用层幂等控制，SQLite 第一版可以不强依赖部分唯一索引。

## 后台页面

新增管理模块：“分享总结”。

### 任务配置区

展示任务列表，支持：

- 新建任务。
- 编辑任务。
- 启用/停用任务。
- 删除任务或归档任务。

任务表单字段：

- 任务名称
- 是否启用
- 周期类型
- 执行时间
- 周几，周期为每周时显示
- 几号，周期为每月时显示
- 最大链接数
- 分享总结提示词

### 历史记录区

分页展示：

- 执行时间
- 任务名称
- 触发方式
- 窗口范围
- 状态
- 原始记录数
- 去重标题数
- AI Provider
- 耗时
- 报告摘要

支持展开或进入详情查看：

- 完整报告
- 提示词快照
- 错误信息
- 输入标题数量统计

## API 建议

后台 API 可按以下资源划分：

```text
GET    /api/admin/share-summary/tasks
POST   /api/admin/share-summary/tasks
PUT    /api/admin/share-summary/tasks/{taskId}
DELETE /api/admin/share-summary/tasks/{taskId}

POST   /api/admin/share-summary/tasks/{taskId}/run

GET    /api/admin/share-summary/runs
GET    /api/admin/share-summary/runs/{runId}
```

所有接口复用现有后台登录鉴权。

## 异常和边界

- 窗口内没有链接创建记录：保存 `EMPTY`，不调用 AI。
- 窗口内有记录但标题都为空：保存 `EMPTY`，不调用 AI。
- AI Provider 未配置或全部禁用：保存 `FAILED`。
- AI 请求失败：保存 `FAILED`，记录错误信息。
- 标题数量超过 `max_links`：截断输入，但记录原始数量、去重数量和实际输入数量。
- 提示词变更：历史记录使用 `prompt_snapshot` 保持可追溯。
- 任务停用：不再定时执行，但历史记录保留。
- 任务删除：建议第一版做逻辑归档，避免历史记录失去任务上下文。
- 服务停机期间错过执行时间：启动后按窗口顺序有限补跑。
- 同一窗口已有成功定时记录：跳过，不重复生成。

## 验收标准

1. 后台可以创建、编辑、启用、停用分享总结任务。
2. 任务支持每日、每周、每月三种周期。
3. 每日任务总结上一个完整自然日。
4. 每周任务总结上一个完整自然周，周一到周日。
5. 每月任务总结上一个完整自然月。
6. 多条任务可以同时启用并独立执行。
7. 总结数据只从数据库读取，不依赖 Meta 缓存。
8. AI 总结成功后保存报告和执行记录。
9. 无数据、AI 失败、任务跳过都有明确执行状态。
10. 服务重启后不会重复执行已成功窗口，并能有限补跑遗漏窗口。
11. 后台可以分页查询历史总结记录并查看报告详情。
