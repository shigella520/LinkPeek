package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.service.ShareSummaryImageService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/share-summary")
@Hidden
public class ShareSummaryPublicController {
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern ORDERED_LIST_ITEM = Pattern.compile("^\\d+[.)]\\s+(.+)$");
    private final ShareSummaryImageService shareSummaryImageService;

    public ShareSummaryPublicController(ShareSummaryImageService shareSummaryImageService) {
        this.shareSummaryImageService = shareSummaryImageService;
    }

    @GetMapping("/og-images/{publicToken}.{ext}")
    public ResponseEntity<Resource> ogImage(@PathVariable String publicToken, @PathVariable String ext) {
        try {
            ShareSummaryImageService.PublicImage image = shareSummaryImageService.publicImage(publicToken, ext);
            return ResponseEntity.ok()
                    .contentType(image.mediaType())
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                    .body(image.resource());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping(value = "/reports/{publicToken}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> report(@PathVariable String publicToken) {
        try {
            ShareSummaryImageService.PublicReport report = shareSummaryImageService.publicReport(publicToken);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(reportHtml(report.image(), report.run()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private String reportHtml(ShareSummaryImageRecord image, ShareSummaryRunRecord run) {
        String title = image.getOgTitle();
        String description = image.getOgDescription();
        String imageUrl = image.getOgImageUrl();
        String pageUrl = image.getOgPageUrl();
        String report = StringUtils.hasText(run.getReport()) ? run.getReport() : "";
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>%s</title>
                    <meta property="og:title" content="%s">
                    <meta property="og:description" content="%s">
                    <meta property="og:image" content="%s">
                    <meta property="og:image:width" content="1200">
                    <meta property="og:image:height" content="630">
                    <meta property="og:type" content="article">
                    <meta property="og:url" content="%s">
                    <meta name="twitter:card" content="summary_large_image">
                    <meta name="twitter:title" content="%s">
                    <meta name="twitter:description" content="%s">
                    <meta name="twitter:image" content="%s">
                    <style>
                        body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #f6f7f9; color: #172033; }
                        main { max-width: 860px; margin: 0 auto; padding: 32px 20px 48px; }
                        img { width: 100%%; height: auto; border-radius: 8px; background: #e8ebf0; }
                        article { line-height: 1.72; background: #fff; border: 1px solid #dfe3ea; border-radius: 8px; padding: 24px; }
                        article h2 { margin: 28px 0 12px; font-size: 22px; line-height: 1.35; }
                        article h3 { margin: 22px 0 10px; font-size: 18px; line-height: 1.4; }
                        article p { margin: 0 0 14px; color: #263044; }
                        article ul, article ol { margin: 0 0 16px 22px; padding: 0; }
                        article li { margin: 6px 0; }
                        article pre { overflow-x: auto; margin: 0 0 16px; padding: 14px; border-radius: 6px; background: #f1f3f6; }
                        article code { padding: 1px 5px; border-radius: 4px; background: #eef1f5; }
                        article pre code { padding: 0; background: transparent; }
                        h1 { font-size: 28px; line-height: 1.25; margin: 24px 0 12px; }
                        main > p { color: #5d6678; }
                    </style>
                </head>
                <body>
                    <main>
                        <img src="%s" alt="%s">
                        <h1>%s</h1>
                        <p>%s</p>
                        <article>%s</article>
                    </main>
                </body>
                </html>
                """.formatted(
                escapeHtml(title),
                escapeAttribute(title),
                escapeAttribute(description),
                escapeAttribute(imageUrl),
                escapeAttribute(pageUrl),
                escapeAttribute(title),
                escapeAttribute(description),
                escapeAttribute(imageUrl),
                escapeAttribute(imageUrl),
                escapeAttribute(title),
                escapeHtml(title),
                escapeHtml(description),
                renderMarkdown(report)
        );
    }

    private String renderMarkdown(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return "";
        }
        List<String> blocks = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        List<String> listItems = new ArrayList<>();
        StringBuilder code = null;
        for (String rawLine : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.stripTrailing();
            if (code != null) {
                if (line.strip().startsWith("```")) {
                    blocks.add("<pre><code>" + escapeHtml(code.toString().stripTrailing()) + "</code></pre>");
                    code = null;
                } else {
                    code.append(line).append('\n');
                }
                continue;
            }
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                flushParagraph(blocks, paragraph);
                flushList(blocks, listItems);
                code = new StringBuilder();
                continue;
            }
            if (trimmed.isEmpty()) {
                flushParagraph(blocks, paragraph);
                flushList(blocks, listItems);
                continue;
            }
            if (trimmed.startsWith("# ")) {
                flushParagraph(blocks, paragraph);
                flushList(blocks, listItems);
                blocks.add("<h2>" + inlineMarkdown(trimmed.substring(2).strip()) + "</h2>");
                continue;
            }
            if (trimmed.startsWith("## ")) {
                flushParagraph(blocks, paragraph);
                flushList(blocks, listItems);
                blocks.add("<h2>" + inlineMarkdown(trimmed.substring(3).strip()) + "</h2>");
                continue;
            }
            if (trimmed.startsWith("### ")) {
                flushParagraph(blocks, paragraph);
                flushList(blocks, listItems);
                blocks.add("<h3>" + inlineMarkdown(trimmed.substring(4).strip()) + "</h3>");
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushParagraph(blocks, paragraph);
                listItems.add("<li>" + inlineMarkdown(trimmed.substring(2).strip()) + "</li>");
                continue;
            }
            java.util.regex.Matcher orderedItem = ORDERED_LIST_ITEM.matcher(trimmed);
            if (orderedItem.matches()) {
                flushParagraph(blocks, paragraph);
                listItems.add("<li>" + inlineMarkdown(orderedItem.group(1).strip()) + "</li>");
                continue;
            }
            flushList(blocks, listItems);
            paragraph.add(trimmed);
        }
        if (code != null) {
            blocks.add("<pre><code>" + escapeHtml(code.toString().stripTrailing()) + "</code></pre>");
        }
        flushParagraph(blocks, paragraph);
        flushList(blocks, listItems);
        return String.join("\n", blocks);
    }

    private void flushParagraph(List<String> blocks, List<String> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        blocks.add("<p>" + inlineMarkdown(String.join(" ", paragraph)) + "</p>");
        paragraph.clear();
    }

    private void flushList(List<String> blocks, List<String> listItems) {
        if (listItems.isEmpty()) {
            return;
        }
        blocks.add("<ul>" + String.join("", listItems) + "</ul>");
        listItems.clear();
    }

    private String inlineMarkdown(String value) {
        String escaped = escapeHtml(value);
        escaped = BOLD.matcher(escaped).replaceAll("<strong>$1</strong>");
        return INLINE_CODE.matcher(escaped).replaceAll("<code>$1</code>");
    }

    private String escapeHtml(String value) {
        return escapeAttribute(value);
    }

    private String escapeAttribute(String value) {
        if (value == null) {
            return "";
        }
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
