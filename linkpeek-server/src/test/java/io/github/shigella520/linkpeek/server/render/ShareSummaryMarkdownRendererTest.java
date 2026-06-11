package io.github.shigella520.linkpeek.server.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareSummaryMarkdownRendererTest {
    @Test
    void rendersHtmlAndSpeechTextFromSameMarkdownSource() {
        String markdown = """
                # 分享总结报告正文

                ## 关键洞察

                - 链接分享增长
                - **内容洞察**稳定
                - [图片测试标题](https://example.com/image)
                - 裸链接 https://example.com/plain
                """;

        String html = ShareSummaryMarkdownRenderer.toHtml(markdown);
        String plainText = ShareSummaryMarkdownRenderer.toPlainText(markdown);

        assertTrue(html.contains("<h2>分享总结报告正文</h2>"));
        assertTrue(html.contains("<strong>内容洞察</strong>稳定"));
        assertTrue(html.contains("<a href=\"https://example.com/image\" target=\"_blank\" rel=\"noreferrer\">图片测试标题</a>"));
        assertEquals("""
                分享总结报告正文
                关键洞察
                链接分享增长
                内容洞察稳定
                图片测试标题
                裸链接 https://example.com/plain
                """.strip(), plainText);
        assertFalse(plainText.contains("#"));
        assertFalse(plainText.contains("**"));
        assertFalse(plainText.contains("[图片测试标题]"));
    }
}
