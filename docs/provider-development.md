# Provider 开发指南

## 基本契约

每个 provider 都需要实现 `io.github.shigella520.linkpeek.core.provider.PreviewProvider`。

必须实现的方法：

- `getId()`
- `supports(URI sourceUrl)`
- `canonicalize(URI sourceUrl)`
- `resolve(URI sourceUrl)`

可选的媒体方法：

- `downloadThumbnail(...)`
- `downloadVideo(...)`

如果不支持媒体能力，默认实现会抛出 `MediaNotSupportedException`。

## 实现规则

- `supports(...)` 必须足够快，且不能产生副作用。
- `canonicalize(...)` 应把同一平台的多种 URL 变体收敛成一个稳定 URL。
- `resolve(...)` 应返回完整可用的 `PreviewMetadata`。
- provider 模块不能依赖 `linkpeek-server`。

## 接入服务端

1. 将 provider 模块作为依赖加入 `linkpeek-server`。
2. 在 `ProviderConfiguration` 中把该 provider 暴露为 Spring Bean。
3. 为 URL 匹配、canonical 化和上游响应映射补齐测试。

## 模板参考

可以从这里开始：

- [`TemplatePreviewProvider`](../linkpeek-provider-template/src/main/java/io/github/shigella520/linkpeek/provider/template/TemplatePreviewProvider.java)

如果需要参考上游 HTTP 调用和缩略图下载处理方式，优先查看 Bilibili provider 的实现。
