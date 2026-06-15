# Provider 开发指南

## 基本契约

每个 provider 都需要实现 `io.github.shigella520.linkpeek.core.provider.PreviewProvider`。

必须实现的方法：

- `getId()`
- `supports(URI sourceUrl)`
- `canonicalize(URI sourceUrl)`
- `resolve(URI sourceUrl)`

可选扩展方法：

- `enrichForAiTitle(...)`
- `supportsAiTitle(...)`
- `downloadThumbnail(...)`
- `downloadVideo(...)`

`enrichForAiTitle(...)` 默认原样返回元数据。文本卡片 provider 如果希望支持更高质量的 AI 标题，可以在这里基于原始 URL 补齐更完整的 `rawContent`，例如抓取帖子正文和回复内容；失败时应尽量返回原元数据，不影响基础预览。

`supportsAiTitle(...)` 默认只允许 `generated://...` 文本卡片且 `rawContent` 非空的元数据进入 AI 标题生成。真实图片 provider 如果要保留原图但使用 AI 标题，可以覆盖该方法，并确保 `rawContent` 包含可供生成标题的文本。

如果不支持媒体能力，`downloadThumbnail(...)` 和 `downloadVideo(...)` 的默认实现会抛出 `MediaNotSupportedException`。

## 实现规则

- `supports(...)` 是 `/api/preview/support` 和 Raycast 脚本的唯一支持判定来源，必须足够快，且不能产生副作用。
- `supports(...)` 只能做 URL 形态判断，不应访问上游、不写缓存、不记录统计。
- `canonicalize(...)` 应把同一平台的多种 URL 变体收敛成一个稳定 URL。
- `resolve(...)` 应返回完整可用的 `PreviewMetadata`。
- 文本卡片 provider 应设置 `generated://...` 形式的缩略图地址，并在 `downloadThumbnail(...)` 中渲染标题卡片。
- 真实图片 provider 可以保留上游缩略图地址，由 `downloadThumbnail(...)` 下载并写入目标路径。
- 如果 provider 支持 AI 标题，`PreviewMetadata.rawContent` 应包含适合总结的正文；`enrichForAiTitle(...)` 可作为额外的正文补齐步骤。
- provider 模块不能依赖 `linkpeek-server`。

## 接入服务端

1. 将 provider 模块作为依赖加入 `linkpeek-server`。
2. 在 `ProviderConfiguration` 中把该 provider 暴露为 Spring Bean。
3. 为 URL 匹配、canonical 化和上游响应映射补齐测试。

注册完成并部署后，`supports(...)` 会自动纳入云端支持判定接口，Raycast 用户无需更新脚本中的平台规则。

## 模板参考

可以从这里开始：

- [`TemplatePreviewProvider`](../linkpeek-provider-template/src/main/java/io/github/shigella520/linkpeek/provider/template/TemplatePreviewProvider.java)

如果需要参考上游 HTTP 调用和缩略图下载处理方式，优先查看 Bilibili provider 的实现。
