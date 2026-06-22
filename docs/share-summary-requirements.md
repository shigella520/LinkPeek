# 分享总结现状说明

本文说明当前代码中的分享总结实现。文件名保留为 `share-summary-requirements.md`，用于兼容既有链接。

分享总结模块按任务配置读取 SQLite 中的分享记录标题，调用后台 AI Provider 生成报告，并在成功后触发 AI 分享图和 TTS 音频的自动生成检查。

## 目标与边界

当前实现支持：

- 创建、编辑、启停、逻辑删除分享总结任务。
- 每日、每周、每月任务。
- `CURRENT` / `PREVIOUS` 两种周期选择模式。
- 定时执行和手动执行。
- 按数据库中的 `PREVIEW_CREATED` 记录生成总结，不依赖 Meta 缓存。
- 按 `max_links` 限制 AI 输入，按 `min_links` 控制最小触发数量。
- 记录执行窗口、状态、链接数量、AI Provider、AI 耗时、报告和错误信息。
- 成功报告自动触发 AI 分享图和 TTS 音频生成检查。

当前实现不支持：

- 手动指定任意执行窗口。
- Cron 表达式。
- 不同任务之间的去重。
- 报告执行中的人工暂停或取消。

## 核心概念

### 分享总结任务

分享总结任务是一条后台配置。任务字段对应 `share_summary_task`：

| 字段 | 说明 |
| --- | --- |
| `name` | 任务名称。 |
| `enabled` | 是否启用定时执行。 |
| `period_type` | `DAILY`、`WEEKLY`、`MONTHLY`。 |
| `period_selection_mode` | `CURRENT` 或 `PREVIOUS`。 |
| `run_time` | `HH:mm`。 |
| `day_of_week` | 周任务使用，`1..7` 表示周一到周日。 |
| `prompt` | 分享总结提示词。 |
| `max_links` | 输入 AI 的最大去重链接数，允许 `1..2000`，默认 `100`。 |
| `min_links` | 触发 AI 总结的最小去重链接数，允许 `1..2000`，默认 `1`。 |
| `deleted` / `deleted_at` | 逻辑删除标记和删除时间。 |

删除任务是逻辑删除。任务不再出现在启用扫描中，历史执行记录保留。

### 执行窗口

执行窗口采用左闭右开：

```text
window_start <= occurred_at < window_end
```

窗口由周期类型、周期选择模式、触发时间和系统时区共同决定。

`PREVIOUS` 表示上一完整周期：

| 周期 | 触发点 | 窗口 |
| --- | --- | --- |
| `DAILY` | 每天 `run_time` | 上一个自然日 |
| `WEEKLY` | 指定周几 `run_time` | 上一个自然周，周一到周日 |
| `MONTHLY` | 每月 1 日 `run_time` | 上一个自然月 |

`CURRENT` 表示当前周期截至触发点：

| 周期 | 触发点 | 窗口 |
| --- | --- | --- |
| `DAILY` | 每天 `run_time` | 当日 00:00 到触发时间 |
| `WEEKLY` | 指定周几 `run_time` | 本周周一 00:00 到触发时间 |
| `MONTHLY` | 月末 `run_time` | 当月 1 日 00:00 到月末触发时间 |

手动执行使用当前时间作为触发点，并按任务的 `period_selection_mode` 计算窗口。请求体中传入窗口覆盖会返回 400。

### 执行记录

执行记录对应 `share_summary_run`，保存任务快照、窗口、状态、输入规模、AI Provider、报告和错误信息。

状态：

| 状态 | 说明 |
| --- | --- |
| `RUNNING` | 已创建记录，正在查询数据或调用 AI。 |
| `SUCCESS` | AI 总结成功并保存报告。 |
| `EMPTY` | 没有可总结标题，或去重标题数量低于 `min_links`。 |
| `FAILED` | 查询、组装或 AI 调用失败。 |
| `SKIPPED` | 状态枚举保留，当前主要由代码逻辑跳过重复窗口，不主动落库。 |

长时间未完成的 `RUNNING` 记录会在后续调度扫描中被标记为失败。

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

输入 AI 的链接字段主要包括：

| 字段 | 来源 | 用途 |
| --- | --- | --- |
| `first_occurred_at` | `stats_event.occurred_at` | 分享时间展示和同频排序。 |
| `preview_key` | `stats_event.preview_key` | 去重。 |
| `canonical_url` | `stats_link.canonical_url` | 报告输入中的链接。 |
| `provider_id` | `stats_event.provider_id` | 来源平台统计和排查。 |
| `title` | `stats_link.title` | AI 总结输入。 |
| `occurrence_count` | 聚合结果 | 高频链接优先。 |

标题集合规则：

- 标题为空的记录跳过。
- 按 `preview_key` 去重。
- 同一个 `preview_key` 在窗口内出现多次时，保留出现次数。
- 排序按出现次数倒序，再按首次出现时间升序。
- 去重数量超过 `max_links` 时截断输入。

## AI 总结请求

分享总结复用后台 AI Provider：

- 只使用启用的 Provider。
- 按排序依次 fallback。
- 每次尝试会记录 Provider 名称。
- 成功时记录 AI Provider 成功。
- 失败、空报告或异常会记录 AI Provider 失败，并可能触发自动降级。
- 分享总结请求超时 = Provider 超时 * `share_summary_timeout_multiplier`，并有上限保护。

Prompt 由三部分组成：

1. 系统级分享总结说明。
2. 任务配置中的 `prompt`。
3. 窗口和链接标题列表。

链接列表会包含标题、canonical URL 和分享时间。

## 定时执行

调度扫描逻辑：

1. 标记超时未完成的总结、图片和音频任务为失败。
2. 查询启用且未删除的分享总结任务。
3. 计算当前时间之前已经到期的窗口。
4. 从最近完成的定时窗口之后开始补跑。
5. 最多补跑最近 `7` 个窗口，避免长时间停机后一次触发大量 AI 请求。
6. 同一任务同一窗口已有 `SUCCESS`、`EMPTY` 或 `RUNNING` 定时记录时跳过。

幂等依据是：

```text
task_id + window_start + window_end + trigger_type=SCHEDULED
```

当前代码通过查询已有记录实现应用层幂等，不依赖数据库唯一约束。

## 手动执行

后台接口：

```text
POST /api/admin/share-summary/tasks/{taskId}/run
```

规则：

- 使用所选任务当前配置。
- `trigger_type = MANUAL`。
- 可重复执行同一窗口。
- 不推进定时任务的最近完成窗口。
- 请求体必须为空；传入手动窗口覆盖会返回 400。

## 分享资产触发

当分享总结执行记录最终保存为 `SUCCESS` 后：

- 如果 AI 生图服务存在且配置启用，会调用图片自动生成检查。
- 如果 TTS 音频服务存在且配置启用，会调用音频自动生成检查。
- 图片或音频失败不会回滚分享总结报告状态。

分享资产状态摘要会回填到后台执行记录响应中，包括图片状态、图片 URL、OG URL、分享页 URL、音频状态、音频 URL 和错误信息。

## 后台 API

所有接口复用后台登录鉴权。

任务：

```text
GET    /api/admin/share-summary/tasks
POST   /api/admin/share-summary/tasks
PUT    /api/admin/share-summary/tasks/{taskId}
DELETE /api/admin/share-summary/tasks/{taskId}
POST   /api/admin/share-summary/tasks/{taskId}/run
```

执行记录：

```text
GET    /api/admin/share-summary/runs
GET    /api/admin/share-summary/runs/{runId}
DELETE /api/admin/share-summary/runs/{runId}
```

图片和音频接口见对应现状说明及后台控制器。

## 异常和边界

- 窗口内没有链接创建记录：保存 `EMPTY`。
- 窗口内记录都没有标题：保存 `EMPTY`。
- 去重标题数低于 `min_links`：保存 `EMPTY`。
- AI Provider 未配置或全部禁用：保存 `FAILED`。
- AI Provider 全部失败或返回空报告：保存 `FAILED`。
- 标题数量超过 `max_links`：截断输入，但记录原始数量、去重数量和实际输入数量。
- Prompt 变更后，历史记录使用 `prompt_snapshot` 保持可追溯。
- 任务停用后不再定时执行，历史记录保留。
- 删除任务后不再定时执行，历史记录保留。
- 删除执行记录时会同步删除关联图片记录、音频记录和对应文件。
- 服务停机期间错过执行时间：启动后按窗口顺序有限补跑。
