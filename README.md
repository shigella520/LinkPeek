# LinkPeek

一个使用 Java 编写的链接预览代理服务，面向 iMessage 一类聊天分享场景，为受支持的第三方链接生成稳定的 Open Graph 预览页。

采用 `Spring Boot 3.x + Maven` 多模块结构，对外统一暴露 `GET /preview?url=...` 入口，内部通过 provider SPI 解析目标链接并输出预览 HTML。

[在线体验 Live Demo](https://linkpeek.jianyutan.com/dashboard)

![LinkPeek Dashboard 预览](docs/preview.png)

[Raycast Script](docs/linkpeek.sh)

[快捷指令 Shortcut](https://www.icloud.com/shortcuts/178990c09c624dd3b45e88eec90e8a9a)

![快捷指令使用指南](docs/快捷指令使用指南.png)

## 功能特点

- 提供 `Bilibili` provider，支持标准视频链接和 `b23.tv` 短链。
- 提供 `V2EX` provider，支持标准话题链接和带 `#reply` 锚点的话题链接，并为话题统一生成渐变标题卡片缩略图。
- 提供 `NGA` provider，支持 `read.php?tid=...` 帖子链接，抓取标题与首楼摘要并生成预览卡片。
- 提供 `LINUX DO` provider，支持公开主题链接，抓取主题 HTML 元数据并生成标题卡片。
- 对爬虫请求返回 Open Graph HTML，对普通浏览器请求执行 `302` 跳转回原始链接。
- 提供轻量支持判定接口，Raycast 脚本通过云端 provider registry 判断链接是否支持，新增 provider 后无需同步脚本规则。
- 提供本地磁盘缓存，缓存元数据和缩略图，减少重复抓取。
- 提供内部缩略图代理路由，`og:image` 指向服务自身地址，便于统一控制。
- 保留视频代理路由占位，但目前明确返回 `501 Not Implemented`，不引入外部二进制依赖。
- 内置 `SQLite + MyBatis` 统计子系统，自动采集创建、打开、失败和缩略图命中事件。
- 普通浏览器打开会立即跳转，并通过有界后台任务异步补齐统计标题。
- 自带统计页，适合自部署后直接查看运营数据。
- 使用多模块组织方式，便于后续扩展更多 provider。

## 安装（Docker）

### 方式一：使用 `docker compose`

仓库已包含一个可直接启动的 `docker-compose.yml`：

```bash
docker compose up -d --build
```

默认监听 `8080` 端口。

建议启动前至少配置：

- `BASE_URL`：服务对外可访问的地址，例如 `https://preview.example.com`
- `WEB_ICON_PATH`：可选的网页 favicon 文件路径，例如 `/data/favicon.svg` 或 `/data/favicon.ico`
- `CACHE_DIR`：缓存目录，默认 `/data/cache`
- `STATS_DB_PATH`：统计数据库路径，默认 `/data/stats/linkpeek.db`
- `CACHE_MAX_SIZE_GB`：缓存空间上限，默认 `10`
- `PREVIEW_WARMUP_THREADS`：普通浏览器打开后的异步标题预热线程数，默认 `2`
- `PREVIEW_WARMUP_QUEUE_CAPACITY`：异步标题预热队列上限，默认 `64`
- 将整个 `/data` 做持久化挂载，统一保存缓存和统计库文件

### 方式二：使用 `docker run`

```bash
docker build -t linkpeek .

docker run --rm \
  -p 8080:8080 \
  -e BASE_URL=https://preview.example.com \
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

- 预览服务建议通过公网 `HTTPS` 暴露，提高即时通讯软件爬取成功率。
- 建议在前面放 `Nginx`  做 TLS、访问日志和基础限流。
- `/data` 目录建议整体持久化挂载，避免容器重建后缓存和统计数据全部丢失。

## 快速开始 / 使用示例

### 1. 启动服务

```bash
docker compose up -d --build
```

### 2. 准备一个对外可访问的域名

例如：

```text
https://preview.example.com
```

并将其配置给环境变量 `BASE_URL`。

### 3. 分享预览链接

统一使用这个入口，把原始链接做 URL 编码后放进 `url` 参数即可，例如：

```text
https://preview.example.com/preview?url=https%3A%2F%2Fwww.v2ex.com%2Ft%2F1206093
```

行为说明：

- 当 iMessage 或其他爬虫访问该链接时，服务返回 Open Graph HTML。
- 当普通用户点击同一个链接时，服务会 `302` 跳转到原始链接。
- Raycast 脚本会先调用云端支持判定接口，只有当前服务端 provider 支持该链接时才复制预览链接。

如果只需要判断一个链接当前是否支持预览，可以调用：

```bash
curl -G --data-urlencode "url=https://www.v2ex.com/t/1206093" \
  https://preview.example.com/api/preview/support
```

支持时返回：

```json
{"supported":true}
```

合法但没有 provider 支持时返回：

```json
{"supported":false}
```

### 4. 本地验证抓取结果

模拟抓取器请求：

```bash
curl -A "facebookexternalhit/1.1" \
  "https://preview.example.com/preview?url=https%3A%2F%2Fwww.bilibili.com%2Fvideo%2FBV1xx411c7mD"
```

检查服务健康状态：

```bash
curl https://preview.example.com/api/health
```

返回：

```json
{"status":"ok"}
```

获取网页 favicon：

```bash
curl -I https://preview.example.com/favicon.ico
```

打开统计看板：

```text
https://preview.example.com/dashboard
```

查看 OpenAPI 文档：

```text
https://preview.example.com/doc.html
```

原始 OpenAPI JSON：

```text
https://preview.example.com/v3/api-docs
```

## 项目结构

```text
LinkPeek/
├── linkpeek-core/
│   └── 通用领域模型、错误模型、URL 规范化、provider SPI
├── linkpeek-provider-bilibili/
│   └── Bilibili URL 识别、短链解析、元数据抓取、缩略图下载
├── linkpeek-provider-linuxdo/
│   └── LINUX DO 主题 URL 识别、HTML 元数据抓取、标题卡片生成
├── linkpeek-provider-nga/
│   └── NGA 帖子 URL 识别、HTML 抓取、GBK 解码、标题卡片生成
├── linkpeek-provider-v2ex/
│   └── V2EX 话题 URL 识别、canonical 化、元数据抓取、缩略图下载
├── linkpeek-provider-template/
│   └── provider 开发模板
├── linkpeek-server/
│   └── Spring Boot 服务、路由、缓存、HTML 渲染、配置装配
├── docs/
│   ├── architecture.md
│   ├── linkpeek.sh
│   └── provider-development.md
├── .github/workflows/ci.yml
├── Dockerfile
├── docker-compose.yml
├── mvnw
├── mvnw.cmd
└── pom.xml
```

各模块职责：

- `linkpeek-core`：定义 `PreviewProvider`、`PreviewMetadata`、`PreviewKey` 等核心抽象。
- `linkpeek-provider-bilibili`：封装 Bilibili 平台相关逻辑，不把平台细节泄漏到 Web 层。
- `linkpeek-provider-linuxdo`：封装 LINUX DO 主题链接解析、HTML 元数据抓取和缩略图生成逻辑。
- `linkpeek-provider-nga`：封装 NGA 帖子 URL 识别、页面抓取、首楼摘要提取和缩略图生成逻辑。
- `linkpeek-provider-v2ex`：封装 V2EX 话题页解析、回复锚点归一化和缩略图下载逻辑。
- `linkpeek-provider-template`：提供新增 provider 的最小骨架示例。
- `linkpeek-server`：负责 HTTP 接口、爬虫识别、缓存、OG HTML 输出、SQLite 统计和 Dashboard 页面。

## 核心逻辑 / 关键流程

### 整体流程

```text
用户分享 /preview?url=<目标链接>
              |
              v
        服务校验并规范化 URL
              |
              v
        provider registry 选择 provider
              |
              v
  Bilibili 短链则先解析重定向为标准视频链接
              |
              v
      根据 canonical URL 生成 PreviewKey
              |
      +-------+-------+
      |               |
      v               v
  爬虫请求          普通浏览器请求
      |               |
      v               v
 查缓存 / 抓元数据      记录打开事件
      |               |
      |               +--> 立即返回 302 跳转原始链接
      |               |
      |               +--> 本地无元数据时投递有界异步预热
      |                    |
      |                    v
      |              后台抓标题并更新统计维表
      v
 渲染 Open Graph HTML + 记录统计事件
      |
      v
 缩略图通过 /media/thumb/{previewKey}.jpg 按需下载与缓存
```

普通浏览器请求记录打开事件后会立即返回 `302` 跳转；如果本地还没有元数据，
服务会投递一个有界后台任务异步抓取标题并更新统计维表。异步预热使用固定线程池、
有限队列和按 `PreviewKey` 的单飞去重，避免高并发打开时重复抓取或占满资源。

### 当前版本设计原则

- 对外只保留一个统一入口，避免平台路由继续膨胀。
- provider 负责平台识别、canonical 化和元数据解析。
- 服务层负责缓存、路由控制和 HTML 渲染。
- 统计页和聚合查询全部内置在服务端，不依赖独立前端工程。
- 首版优先保证预览链路稳定，不做纯 Java 的视频下载能力。

## 进阶用法

### 配置项

所有主要配置都通过环境变量提供：

| 变量名 | 默认值 | 说明 |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | 生成预览资源绝对地址时使用的服务基础地址 |
| `WEB_ICON_PATH` | 空 | 可选的网页 favicon 文件路径，未配置或文件不存在时回退到内置 `DefaultIcon.svg` |
| `CACHE_DIR` | `/data/cache` | 本地缓存根目录 |
| `STATS_DB_PATH` | `/data/stats/linkpeek.db` | SQLite 统计库文件路径 |
| `CACHE_TTL_SECONDS` | `86400` | 元数据和缩略图缓存有效期 |
| `CACHE_MAX_SIZE_GB` | `10` | 缓存空间上限 |
| `STATS_RETENTION_DAYS` | `180` | 统计事件保留天数 |
| `STATS_ADMIN_PASSWORD` | 空 | 统计管理密码。配置后可通过 `GET /api/stats/admin/purge-all?password=...` 清空统计数据 |
| `DOWNLOAD_TIMEOUT` | `120s` | 上游请求超时时间 |
| `VIDEO_MAX_QUALITY` | `480` | 为未来视频能力预留，首版暂不启用 |
| `PREVIEW_WARMUP_ENABLED` | `true` | 是否启用普通浏览器打开后的异步元数据预热 |
| `PREVIEW_WARMUP_THREADS` | `2` | 异步元数据预热线程数 |
| `PREVIEW_WARMUP_QUEUE_CAPACITY` | `64` | 异步元数据预热队列上限，队列满时跳过本次预热 |
| `NGA_PASSPORT_UID` | 空 | 可选的 NGA 登录态 UID，配置后 NGA provider 优先使用登录态抓取帖子 |
| `NGA_PASSPORT_CID` | 空 | 可选的 NGA 登录态 CID，需与 `NGA_PASSPORT_UID` 配对使用 |
| `LINUXDO_COOKIE` | 空 | 可选的 LINUX DO 登录态 Cookie，配置后可抓取当前账号可见但匿名不可见的主题 |
| `LOG_LEVEL` | `INFO` | 日志级别 |

### 新增 provider

后续扩展新平台时，建议：

1. 在独立模块中实现 `PreviewProvider`
2. 补齐 `supports()`、`canonicalize()`、`resolve()`
3. 如有需要实现 `downloadThumbnail()`
4. 在 `linkpeek-server` 中注册为 Spring Bean

`supports()` 是服务端支持判定接口和 Raycast 脚本的唯一规则来源；新增 provider 并部署后，Raycast 用户不需要更新脚本。该方法必须只做快速 URL 形态判断，不访问上游、不写缓存、不记录统计。

参考文档：

- [架构说明](./docs/architecture.md)
- [Provider 开发指南](./docs/provider-development.md)
- [TemplatePreviewProvider](./linkpeek-provider-template/src/main/java/io/github/shigella520/linkpeek/provider/template/TemplatePreviewProvider.java)

### 本地开发

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

如果你想显式指定端口，也可以这样启动：

```bash
CACHE_DIR=$PWD/.cache/linkpeek \
STATS_DB_PATH=$PWD/.data/linkpeek/stats.db \
./mvnw -pl linkpeek-server -am spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=8080
```

### 常见问题：`PKIX path building failed`

如果本地日志里出现类似下面的错误：

```text
javax.net.ssl.SSLHandshakeException: PKIX path building failed
```

这通常不是 LinkPeek 的业务逻辑问题，而是当前 Java 运行时不信任你机器当前看到的 HTTPS 证书链。最常见的场景是：

- 开着公司/校园网代理
- 开着 Clash、Surge、Charles、Fiddler 之类的 HTTPS 代理或抓包工具
- `curl` 走的是系统证书，而 Java 17 走的是自己独立的 truststore

你现在这个现象就是典型例子：`curl` 能访问 `https://api.bilibili.com`，但 Java `HttpClient` 握手失败。

建议按下面顺序处理：

1. 先关闭系统代理、抓包工具或 HTTPS 中间人代理，再重试启动服务。
2. 如果必须经过代理，把代理根证书导出为 `ca.crt`，导入当前 JDK 的 truststore：

```bash
keytool -importcert \
  -alias local-proxy-ca \
  -file /path/to/ca.crt \
  -keystore "$JAVA_HOME/lib/security/cacerts"
```

默认密码通常是 `changeit`。

3. 如果你不想改全局 JDK，也可以单独给本次启动指定 truststore：

```bash
CACHE_DIR=$PWD/.cache/linkpeek ./mvnw -pl linkpeek-server -am spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=8080 \
  -Dspring-boot.run.jvmArguments='-Djavax.net.ssl.trustStore=/path/to/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit'
```

4. 导入证书后，可以先用下面的命令验证 Java 侧是否恢复正常：

```bash
curl -A "facebookexternalhit/1.1" \
  "http://localhost:8080/preview?url=https%3A%2F%2Fwww.bilibili.com%2Fvideo%2FBV1McSQBEE71"
```

如果页面不再返回 `Preview Error`，说明证书链问题已经解决。

## 许可证

本项目使用 [MIT License](./LICENSE)。

这意味着：

- 允许自由使用、修改、分发和商用
- 只需保留原始版权声明和许可证文本
- 适合个人项目、开源项目和商业内部项目使用
