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
                                      -> sqlite stats -> dashboard/api
                                      |
                                      -> bounded warmup executor -> metadata cache + stats link title
```

## 模块边界

- `linkpeek-core`：定义所有模块共享的核心类型与契约。
- `linkpeek-provider-bilibili`：提供 Bilibili 的具体 `PreviewProvider` 实现。
- `linkpeek-provider-linuxdo`：提供 LINUX DO 的具体 `PreviewProvider` 实现。
- `linkpeek-provider-nga`：提供 NGA 的具体 `PreviewProvider` 实现。
- `linkpeek-provider-v2ex`：提供 V2EX 的具体 `PreviewProvider` 实现。
- `linkpeek-provider-template`：提供新增 provider 的最小骨架示例。
- `linkpeek-server`：负责 HTTP 路由、运行时配置、缓存、日志、SQLite 统计聚合和 HTML 渲染。

## 请求流程

1. `/preview?url=...` 先校验输入 URL。
2. provider registry 通过 `supports(URI)` 选择匹配的 provider。
3. provider 将原始链接规范化为 canonical URL。
4. 服务端根据 canonical URL 计算稳定的 `PreviewKey`。
5. 对爬虫请求解析元数据并返回 Open Graph HTML，同时记录创建事件。
6. 对普通浏览器请求立即记录打开事件并跳转到原始链接。
7. 如果普通浏览器请求命中的元数据尚未缓存，服务会投递有界后台任务异步预热元数据，用于补齐统计看板中的标题。
8. 异步预热使用固定线程池、有限队列和按 `PreviewKey` 的单飞去重，队列满或重复任务会跳过，不阻塞浏览器跳转。
9. 缩略图请求基于缓存元数据和 provider 自身的下载逻辑处理，并记录缩略图服务事件。
10. 统计看板通过 `/api/stats/dashboard` 聚合 SQLite 中的事件数据，再由 `/dashboard` 页面展示。

## 缓存设计

- 元数据缓存：`CACHE_DIR/meta/{previewKey}.json`
- 缩略图缓存：`CACHE_DIR/thumb/{previewKey}.jpg`
- 预留视频缓存：`CACHE_DIR/video/{previewKey}.mp4`

元数据和缩略图都使用 TTL 控制新鲜度；淘汰策略采用基于最后修改时间的近似 LRU。

## 统计设计

- 事件库默认使用 `/data/stats/linkpeek.db`。
- 统计写入直接发生在 Web 控制器和媒体代理分支，不依赖离线日志回放。
- 当前看板只展示三层指标：规模总览、转化分析、内容洞察。
- 根路径 `/` 固定跳转到 `/dashboard`；轻量探活迁移到 `/api/health`。
