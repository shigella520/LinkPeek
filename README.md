# LinkPeek

LinkPeek 是一个面向聊天分享场景的链接预览代理服务。它用 Java 和 Spring Boot 实现，对外提供统一的 `/preview?url=...` 入口，为受支持的第三方链接生成稳定的 Open Graph 预览页，并提供统计看板、管理后台、AI 标题、分享总结、分享资产和 Webhook 通知能力。

![LinkPeek Dashboard 预览](docs/preview-dashboard.png)

[在线体验 Live Demo](https://linkpeek.jianyutan.com/dashboard)

管理密码：`linkpeek`。在 Dashboard 连续按 3 下 `6` 会显示跳转按钮。

[Raycast Script](docs/linkpeek.sh)

[快捷指令 Shortcut](https://www.icloud.com/shortcuts/5cce870e64ff48e0853bd77485191fa7)

[Chrome 扩展配置文档](docs/chrome-extension.md)

## 功能特点

- 统一预览入口：`/preview` 对爬虫返回 Open Graph HTML，对普通浏览器记录打开事件后跳转原始链接。
- 多平台解析：内置 Bilibili、GapHub、V2EX、NGA、LINUX DO，并通过 provider SPI 扩展新平台。
- AI 标题生成：文本卡片支持 Style Prompt、`FREESTYLE`、Title Format Prompt、多 AI Provider fallback、独立超时和失败阈值降级。
- 缓存与预热：磁盘缓存元数据、缩略图和预留视频文件；普通浏览器打开后可异步预热元数据。
- 数据看板：Dashboard 展示创建、打开、失败、热门链接、AI 渲染占比、AI 成功率和内容洞察。
- 管理后台：维护 Prompt、AI Provider、Provider 配置、分享总结、图片/TTS 配置、通知渠道、发送记录、服务日志和预览事件。
- 分享总结：支持每日、每周、每月任务，按数据库中的分享记录生成 AI 报告。
- 分享资产：成功报告可自动或手动生成 AI 分享图、Open Graph 分享页和 TTS 音频。
- Webhook 通知：支持图片成功/失败、音频失败、AI Provider 失败/降级、数据抓取失败等内部事件通知。
- 自动化入口：Raycast Script 和 iOS Shortcut 可以直接生成 LinkPeek 分享链接。

## 核心能力

### 链接预览

`/preview` 先校验 URL，再通过 provider registry 选择内容 provider。provider 负责 URL 支持判定、canonical 化、元数据解析和可选媒体下载。爬虫请求会得到 Open Graph HTML；普通浏览器请求会记录打开事件并跳转原始链接。

支持判定接口 `/api/preview/support?url=...` 只执行快速 URL 形态判断，不访问上游、不写缓存、不记录统计。Raycast 脚本也依赖这个接口，因此新增 provider 后脚本不需要同步更新平台规则。

### AI 标题生成

文本卡片 provider 可以提供 `rawContent`，LinkPeek 会根据后台配置的 Style Prompt 生成更适合分享场景的一行标题。Bilibili 这类真实图片卡片默认保留原图预览，Bilibili 的 AI 标题能力可在 Provider 配置中开关。

- Style Key 保存和匹配都会统一转大写。
- `FREESTYLE` 是系统保留模式，会从已有 Style Prompt 中随机选择一个。
- Title Format Prompt 作为格式要求，Raw Content 作为独立 user message 放在最后。
- AI Provider 按启用状态和排序依次 fallback。
- 每个 AI Provider 有独立请求超时；分享总结请求会按全局倍数放大超时。
- 自动降级开启后，Provider 连续失败达到阈值会被移动到列表最后，并触发通知事件。
- Styled 预览使用 `canonical URL + style + prompt hash` 生成独立 `PreviewKey`，避免不同风格污染缓存。

### 分享总结与分享资产

分享总结任务支持每日、每周、每月。每条任务可以配置周期选择模式、执行时间、Prompt、最大链接数和最小链接数。任务执行时从 SQLite 的分享事件和链接维表读取标题，不依赖 Meta 磁盘缓存。

任务成功后可以自动触发两类分享资产：

- AI 分享图：调用 OpenAI-compatible 图片接口，解析 `data[0].b64_json` 或 `data[0].url`，下载/解码后标准化为 `1200x630`，保存到 LinkPeek 自己的存储路径，并生成公开 `og:image` URL 和报告分享页。
- TTS 音频：调用 OpenAI-compatible 或 MiMo TTS 接口，保存音频文件并生成公开音频 URL。

分享图成功后会触发 `SHARE_SUMMARY_IMAGE_SUCCESS` 事件，可通过 Webhook 自动推送到外部系统，例如 BlueBubbles Server。

### Webhook 通知

通知系统由事件 Schema、通知渠道、通知任务和发送记录组成。通知任务选择事件类型后，只能使用该事件支持的占位符；服务端在保存和发送前都会校验模板。

当前事件包括：

- `SHARE_SUMMARY_IMAGE_SUCCESS`
- `SHARE_SUMMARY_IMAGE_FAILED`
- `SHARE_SUMMARY_AUDIO_FAILED`
- `AI_PROVIDER_REQUEST_FAILED`
- `AI_PROVIDER_AUTO_DOWNGRADED`
- `DATA_CRAWL_REQUEST_FAILED`

Webhook 发送使用异步线程池，默认最多尝试 3 次。请求支持自定义 Header、Body 模板、HMAC-SHA256 签名、SSRF 防护、请求体保存、响应摘要和失败重试。

## 效果预览

![LinkPeek Usage](docs/preview-usage.png)

![LinkPeek Report Image](docs/report-image.png)

## 安装（Docker）

### 方式一：使用 `docker compose`

仓库包含可直接启动的 `docker-compose.yml`：

```bash
docker compose up -d --build
```

默认监听 `8080` 端口。

建议生产环境至少配置：

- `BASE_URL`：服务对外可访问的地址，例如 `https://preview.example.com`
- `TZ`：容器时区，建议设为 `Asia/Shanghai`
- `STATS_ADMIN_PASSWORD`：管理后台登录密码，配置后启用 `/admin`
- `/data` 持久化挂载：保存缓存、SQLite 数据库、分享资产和日志

### 方式二：使用 `docker run`

```bash
docker build -t linkpeek .

docker run --rm \
  -p 8080:8080 \
  -e BASE_URL=https://preview.example.com \
  -e TZ=Asia/Shanghai \
  -e STATS_ADMIN_PASSWORD=change-me \
  -e WEB_ICON_PATH=/data/favicon.svg \
  -e CACHE_DIR=/data/cache \
  -e STATS_DB_PATH=/data/stats/linkpeek.db \
  -e CACHE_MAX_SIZE_GB=10 \
  -e PREVIEW_WARMUP_THREADS=2 \
  -e PREVIEW_WARMUP_QUEUE_CAPACITY=64 \
  -v "$PWD/data:/data" \
  linkpeek
```

### 生产部署建议

- 预览服务建议通过公网 HTTPS 暴露，提高即时通讯软件抓取成功率。
- 建议在前面放 Nginx 做 TLS、访问日志和基础限流。
- `/data` 目录建议整体持久化，避免容器重建后缓存、统计库、分享图片、音频和日志丢失。
- 管理后台、SQLite 文件、日志和 `/data` 挂载目录都应按生产权限保护，因为其中保存 Cookie、Prompt、AI Key 和 Webhook Secret。

## 快速开始

### 启动

```bash
docker compose up -d --build
```

生产部署时，把 `BASE_URL` 配置成服务公网地址，例如 `https://preview.example.com`。

### 生成预览链接

把原始链接 URL 编码后放到 `url` 参数：

```text
https://preview.example.com/preview?url=https%3A%2F%2Fwww.v2ex.com%2Ft%2F1206093
```

文本卡片可以追加 `style` 触发 AI 标题生成：

```text
https://preview.example.com/preview?url=https%3A%2F%2Fwww.v2ex.com%2Ft%2F1206093&style=FREESTYLE
```

常用入口：

| 地址 | 用途 |
| --- | --- |
| `/dashboard` | 统计看板和分享链接生成器 |
| `/admin/login` | 管理后台 |
| `/api/health` | 健康检查 |
| `/api/preview/support?url=...` | 判断当前链接是否支持预览 |
| `/api/preview/styles` | 查询可用 Style Key |
| `/doc.html` | OpenAPI 文档 |
| `/share-summary/reports/{publicToken}` | 分享总结公开报告页 |
| `/share-summary/og-images/{publicToken}.{ext}` | 分享总结公开 OG 图片 |
| `/share-summary/audios/{publicToken}.{ext}` | 分享总结公开音频 |

支持判定示例：

```bash
curl -G --data-urlencode "url=https://www.v2ex.com/t/1206093" \
  https://preview.example.com/api/preview/support
```

模拟抓取器请求：

```bash
curl -A "facebookexternalhit/1.1" \
  "https://preview.example.com/preview?url=https%3A%2F%2Fwww.bilibili.com%2Fvideo%2FBV1xx411c7mD"
```

## 项目结构

```text
LinkPeek/
├── linkpeek-core/
│   └── 通用领域模型、错误模型、URL 规范化、provider SPI
├── linkpeek-provider-bilibili/
│   └── Bilibili URL 识别、短链解析、元数据抓取、缩略图下载
├── linkpeek-provider-gaphub/
│   └── GapHub 主题 URL 识别、HTML 元数据抓取、标题卡片生成
├── linkpeek-provider-linuxdo/
│   └── LINUX DO 主题 URL 识别、HTML 元数据抓取、标题卡片生成
├── linkpeek-provider-nga/
│   └── NGA 帖子 URL 识别、页面抓取、GBK 解码、标题卡片生成
├── linkpeek-provider-v2ex/
│   └── V2EX 话题 URL 识别、canonical 化、元数据抓取、标题卡片生成
├── linkpeek-provider-template/
│   └── provider 开发模板
├── linkpeek-server/
│   └── Spring Boot 服务、路由、缓存、HTML 渲染、管理后台、统计、分享总结、分享资产和通知
├── docs/
│   └── 工程文档、自动化脚本和截图资源
├── .github/workflows/
│   └── CI 和 DEV 镜像发布 workflow
├── Dockerfile
├── docker-compose.yml
├── mvnw
├── mvnw.cmd
└── pom.xml
```

各模块职责：

- `linkpeek-core`：定义 `PreviewProvider`、`PreviewMetadata`、`PreviewKey` 等核心抽象。
- `linkpeek-provider-*`：封装各平台 URL 判断、canonical 化、元数据解析、AI 标题上下文补齐和媒体下载逻辑。
- `linkpeek-provider-template`：提供新增 provider 的最小骨架示例。
- `linkpeek-server`：负责 HTTP 接口、爬虫识别、缓存、统计、Dashboard、管理后台、AI 标题、分享总结、图片/TTS 生成和 Webhook 通知。

## 核心流程

```text
/preview?url=<目标链接>&style=<可选风格>
        |
        v
校验 URL -> provider registry -> canonical URL -> 基础 PreviewKey
        |
        v
解析 style：空值走基础预览；普通 style 转大写匹配 Style Prompt；FREESTYLE 随机选择 Style Prompt
        |
        v
命中 Style Prompt 时生成 styled PreviewKey（canonical URL + style + prompt hash）
        |
        +-------------------------------+
        |                               |
        v                               v
     爬虫请求                         普通浏览器请求
        |                               |
        v                               v
缓存 / 单飞锁 / 抓取元数据              记录打开事件并 302 跳转原始链接
        |                               |
        |                               +--> 本地无元数据时投递有界异步预热
        v
文本卡片 + Style Prompt -> 调用 AI Provider 生成标题
        |
        v
AI Provider 按启用和排序 fallback，失败会记录事件并可触发自动降级
        |
        v
成功则缓存 styled 元数据；失败、空返回或真实图片卡片则回退基础元数据
        |
        v
渲染 Open Graph HTML，记录创建事件、AI 请求/成功、Provider、耗时、style 等统计字段
```

分享总结链路：

```text
定时扫描 / 手动执行
        |
        v
根据任务周期和 period_selection_mode 计算窗口
        |
        v
读取 stats_event + stats_link 中的 PREVIEW_CREATED 标题
        |
        v
达到 min_links 后调用 AI Provider 生成报告
        |
        v
保存 share_summary_run
        |
        +--> 自动生成分享图 -> 公开 OG 图片 / 分享页 -> Webhook 通知
        |
        +--> 自动生成 TTS 音频 -> 公开音频 URL
```

## 配置项

部署级配置通过环境变量提供；Prompt、论坛 Cookie、AI Provider、分享总结、图片/TTS 和通知配置通过 `/admin` 写入 SQLite。

| 变量名 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `8080` | HTTP 服务监听端口 |
| `BASE_URL` | `http://localhost:8080` | 生成公开资源绝对地址时使用的服务基础地址 |
| `TZ` | 系统时区 | 容器时区，影响统计窗口、分享总结窗口和日期展示 |
| `WEB_ICON_PATH` | 空 | 可选网页 favicon 文件路径 |
| `CACHE_DIR` | `/data/cache` | 缓存和分享资产根目录 |
| `STATS_DB_PATH` | `/data/stats/linkpeek.db` | SQLite 数据库路径 |
| `CACHE_TTL_SECONDS` | `86400` | 元数据和缩略图缓存有效期 |
| `CACHE_MAX_SIZE_GB` | `10` | 缓存空间上限 |
| `STATS_RETENTION_DAYS` | `180` | 统计事件保留天数 |
| `STATS_EVENT_DEDUPE_TTL` | `2m` | 统计事件内存去重 TTL |
| `STATS_EVENT_DEDUPE_MAX_ENTRIES` | `10000` | 统计事件去重缓存最大条目数 |
| `STATS_ADMIN_PASSWORD` | 空 | 管理后台登录密码，配置后启用 `/admin` |
| `DOWNLOAD_TIMEOUT` | `120s` | 上游内容和媒体请求超时时间 |
| `VIDEO_MAX_QUALITY` | `480` | 预留视频能力的最高质量 |
| `PREVIEW_WARMUP_ENABLED` | `true` | 是否启用普通浏览器打开后的异步元数据预热 |
| `PREVIEW_WARMUP_THREADS` | `2` | 异步预热线程数 |
| `PREVIEW_WARMUP_QUEUE_CAPACITY` | `64` | 异步预热队列上限 |
| `LOG_LEVEL` | `INFO` | 日志级别 |
| `LOG_FILE_PATH` | `/data/logs/linkpeek.log` | 服务滚动日志路径，也供后台日志页面读取 |
| `LOG_FILE_MAX_SIZE` | `10MB` | 单个滚动日志文件上限 |
| `LOG_FILE_MAX_HISTORY` | `14` | 滚动日志保留文件数量 |
| `LOG_FILE_TOTAL_SIZE_CAP` | `200MB` | 滚动日志总大小上限 |

`crawler-signatures` 当前在配置文件中内置为 `facebookexternalhit`、`Facebot`、`Twitterbot`、`Applebot`。

## 管理后台

访问 `/admin/login` 使用 `STATS_ADMIN_PASSWORD` 登录。后台会签发 HttpOnly Cookie，`GET /api/admin/session` 只用于页面刷新时确认登录状态。

后台主要功能：

- 提示词设置：维护 Title Format Prompt 和 `Style Key -> Style Prompt`。
- AI 服务配置：维护 AI Provider、启用状态、排序、API 类型、模型、effort、超时、连通性测试和自动降级配置。
- Provider 配置：维护 Bilibili AI 标题开关、LinuxDo Cookie 和 NGA 登录态。
- 分享总结：维护任务、查看执行记录、手动执行、删除记录。
- 分享资产：维护 AI 生图配置、TTS 配置，生成/重新生成图片和音频。
- 通知管理：维护 Webhook 渠道、通知任务、事件占位符、发送记录、重试和删除。
- 服务日志和预览事件：查看日志、清理缓存、查看预览事件和清理统计数据。

## 新增 provider

扩展新平台时：

1. 在独立模块中实现 `PreviewProvider`
2. 补齐 `supports()`、`canonicalize()`、`resolve()`
3. 如支持 AI 标题，补齐 `rawContent` 或覆盖 `enrichForAiTitle()` / `supportsAiTitle()`
4. 如支持媒体，覆盖 `downloadThumbnail()` 或 `downloadVideo()`
5. 在 `linkpeek-server` 中注册为 Spring Bean
6. 为 URL 匹配、canonical 化、上游映射和媒体下载补测试

`supports()` 是 `/api/preview/support` 和 Raycast 脚本的唯一支持判定来源，必须只做快速 URL 形态判断。

参考：

- [架构说明](./docs/architecture.md)
- [数据库表结构](./docs/database-schema.md)
- [Provider 开发指南](./docs/provider-development.md)
- [DEV 镜像发布指南](./docs/publish-dev-image-workflow.md)
- [TemplatePreviewProvider](./linkpeek-provider-template/src/main/java/io/github/shigella520/linkpeek/provider/template/TemplatePreviewProvider.java)

## 本地开发

本地构建与测试：

```bash
./mvnw -B verify
```

本地启动服务：

```bash
CACHE_DIR=$PWD/.cache/linkpeek \
STATS_DB_PATH=$PWD/.data/linkpeek/stats.db \
./mvnw -pl linkpeek-server -am spring-boot:run
```

指定端口：

```bash
CACHE_DIR=$PWD/.cache/linkpeek \
STATS_DB_PATH=$PWD/.data/linkpeek/stats.db \
./mvnw -pl linkpeek-server -am spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=8080
```

## 常见问题：`PKIX path building failed`

如果本地日志出现：

```text
javax.net.ssl.SSLHandshakeException: PKIX path building failed
```

这通常不是 LinkPeek 业务逻辑问题，而是 Java 运行时不信任当前 HTTPS 证书链。常见场景：

- 开着公司/校园网代理
- 开着 Clash、Surge、Charles、Fiddler 等 HTTPS 代理或抓包工具
- `curl` 走系统证书，而 Java 17 走独立 truststore

建议按顺序处理：

1. 关闭系统代理、抓包工具或 HTTPS 中间人代理后重试。
2. 如果必须经过代理，把代理根证书导出为 `ca.crt`，导入当前 JDK truststore：

```bash
keytool -importcert \
  -alias local-proxy-ca \
  -file /path/to/ca.crt \
  -keystore "$JAVA_HOME/lib/security/cacerts"
```

默认密码通常是 `changeit`。

3. 如果不想改全局 JDK，可以单独给本次启动指定 truststore：

```bash
CACHE_DIR=$PWD/.cache/linkpeek ./mvnw -pl linkpeek-server -am spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=8080 \
  -Dspring-boot.run.jvmArguments='-Djavax.net.ssl.trustStore=/path/to/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit'
```

4. 导入证书后验证 Java 侧是否恢复：

```bash
curl -A "facebookexternalhit/1.1" \
  "http://localhost:8080/preview?url=https%3A%2F%2Fwww.bilibili.com%2Fvideo%2FBV1McSQBEE71"
```

页面不再返回 `Preview Error` 即说明证书链问题已解决。

## 许可证

本项目使用 [MIT License](./LICENSE)。

这意味着：

- 允许自由使用、修改、分发和商用
- 只需保留原始版权声明和许可证文本
- 适合个人项目、开源项目和商业内部项目使用

## 友情链接

<p align="center">
  <a href="https://linux.do" target="_blank">
    <img src="https://img.shields.io/badge/LINUX-DO-FFB003?style=for-the-badge&logo=linux&logoColor=white" alt="LINUX DO" />
  </a>
</p>
