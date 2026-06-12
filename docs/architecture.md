# 架构说明

## 运行形态

LinkPeek 以单个 Spring Boot 应用的形式运行，但内部通过多个 Maven 模块组合完成。

```text
client -> /preview?url=... -> server controller -> provider registry -> provider
                                      |                 |
                                      |                 -> canonical URL + metadata
                                      |
                                      -> disk cache -> metadata/thumb files
                                      |
                                      -> AI title service -> ordered AI providers
                                      |
                                      -> sqlite stats -> dashboard/api
                                      |
                                      -> bounded warmup executor -> metadata cache + stats link title
```

## 模块边界

- `linkpeek-core`：定义所有模块共享的核心类型与契约。
- `linkpeek-provider-bilibili`：提供 Bilibili 的具体 `PreviewProvider` 实现。
- `linkpeek-provider-gaphub`：提供 GapHub 的具体 `PreviewProvider` 实现。
- `linkpeek-provider-linuxdo`：提供 LINUX DO 的具体 `PreviewProvider` 实现。
- `linkpeek-provider-nga`：提供 NGA 的具体 `PreviewProvider` 实现。
- `linkpeek-provider-v2ex`：提供 V2EX 的具体 `PreviewProvider` 实现。
- `linkpeek-provider-template`：提供新增 provider 的最小骨架示例。
- `linkpeek-server`：负责 HTTP 路由、运行时配置、缓存、日志、SQLite 统计聚合、AI 标题生成和 HTML 渲染。

## 请求流程

支持判定接口 `/api/preview/support?url=...` 只执行第 1、2 步，返回 JSON 布尔结果；它不会 canonicalize、抓取上游、写缓存或记录统计。

1. `/preview?url=...` 先校验输入 URL。
2. provider registry 通过 `supports(URI)` 选择匹配的 provider。
3. provider 将原始链接规范化为 canonical URL。
4. 服务端根据 canonical URL 计算稳定的 `PreviewKey`。
5. 对爬虫请求解析元数据；如果带有 `style` 且命中后台 Style Prompt，则尝试基于文本卡片内容生成 AI 标题。
6. AI 标题会使用 `canonical URL + style + prompt hash` 生成独立的 styled `PreviewKey`，避免不同标题风格共用同一份元数据缓存。
7. AI 请求按后台 AI Provider 列表的启用状态和排序 fallback；单个 Provider 有自己的请求超时。全局失败阈值降级开启后，Provider 连续处理失败达到阈值会被移动到列表最后并写入明显 WARN 日志。
8. AI 生成失败、返回空内容或目标 provider 不支持文本卡片时，回退到基础元数据和原标题。
9. 返回 Open Graph HTML，同时记录创建事件和 AI 请求/成功标记。
10. 对普通浏览器请求立即记录打开事件并跳转到原始链接，不在点击跳转分支同步生成 AI 标题。
11. 如果普通浏览器请求命中的元数据尚未缓存，服务会投递有界后台任务异步预热元数据，用于补齐统计看板中的标题。
12. 异步预热使用固定线程池、有限队列和按 `PreviewKey` 的单飞去重，队列满或重复任务会跳过，不阻塞浏览器跳转。
13. 缩略图请求基于缓存元数据和 provider 自身的下载逻辑处理，并记录缩略图服务事件。
14. 统计看板通过 `/api/stats/dashboard` 聚合 SQLite 中的事件数据，再由 `/dashboard` 页面展示。

## 缓存设计

- 元数据缓存：`CACHE_DIR/meta/{previewKey}.json`
- 缩略图缓存：`CACHE_DIR/thumb/{previewKey}.jpg`
- 预留视频缓存：`CACHE_DIR/video/{previewKey}.mp4`

基础元数据和 AI styled 元数据都写入 `meta` 目录，只是使用不同的 `PreviewKey`。元数据和缩略图都使用 TTL 控制新鲜度；淘汰策略采用基于最后修改时间的近似 LRU。

## 运行配置设计

- 部署级配置来自环境变量，例如 `BASE_URL`、`CACHE_DIR`、`DOWNLOAD_TIMEOUT` 和日志路径。
- 管理后台运行时配置写入 SQLite，包括 Style Prompt、论坛 Cookie、AI Provider 列表和 AI Provider 自动降级配置。
- AI Provider 自动降级的开关与失败阈值是全局配置，保存在通用 `provider_config` 表；每个 Provider 只保存自身连接信息、启用状态、排序和请求超时。
- 详细表结构和逻辑关系见 [数据库表结构](./database-schema.md)。

## 统计设计

- 事件库默认使用 `/data/stats/linkpeek.db`。
- 统计写入直接发生在 Web 控制器和媒体代理分支，不依赖离线日志回放。
- 当前看板展示三层指标：规模总览、转化分析、内容洞察；转化分析内包含 AI 请求数、AI 渲染占比和 AI 成功率。
- 根路径 `/` 固定跳转到 `/dashboard`；轻量探活迁移到 `/api/health`。
