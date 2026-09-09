# Provider 开发指南

本文说明当前 `PreviewProvider` SPI 的实现契约。新增平台时优先把平台细节收敛在独立 provider 模块内，不要泄漏到 `linkpeek-server`。

## 基本契约

每个 provider 都需要实现 `io.github.shigella520.linkpeek.core.provider.PreviewProvider`。

必须实现的方法：

- `getId()`
- `supports(URI sourceUrl)`
- `canonicalize(URI sourceUrl)`
- `resolve(URI sourceUrl)`

可选扩展方法：

- `enrichForAiTitle(PreviewMetadata metadata, URI sourceUrl)`
- `supportsAiTitle(PreviewMetadata metadata)`
- `downloadThumbnail(PreviewMetadata metadata, Path targetPath)`
- `downloadVideo(PreviewMetadata metadata, Path targetPath)`

默认 AI 标题支持规则是：

```text
metadata != null
metadata.thumbnailUrl starts with generated://
metadata.rawContent is not blank
```

`enrichForAiTitle(...)` 默认原样返回元数据。文本卡片 provider 如果希望生成更高质量的 AI 标题，可以在这里基于原始 URL 补齐更完整的 `rawContent`，例如抓取帖子正文和回复内容。补齐失败时应尽量返回原元数据，不影响基础预览。

`supportsAiTitle(...)` 默认调用 `PreviewProvider.defaultSupportsAiTitle(...)`。真实图片 provider 如果要保留原图但使用 AI 标题，可以覆盖该方法，并确保 `rawContent` 包含可用于生成标题的文本。

如果不支持媒体能力，`downloadThumbnail(...)` 和 `downloadVideo(...)` 的默认实现会抛出 `MediaNotSupportedException`。

## 实现规则

- `supports(...)` 是 `/api/preview/support` 和 Raycast 脚本的唯一支持判定来源，必须足够快。
- `supports(...)` 只能做 URL 形态判断，不应访问上游、不写缓存、不记录统计。
- `canonicalize(...)` 应把同一平台的 URL 变体收敛成稳定 URL。
- `resolve(...)` 应返回完整可用的 `PreviewMetadata`。
- 文本卡片 provider 应设置 `generated://...` 形式的缩略图地址，并在 `downloadThumbnail(...)` 中渲染标题卡片。
- 真实图片 provider 可以保留上游缩略图地址，由 `downloadThumbnail(...)` 下载并写入目标路径。
- 如果 provider 支持 AI 标题，`PreviewMetadata.rawContent` 应包含适合生成标题的正文。
- provider 模块不能依赖 `linkpeek-server`。
- 登录态、Cookie 或平台开关应通过服务端配置注入到 provider 构造器，不应在 provider 模块直接读取后台数据库。

## 当前内置 provider

| Provider | 模块 | 预览形态 | 运行配置 |
| --- | --- | --- | --- |
| `bilibili` | `linkpeek-provider-bilibili` | 上游图片缩略图 | Bilibili AI 标题开关 |
| `gaphub` | `linkpeek-provider-gaphub` | 生成式文本卡片 | 无 |
| `linuxdo` | `linkpeek-provider-linuxdo` | 生成式文本卡片 | LinuxDo Cookie |
| `nga` | `linkpeek-provider-nga` | 生成式文本卡片 | NGA 登录态 |
| `v2ex` | `linkpeek-provider-v2ex` | 生成式文本卡片 | 无 |

当前 provider 在 `linkpeek-server/src/main/java/io/github/shigella520/linkpeek/server/config/ProviderConfiguration.java` 中注册为 Spring Bean。

## 接入服务端

1. 新建或复用独立 Maven provider 模块。
2. 实现 `PreviewProvider`。
3. 将 provider 模块作为依赖加入 `linkpeek-server`。
4. 在 `ProviderConfiguration` 中把 provider 暴露为 Spring Bean。
5. 为 URL 匹配、canonical 化、上游响应映射、AI 标题上下文和媒体下载补齐测试。

注册完成并部署后，`supports(...)` 会自动纳入云端支持判定接口，Raycast 用户无需更新脚本中的平台规则。

## 模板参考

可以从这里开始：

- [`TemplatePreviewProvider`](../linkpeek-provider-template/src/main/java/io/github/shigella520/linkpeek/provider/template/TemplatePreviewProvider.java)

如果需要参考上游 HTTP 调用和缩略图下载处理方式，优先查看 Bilibili provider。需要参考文本卡片和 AI 标题上下文补齐时，优先查看 V2EX、LINUX DO、NGA 或 GapHub provider。
