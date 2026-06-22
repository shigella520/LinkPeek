# 架构说明

本文描述当前代码中的运行结构和主要数据流。具体 schema 见 [数据库表结构](./database-schema.md)，provider 扩展契约见 [Provider 开发指南](./provider-development.md)。

## 运行形态

LinkPeek 以单个 Spring Boot 应用运行，内部由多个 Maven 模块组合完成。

```text
client -> /preview?url=... -> server controller -> provider registry -> provider
                                      |                 |
                                      |                 -> canonical URL + metadata
                                      |
                                      -> disk cache -> meta/thumb/video files
                                      |
                                      -> AI title service -> ordered AI providers
                                      |
                                      -> sqlite stats -> dashboard/api/admin
                                      |
                                      -> bounded warmup executor -> metadata cache + stats link title

admin -> share summary task -> AI summary -> share_summary_run
                                      |
                                      +-> image executor -> public OG image/report -> notification event
                                      |
                                      +-> audio executor -> public audio

internal events -> notification service -> webhook delivery records -> external systems
```

## 模块边界

- `linkpeek-core`：定义核心模型、错误类型、URL 规范化工具、爬虫匹配和 `PreviewProvider` SPI。
- `linkpeek-provider-bilibili`：Bilibili URL 识别、短链解析、元数据抓取、缩略图下载；AI 标题可由后台开关控制。
- `linkpeek-provider-gaphub`：GapHub 主题链接解析、HTML 元数据抓取和标题卡片生成。
- `linkpeek-provider-linuxdo`：LINUX DO 主题链接解析、HTML 元数据抓取、Cookie 配置读取和标题卡片生成。
- `linkpeek-provider-nga`：NGA 帖子 URL 识别、登录态配置读取、页面抓取、GBK 解码和标题卡片生成。
- `linkpeek-provider-v2ex`：V2EX 话题页解析、回复锚点归一化、AI 标题上下文补齐和标题卡片生成。
- `linkpeek-provider-template`：新增 provider 的最小骨架示例。
- `linkpeek-server`：HTTP 路由、运行配置、缓存、日志、SQLite、Dashboard、管理后台、AI 标题、分享总结、分享资产和通知投递。

## 预览请求流程

支持判定接口 `/api/preview/support?url=...` 只执行快速 URL 形态判断。它不会 canonicalize、抓取上游、写缓存或记录统计。

`/preview` 请求流程：

1. 校验输入 URL。
2. provider registry 通过 `supports(URI)` 选择匹配的内容 provider。
3. provider 将原始链接规范化为 canonical URL。
4. 服务端根据 canonical URL 计算基础 `PreviewKey`。
5. 解析 `style`：空值走基础预览；普通值转大写匹配后台 Style Prompt；`FREESTYLE` 从已有 Style Prompt 中随机选择。
6. 命中 Style Prompt 时，用 `canonical URL + style + prompt hash` 生成 styled `PreviewKey`。
7. 爬虫请求进入元数据解析、缓存和 HTML 渲染分支。
8. 普通浏览器请求记录打开事件并 302 跳转原始链接，不同步生成 AI 标题。
9. 普通浏览器请求如果发现元数据未缓存，会投递有界异步预热任务。
10. 缩略图和视频请求走 `/media/thumb/{previewKey}.jpg`、`/media/video/{previewKey}.mp4`，由缓存和 provider 下载能力处理。

## AI 标题流程

- AI 标题只在爬虫预览创建分支中尝试。
- 文本卡片 provider 通过 `generated://...` 缩略图和非空 `rawContent` 默认支持 AI 标题。
- provider 可以覆盖 `enrichForAiTitle(...)` 补齐正文，也可以覆盖 `supportsAiTitle(...)` 改变支持规则。
- AI 请求使用后台启用的 AI Provider，按 `sort_order ASC, id ASC` fallback。
- AI 请求成功会记录成功并写入 styled 元数据缓存。
- AI 请求失败、空返回或 provider 不支持 AI 标题时，回退基础元数据和原标题。
- AI Provider 失败会进入失败计数；自动降级开启且达到阈值时，Provider 会被移动到排序列表最后。
- AI Provider 请求失败和自动降级都可以触发通知事件。

## 分享总结流程

分享总结任务由后台维护，调度扫描会定期处理启用且未删除的任务。

1. 根据 `period_type`、`period_selection_mode`、`run_time` 和可选 `day_of_week` 计算应执行窗口。
2. 同一任务同一窗口如果已有 `SUCCESS`、`EMPTY` 或 `RUNNING` 定时记录，则跳过。
3. 查询 `stats_event` + `stats_link` 中窗口内的 `PREVIEW_CREATED` 标题。
4. 按链接去重和频次排序，受 `max_links` 限制。
5. 无标题或去重数量低于 `min_links` 时保存 `EMPTY`。
6. 调用 AI Provider 生成报告，成功后保存 `SUCCESS`。
7. 成功报告触发分享图和音频的自动生成检查。

调度启动时还会把超时未完成的 RUNNING 总结、活跃图片任务和活跃音频任务标记为失败，避免重启后长时间悬挂。

## 分享资产流程

### AI 分享图

- 配置表是单例 `share_summary_image_config`。
- 当前 Provider 类型为 `OPENAI_COMPATIBLE`。
- 生图任务先保存为 `PENDING`，再由单线程 executor 异步执行。
- 上游结果支持 `data[0].b64_json` 和 `data[0].url`。
- URL 图片下载会校验协议、host 和 IP，阻止回环、内网、link-local、multicast 等地址。
- 图片会解码后裁剪/缩放到 `1200x630`，重新编码为 `png` 或 `jpg`。
- 成功后生成公开图片 URL 和报告页 URL。
- 成功触发 `SHARE_SUMMARY_IMAGE_SUCCESS`；失败触发 `SHARE_SUMMARY_IMAGE_FAILED`。

### TTS 音频

- 配置表是单例 `share_summary_audio_config`。
- 支持 OpenAI-compatible 音频接口和 MiMo TTS 适配逻辑。
- 音频任务先保存为 `PENDING`，再由单线程 executor 异步执行。
- 成功后保存音频文件并生成公开音频 URL。
- 失败触发 `SHARE_SUMMARY_AUDIO_FAILED`。

## 通知流程

通知系统由事件 Schema、渠道、任务和发送记录组成。

1. 内部事件产生后，通知服务查询启用且事件类型匹配的通知任务。
2. 对 `SHARE_SUMMARY_IMAGE_SUCCESS` 任务，还会按分享总结任务 ID、周期类型、触发方式做过滤。
3. 服务端根据事件 Schema 校验并渲染任务模板。
4. 渠道 Body 模板只支持 `message.body` 和 `message.bodyJson`。
5. 每个启用渠道创建一条 `notification_delivery` 记录。
6. Webhook 在线程池中发送，失败时按规则最多尝试 3 次。
7. 发送完成后记录状态、HTTP 状态码、响应摘要、错误信息、耗时和尝试次数。

Webhook 发送时固定添加：

- `Content-Type: application/json`
- `User-Agent: LinkPeek-Webhook/1.0`
- `X-LinkPeek-Event`
- `X-LinkPeek-Timestamp`
- 可选 `X-LinkPeek-Signature`

URL 校验会阻止内网和本机地址。自定义 Header 不允许覆盖 `Host`、`Content-Length`、`Connection`、`Transfer-Encoding`。

## 缓存与存储

默认根目录由 `CACHE_DIR` 指定。

```text
{CACHE_DIR}/meta/{previewKey}.json
{CACHE_DIR}/thumb/{previewKey}.jpg
{CACHE_DIR}/video/{previewKey}.mp4
{CACHE_DIR}/share-summary/images/{run_id}/{image_id}.{ext}
{CACHE_DIR}/share-summary/audios/{run_id}/{audio_id}.{ext}
{CACHE_DIR}/share-summary/test-audio.{ext}
```

元数据和缩略图使用 TTL 控制新鲜度；缓存淘汰采用基于最后修改时间的近似 LRU。分享总结图片和音频由对应服务管理，删除执行记录时会同步删除关联图片和音频记录及文件。

## 运行配置

- 部署级配置来自环境变量，例如 `BASE_URL`、`CACHE_DIR`、`DOWNLOAD_TIMEOUT`、日志路径和预热线程数。
- 后台运行配置写入 SQLite，包括 Prompt、论坛 Cookie、AI Provider、AI Provider 自动降级、分享总结任务、图片/TTS 配置和通知配置。
- SQLite 默认使用 WAL、`synchronous=NORMAL`、5 秒 busy timeout，并关闭 foreign key enforcement。
- 当前没有版本化 migration 工具，启动时执行 `stats-schema.sql`，再由 `StatisticsConfiguration` 做幂等列迁移和少量兼容性重建。

## 统计设计

- 事件库默认路径是 `/data/stats/linkpeek.db`。
- 统计写入直接发生在 Web 控制器、媒体代理、预热和错误分支，不依赖离线日志回放。
- 统计事件包含 URL、style、AI Provider、AI 耗时、抓取耗时、缓存命中、客户端类型和错误码。
- `STATS_EVENT_DEDUPE_TTL` 和 `STATS_EVENT_DEDUPE_MAX_ENTRIES` 控制内存去重，减少短时间重复事件。
- Dashboard 通过 `/api/stats/dashboard` 聚合 SQLite，再由 `/dashboard` 页面展示。
- 根路径 `/` 固定跳转到 `/dashboard`；轻量探活是 `/api/health`。
