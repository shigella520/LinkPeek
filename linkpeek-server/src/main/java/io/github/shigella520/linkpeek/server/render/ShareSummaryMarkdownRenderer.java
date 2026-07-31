package io.github.shigella520.linkpeek.server.render;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShareSummaryMarkdownRenderer {
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)");
    private static final Pattern URL = Pattern.compile("(?<![\"'=])(https?://[^\\s<]+)");
    private static final Pattern ANCHOR = Pattern.compile("<a\\b[^>]*>.*?</a>");
    private static final Pattern ORDERED_LIST_ITEM = Pattern.compile("^\\d+[.)]\\s+(.+)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern THEMATIC_BREAK = Pattern.compile("^(?:(?:-\\s*){3,}|(?:\\*\\s*){3,}|(?:_\\s*){3,})$");

    private ShareSummaryMarkdownRenderer() {
    }

    public static String toHtml(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return "";
        }
        List<String> blocks = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        List<String> listItems = new ArrayList<>();
        StringBuilder code = null;
        for (String rawLine : lines(markdown)) {
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
                flushHtmlParagraph(blocks, paragraph);
                flushHtmlList(blocks, listItems);
                code = new StringBuilder();
                continue;
            }
            if (trimmed.isEmpty()) {
                flushHtmlParagraph(blocks, paragraph);
                flushHtmlList(blocks, listItems);
                continue;
            }
            if (THEMATIC_BREAK.matcher(trimmed).matches()) {
                flushHtmlParagraph(blocks, paragraph);
                flushHtmlList(blocks, listItems);
                blocks.add("<hr>");
                continue;
            }
            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                flushHtmlParagraph(blocks, paragraph);
                flushHtmlList(blocks, listItems);
                String tag = heading.group(1).length() <= 2 ? "h2" : "h3";
                blocks.add("<" + tag + ">" + inlineHtml(heading.group(2).strip()) + "</" + tag + ">");
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushHtmlParagraph(blocks, paragraph);
                listItems.add("<li>" + inlineHtml(trimmed.substring(2).strip()) + "</li>");
                continue;
            }
            Matcher orderedItem = ORDERED_LIST_ITEM.matcher(trimmed);
            if (orderedItem.matches()) {
                flushHtmlParagraph(blocks, paragraph);
                listItems.add("<li>" + inlineHtml(orderedItem.group(1).strip()) + "</li>");
                continue;
            }
            flushHtmlList(blocks, listItems);
            paragraph.add(trimmed);
        }
        if (code != null) {
            blocks.add("<pre><code>" + escapeHtml(code.toString().stripTrailing()) + "</code></pre>");
        }
        flushHtmlParagraph(blocks, paragraph);
        flushHtmlList(blocks, listItems);
        return String.join("\n", blocks);
    }

    public static String toPlainText(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return "";
        }
        List<String> blocks = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        List<String> listItems = new ArrayList<>();
        StringBuilder code = null;
        for (String rawLine : lines(markdown)) {
            String line = rawLine.stripTrailing();
            if (code != null) {
                if (line.strip().startsWith("```")) {
                    addTextBlock(blocks, code.toString().stripTrailing());
                    code = null;
                } else {
                    code.append(line).append('\n');
                }
                continue;
            }
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                flushTextParagraph(blocks, paragraph);
                flushTextList(blocks, listItems);
                code = new StringBuilder();
                continue;
            }
            if (trimmed.isEmpty()) {
                flushTextParagraph(blocks, paragraph);
                flushTextList(blocks, listItems);
                continue;
            }
            if (THEMATIC_BREAK.matcher(trimmed).matches()) {
                flushTextParagraph(blocks, paragraph);
                flushTextList(blocks, listItems);
                continue;
            }
            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                flushTextParagraph(blocks, paragraph);
                flushTextList(blocks, listItems);
                addTextBlock(blocks, inlineText(heading.group(2).strip()));
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushTextParagraph(blocks, paragraph);
                listItems.add(inlineText(trimmed.substring(2).strip()));
                continue;
            }
            Matcher orderedItem = ORDERED_LIST_ITEM.matcher(trimmed);
            if (orderedItem.matches()) {
                flushTextParagraph(blocks, paragraph);
                listItems.add(inlineText(orderedItem.group(1).strip()));
                continue;
            }
            flushTextList(blocks, listItems);
            paragraph.add(trimmed);
        }
        if (code != null) {
            addTextBlock(blocks, code.toString().stripTrailing());
        }
        flushTextParagraph(blocks, paragraph);
        flushTextList(blocks, listItems);
        return normalizePlainText(String.join("\n", blocks));
    }

    private static String[] lines(String markdown) {
        return markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    private static void flushHtmlParagraph(List<String> blocks, List<String> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        blocks.add("<p>" + inlineHtml(String.join(" ", paragraph)) + "</p>");
        paragraph.clear();
    }

    private static void flushHtmlList(List<String> blocks, List<String> listItems) {
        if (listItems.isEmpty()) {
            return;
        }
        blocks.add("<ul>" + String.join("", listItems) + "</ul>");
        listItems.clear();
    }

    private static void flushTextParagraph(List<String> blocks, List<String> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        addTextBlock(blocks, inlineText(String.join(" ", paragraph)));
        paragraph.clear();
    }

    private static void flushTextList(List<String> blocks, List<String> listItems) {
        if (listItems.isEmpty()) {
            return;
        }
        listItems.stream()
                .map(ShareSummaryMarkdownRenderer::normalizePlainText)
                .filter(StringUtils::hasText)
                .forEach(blocks::add);
        listItems.clear();
    }

    private static void addTextBlock(List<String> blocks, String value) {
        String normalized = normalizePlainText(value);
        if (StringUtils.hasText(normalized)) {
            blocks.add(normalized);
        }
    }

    private static String inlineHtml(String value) {
        String escaped = escapeHtml(value);
        escaped = renderMarkdownLinks(escaped);
        escaped = renderPlainUrls(escaped);
        escaped = BOLD.matcher(escaped).replaceAll("<strong>$1</strong>");
        return INLINE_CODE.matcher(escaped).replaceAll("<code>$1</code>");
    }

    private static String inlineText(String value) {
        String rendered = renderMarkdownLinksAsText(value);
        rendered = BOLD.matcher(rendered).replaceAll("$1");
        rendered = INLINE_CODE.matcher(rendered).replaceAll("$1");
        return normalizePlainText(rendered);
    }

    private static String renderMarkdownLinks(String value) {
        Matcher matcher = MARKDOWN_LINK.matcher(value);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(linkHtml(matcher.group(2), matcher.group(1))));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static String renderMarkdownLinksAsText(String value) {
        Matcher matcher = MARKDOWN_LINK.matcher(value);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(matcher.group(1)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static String renderPlainUrls(String value) {
        Matcher anchor = ANCHOR.matcher(value);
        StringBuilder rendered = new StringBuilder();
        int lastIndex = 0;
        while (anchor.find()) {
            rendered.append(linkPlainUrls(value.substring(lastIndex, anchor.start())));
            rendered.append(anchor.group());
            lastIndex = anchor.end();
        }
        rendered.append(linkPlainUrls(value.substring(lastIndex)));
        return rendered.toString();
    }

    private static String linkPlainUrls(String value) {
        Matcher matcher = URL.matcher(value);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String url = stripTrailingUrlPunctuation(matcher.group(1));
            String trailing = matcher.group(1).substring(url.length());
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(linkHtml(url, url) + trailing));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static String stripTrailingUrlPunctuation(String url) {
        String stripped = url;
        while (stripped.endsWith(".")
                || stripped.endsWith(",")
                || stripped.endsWith(";")
                || stripped.endsWith(":")
                || stripped.endsWith("!")
                || stripped.endsWith("?")
                || stripped.endsWith("，")
                || stripped.endsWith("。")
                || stripped.endsWith("；")
                || stripped.endsWith("：")
                || stripped.endsWith("！")
                || stripped.endsWith("？")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static String linkHtml(String escapedUrl, String escapedLabel) {
        return "<a href=\"%s\" target=\"_blank\" rel=\"noreferrer\">%s</a>".formatted(
                escapedUrl,
                escapedLabel
        );
    }

    private static String normalizePlainText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00a0', ' ')
                .replaceAll("[\\t ]+", " ")
                .replaceAll(" *\\n+ *", "\n")
                .strip();
    }

    private static String escapeHtml(String value) {
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
