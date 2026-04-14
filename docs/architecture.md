# 架构说明

## 运行形态

LinkPeek 以单个 Spring Boot 应用的形式运行，但内部通过多个 Maven 模块组合完成。

```text
client -> /preview?url=... -> server controller -> provider registry -> provider
                                      |                 |
                                      |                 -> canonical URL + metadata
                                      |
                                      -> disk cache -> metadata/thumb files
```

## 模块边界

- `linkpeek-core`：定义所有模块共享的核心类型与契约。
- `linkpeek-provider-bilibili`：提供 Bilibili 的具体 `PreviewProvider` 实现。
- `linkpeek-provider-template`：提供新增 provider 的最小骨架示例。
- `linkpeek-server`：负责 HTTP 路由、运行时配置、缓存、日志和 HTML 渲染。

## 请求流程

1. `/preview?url=...` 先校验输入 URL。
2. provider registry 通过 `supports(URI)` 选择匹配的 provider。
3. provider 将原始链接规范化为 canonical URL。
4. 服务端根据 canonical URL 计算稳定的 `PreviewKey`。
5. 对爬虫请求解析元数据并返回 Open Graph HTML。
6. 对普通浏览器请求直接跳转到原始链接，不触发上游元数据抓取。
7. 缩略图请求基于缓存元数据和 provider 自身的下载逻辑处理。

## 缓存设计

- 元数据缓存：`CACHE_DIR/meta/{previewKey}.json`
- 缩略图缓存：`CACHE_DIR/thumb/{previewKey}.jpg`
- 预留视频缓存：`CACHE_DIR/video/{previewKey}.mp4`

元数据和缩略图都使用 TTL 控制新鲜度；淘汰策略采用基于最后修改时间的近似 LRU。
