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
                        .reader { margin: 18px 0; padding: 16px; border: 1px solid #dfe3ea; border-radius: 8px; background: #fff; }
                        .reader[hidden] { display: none !important; }
                        .reader-row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
                        .reader-actions { display: flex; gap: 8px; flex-wrap: wrap; }
                        .reader button { min-height: 36px; padding: 0 14px; border: 1px solid #ccd3df; border-radius: 6px; background: #f8fafc; color: #172033; font: inherit; cursor: pointer; }
                        .reader button:hover:not(:disabled) { background: #eef2f7; }
                        .reader button:disabled { cursor: not-allowed; opacity: 0.45; }
                        .reader-rate { display: flex; gap: 8px; align-items: center; color: #5d6678; font-size: 14px; }
                        .reader-rate input { width: 120px; accent-color: #2563eb; }
                        .reader-status { margin: 10px 0 0; color: #5d6678; font-size: 14px; }
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
                        @media (max-width: 520px) {
                            main { padding: 20px 14px 36px; }
                            article { padding: 18px; }
                            .reader { padding: 14px; }
                            .reader-row { align-items: stretch; }
                            .reader-actions, .reader-rate { width: 100%%; }
                            .reader-actions button { flex: 1 1 calc(50%% - 4px); }
                            .reader-rate input { flex: 1; width: auto; }
                        }
                    </style>
                </head>
                <body>
                    <main>
                        <img src="%s" alt="%s">
                        <h1 data-reader-title>%s</h1>
                        <p data-reader-description>%s</p>
                        <section class="reader" data-reader hidden aria-label="报告朗读">
                            <div class="reader-row">
                                <div class="reader-actions">
                                    <button type="button" data-reader-action="play">播放</button>
                                    <button type="button" data-reader-action="pause" disabled>暂停</button>
                                    <button type="button" data-reader-action="resume" disabled>继续</button>
                                    <button type="button" data-reader-action="stop" disabled>停止</button>
                                </div>
                                <label class="reader-rate">
                                    语速
                                    <input type="range" min="0.8" max="1.4" step="0.1" value="1" data-reader-rate>
                                    <span data-reader-rate-label>1.0x</span>
                                </label>
                            </div>
                            <p class="reader-status" data-reader-status>点击播放朗读报告正文。</p>
                        </section>
                        <article data-reader-content>%s</article>
                    </main>
                    <script>
                        (() => {
                            const root = document.querySelector("[data-reader]");
                            const content = document.querySelector("[data-reader-content]");
                            if (!root || !content || !("speechSynthesis" in window) || !("SpeechSynthesisUtterance" in window)) {
                                return;
                            }

                            const synth = window.speechSynthesis;
                            const actions = {
                                play: root.querySelector('[data-reader-action="play"]'),
                                pause: root.querySelector('[data-reader-action="pause"]'),
                                resume: root.querySelector('[data-reader-action="resume"]'),
                                stop: root.querySelector('[data-reader-action="stop"]')
                            };
                            const status = root.querySelector("[data-reader-status]");
                            const rateInput = root.querySelector("[data-reader-rate]");
                            const rateLabel = root.querySelector("[data-reader-rate-label]");
                            let chunks = [];
                            let chunkIndex = 0;
                            let stopRequested = false;

                            root.hidden = false;

                            function reportText() {
                                return [
                                    document.querySelector("[data-reader-title]"),
                                    document.querySelector("[data-reader-description]"),
                                    content
                                ]
                                    .map((node) => node ? node.innerText.trim() : "")
                                    .filter(Boolean)
                                    .join("\\n");
                            }

                            function splitText(value) {
                                const normalized = value.replace(/\\s+/g, " ").trim();
                                if (!normalized) {
                                    return [];
                                }
                                const sentences = normalized.match(/[^。！？!?；;]+[。！？!?；;]?/g) || [normalized];
                                const result = [];
                                let current = "";
                                sentences.forEach((sentence) => {
                                    const text = sentence.trim();
                                    if (!text) {
                                        return;
                                    }
                                    if (text.length > 140) {
                                        if (current) {
                                            result.push(current);
                                            current = "";
                                        }
                                        for (let i = 0; i < text.length; i += 120) {
                                            result.push(text.slice(i, i + 120));
                                        }
                                        return;
                                    }
                                    const next = current ? current + " " + text : text;
                                    if (next.length > 160) {
                                        result.push(current);
                                        current = text;
                                    } else {
                                        current = next;
                                    }
                                });
                                if (current) {
                                    result.push(current);
                                }
                                return result;
                            }

                            function chineseVoice() {
                                const voices = synth.getVoices();
                                return voices.find((voice) => /^zh/i.test(voice.lang))
                                    || voices.find((voice) => /Chinese|Mandarin|中文|普通话/i.test(voice.name))
                                    || null;
                            }

                            function setStatus(message, state) {
                                status.textContent = message;
                                const playing = state === "playing";
                                const paused = state === "paused";
                                const active = playing || paused;
                                actions.play.disabled = playing;
                                actions.pause.disabled = !playing;
                                actions.resume.disabled = !paused;
                                actions.stop.disabled = !active;
                            }

                            function speakCurrentChunk() {
                                if (stopRequested) {
                                    return;
                                }
                                if (chunkIndex >= chunks.length) {
                                    setStatus("朗读完成。", "idle");
                                    return;
                                }
                                const utterance = new SpeechSynthesisUtterance(chunks[chunkIndex]);
                                utterance.lang = "zh-CN";
                                utterance.rate = Number(rateInput.value) || 1;
                                const voice = chineseVoice();
                                if (voice) {
                                    utterance.voice = voice;
                                }
                                utterance.onend = () => {
                                    if (stopRequested) {
                                        return;
                                    }
                                    chunkIndex += 1;
                                    speakCurrentChunk();
                                };
                                utterance.onerror = () => {
                                    if (!stopRequested) {
                                        setStatus("朗读被浏览器中断，请重试。", "idle");
                                    }
                                };
                                setStatus(`正在朗读 ${chunkIndex + 1}/${chunks.length}`, "playing");
                                synth.speak(utterance);
                            }

                            function play() {
                                const nextChunks = splitText(reportText());
                                if (nextChunks.length === 0) {
                                    setStatus("没有可朗读的正文。", "idle");
                                    return;
                                }
                                stopRequested = true;
                                synth.cancel();
                                chunks = nextChunks;
                                chunkIndex = 0;
                                stopRequested = false;
                                speakCurrentChunk();
                            }

                            function pause() {
                                if (synth.speaking && !synth.paused) {
                                    synth.pause();
                                    setStatus("已暂停。", "paused");
                                }
                            }

                            function resume() {
                                if (synth.paused) {
                                    synth.resume();
                                    setStatus(`正在朗读 ${chunkIndex + 1}/${chunks.length}`, "playing");
                                }
                            }

                            function stop() {
                                stopRequested = true;
                                synth.cancel();
                                setStatus("已停止。", "idle");
                            }

                            actions.play.addEventListener("click", play);
                            actions.pause.addEventListener("click", pause);
                            actions.resume.addEventListener("click", resume);
                            actions.stop.addEventListener("click", stop);
                            rateInput.addEventListener("input", () => {
                                rateLabel.textContent = `${Number(rateInput.value).toFixed(1)}x`;
                            });
                            window.addEventListener("pagehide", () => synth.cancel());
                            if ("onvoiceschanged" in synth) {
                                synth.onvoiceschanged = chineseVoice;
                            }
                            setStatus("点击播放朗读报告正文。", "idle");
                        })();
                    </script>
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
