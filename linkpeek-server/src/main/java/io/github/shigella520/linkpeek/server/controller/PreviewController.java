package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.core.error.InvalidPreviewUrlException;
import io.github.shigella520.linkpeek.core.error.PreviewException;
import io.github.shigella520.linkpeek.core.error.UnsupportedPreviewUrlException;
import io.github.shigella520.linkpeek.core.util.CrawlerMatcher;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import io.github.shigella520.linkpeek.server.render.HtmlPageRenderer;
import io.github.shigella520.linkpeek.server.service.PreviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.util.Locale;

@Controller
@Tag(name = "Preview", description = "统一链接预览入口")
public class PreviewController {
    private static final Logger log = LoggerFactory.getLogger(PreviewController.class);
    private static final String RENDER_MODE_HEADER = "X-LinkPeek-Render-Mode";

    private final PreviewService previewService;
    private final HtmlPageRenderer htmlPageRenderer;
    private final CrawlerMatcher crawlerMatcher;
    private final LinkPeekProperties properties;

    public PreviewController(
            PreviewService previewService,
            HtmlPageRenderer htmlPageRenderer,
            CrawlerMatcher crawlerMatcher,
            LinkPeekProperties properties
    ) {
        this.previewService = previewService;
        this.htmlPageRenderer = htmlPageRenderer;
        this.crawlerMatcher = crawlerMatcher;
        this.properties = properties;
    }

    @GetMapping(value = "/preview", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(
            summary = "生成链接预览",
            description = "对 crawler 请求返回 Open Graph HTML，对普通浏览器请求返回 302 跳转。调试时可通过 X-LinkPeek-Render-Mode 覆盖默认行为。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Crawler 请求返回预览 HTML"),
                    @ApiResponse(responseCode = "302", description = "普通浏览器跳转到原始链接", content = @Content(schema = @Schema(hidden = true))),
                    @ApiResponse(responseCode = "400", description = "URL 参数非法"),
                    @ApiResponse(responseCode = "422", description = "当前没有可用 provider 支持该 URL"),
                    @ApiResponse(responseCode = "502", description = "上游平台访问失败")
            }
    )
    public ResponseEntity<String> preview(
            @Parameter(
                    description = "完整目标 URL，必须为已 URL 编码的 http/https 地址。",
                    example = "https://www.bilibili.com/video/BV1xx411c7mD"
            )
            @RequestParam("url") String url,
            @Parameter(
                    description = "渲染模式覆盖。`auto` 按 User-Agent 自动判断，`crawler` 强制返回 OG HTML，`redirect` 强制返回 302。Swagger UI 调试时建议填 `crawler`。",
                    example = "crawler"
            )
            @RequestHeader(value = RENDER_MODE_HEADER, required = false) String renderModeHeader,
            @Parameter(hidden = true)
            HttpServletRequest request
    ) {
        long startedAt = System.nanoTime();
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);

        try {
            PreviewService.ResolvedPreview resolvedPreview = previewService.prepare(url);
            if (shouldRedirect(userAgent, renderModeHeader)) {
                return redirectResponse(resolvedPreview.sourceUrl(), resolvedPreview.previewKey().value(), resolvedPreview.provider().getId(), startedAt);
            }

            PreviewService.PreviewLoadResult result = previewService.loadPreview(resolvedPreview);
            String body = htmlPageRenderer.renderPreview(result.metadata(), result.resolvedPreview().previewKey(), properties.getBaseUrl());
            log.info(
                    "preview_served previewKey={} provider={} cacheHit={} durationMs={} status={}",
                    result.resolvedPreview().previewKey().value(),
                    result.metadata().providerId(),
                    result.cacheHit(),
                    elapsedMillis(startedAt),
                    200
            );
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(body);
        } catch (InvalidPreviewUrlException exception) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Invalid URL", exception.getMessage(), startedAt, null);
        } catch (UnsupportedPreviewUrlException exception) {
            return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported URL", exception.getMessage(), startedAt, null);
        } catch (PreviewException exception) {
            return errorResponse(HttpStatus.BAD_GATEWAY, "Preview Error", exception.getMessage(), startedAt, exception);
        }
    }

    private boolean shouldRedirect(String userAgent, String renderModeHeader) {
        RenderMode renderMode = RenderMode.fromHeader(renderModeHeader);
        return switch (renderMode) {
            case AUTO -> !crawlerMatcher.matches(userAgent);
            case CRAWLER -> false;
            case REDIRECT -> true;
        };
    }

    private ResponseEntity<String> redirectResponse(URI redirectUrl, String previewKey, String providerId, long startedAt) {
        log.info(
                "preview_redirected previewKey={} provider={} cacheHit={} durationMs={} status={}",
                previewKey,
                providerId,
                false,
                elapsedMillis(startedAt),
                302
        );
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(redirectUrl)
                .build();
    }

    private ResponseEntity<String> errorResponse(HttpStatus status, String title, String message, long startedAt, Throwable cause) {
        long durationMs = elapsedMillis(startedAt);
        if (cause == null) {
            log.info(
                    "preview_failed previewKey={} provider={} cacheHit={} durationMs={} status={} message={}",
                    "n/a",
                    "n/a",
                    false,
                    durationMs,
                    status.value(),
                    message
            );
        } else {
            log.warn(
                    "preview_failed previewKey={} provider={} cacheHit={} durationMs={} status={} message={}",
                    "n/a",
                    "n/a",
                    false,
                    durationMs,
                    status.value(),
                    message,
                    cause
            );
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .body(htmlPageRenderer.renderError(title, message));
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private enum RenderMode {
        AUTO,
        CRAWLER,
        REDIRECT;

        private static RenderMode fromHeader(String value) {
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "crawler" -> CRAWLER;
                case "redirect" -> REDIRECT;
                default -> throw new InvalidPreviewUrlException(
                        "Invalid X-LinkPeek-Render-Mode header. Supported values are auto, crawler, redirect."
                );
            };
        }
    }
}
