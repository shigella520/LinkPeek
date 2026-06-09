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
                        .reader { margin: 18px 0; padding: 14px; border: 1px solid #dfe3ea; border-radius: 8px; background: #fff; box-shadow: 0 10px 24px rgba(23, 32, 51, 0.05); }
                        .reader[hidden] { display: none !important; }
                        .reader-main { display: grid; grid-template-columns: 38px minmax(0, 1fr); gap: 12px; align-items: center; }
                        .reader button { border: 0; font: inherit; cursor: pointer; position: relative; }
                        .reader button:disabled { cursor: not-allowed; opacity: 0.42; }
                        .reader-play { width: 38px; height: 38px; border-radius: 8px; background: #172033; color: #fff; display: grid; place-items: center; box-shadow: inset 0 -1px 0 rgba(255, 255, 255, 0.12); }
                        .reader-play:hover:not(:disabled) { background: #263044; }
                        .reader-play::before { content: ""; display: block; width: 0; height: 0; margin-left: 3px; border-top: 7px solid transparent; border-bottom: 7px solid transparent; border-left: 11px solid currentColor; }
                        .reader-play.is-playing::before { width: 4px; height: 14px; margin-left: 0; border: 0; border-radius: 2px; background: currentColor; box-shadow: 8px 0 0 currentColor; transform: translateX(-4px); }
                        .reader-status { min-width: 0; color: #5d6678; font-size: 13px; line-height: 1.35; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                        .reader-progress { height: 4px; margin-top: 8px; border-radius: 999px; background: #e8edf4; overflow: hidden; }
                        .reader-progress-bar { display: block; width: 0%%; height: 100%%; border-radius: inherit; background: #2563eb; transition: width 180ms ease; }
                        .reader-settings { display: grid; grid-template-columns: minmax(128px, 0.7fr) minmax(220px, 1.3fr); gap: 14px; margin-top: 14px; align-items: end; }
                        .reader-control { display: grid; grid-template-columns: minmax(44px, auto) minmax(0, 1fr); gap: 8px; min-width: 0; align-items: center; color: #5d6678; font-size: 13px; }
                        .reader-control-label { display: block; line-height: 34px; white-space: nowrap; }
                        .reader-control-value { min-width: 0; display: grid; gap: 4px; }
                        .reader-control-meta { display: none; }
                        .reader input, .reader select { width: 100%%; min-height: 34px; border: 1px solid #d7dde7; border-radius: 7px; background: #f8fafc; color: #172033; font: inherit; }
                        .reader input { accent-color: #2563eb; }
                        .reader select { padding: 0 10px; }
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
                            .reader-settings { grid-template-columns: 1fr; }
                            .reader-control { grid-template-columns: 44px minmax(0, 1fr); }
                        }
                    </style>
                </head>
                <body>
                    <main>
                        <img src="%s" alt="%s">
                        <h1 data-reader-title>%s</h1>
                        <p data-reader-description>%s</p>
                        <section class="reader" data-reader hidden aria-label="报告朗读">
                            <div class="reader-main">
                                <button class="reader-play" type="button" data-reader-action="toggle" aria-label="播放"></button>
                                <div>
                                    <div class="reader-status" data-reader-status>准备播放</div>
                                    <div class="reader-progress" aria-hidden="true">
                                        <span class="reader-progress-bar" data-reader-progress></span>
                                    </div>
                                </div>
                            </div>
                            <div class="reader-settings">
                                <label class="reader-control">
                                    <span class="reader-control-label">语速</span>
                                    <span class="reader-control-value">
                                        <select data-reader-rate>
                                            <option value="1.0">1.0x</option>
                                            <option value="1.1">1.1x</option>
                                            <option value="1.2">1.2x</option>
                                            <option value="1.3">1.3x</option>
                                            <option value="1.4">1.4x</option>
                                            <option value="1.5">1.5x</option>
                                            <option value="1.8">1.8x</option>
                                            <option value="2.0">2.0x</option>
                                        </select>
                                        <span class="reader-control-meta" data-reader-rate-label></span>
                                    </span>
                                </label>
                                <label class="reader-control reader-voice">
                                    <span class="reader-control-label">音色</span>
                                    <span class="reader-control-value">
                                        <select data-reader-voice>
                                            <option value="">系统默认</option>
                                        </select>
                                        <span class="reader-control-meta" data-reader-voice-hint>系统默认</span>
                                    </span>
                                </label>
                            </div>
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
                                toggle: root.querySelector('[data-reader-action="toggle"]')
                            };
                            const status = root.querySelector("[data-reader-status]");
                            const progress = root.querySelector("[data-reader-progress]");
                            const rateInput = root.querySelector("[data-reader-rate]");
                            const rateLabel = root.querySelector("[data-reader-rate-label]");
                            const voiceSelect = root.querySelector("[data-reader-voice]");
                            const voiceHint = root.querySelector("[data-reader-voice-hint]");
                            const storageKey = "linkpeek.shareSummary.readerVoice";
                            let availableVoices = [];
                            let chunks = [];
                            let chunkIndex = 0;
                            let stopRequested = false;
                            let pausedByUser = false;

                            root.hidden = false;

                            function defaultRate() {
                                const viewportWidth = Math.min(
                                    window.innerWidth || Number.MAX_SAFE_INTEGER,
                                    (window.screen && window.screen.width) || Number.MAX_SAFE_INTEGER
                                );
                                return viewportWidth <= 520 ? 1.1 : 1.4;
                            }

                            function setRate(value) {
                                rateInput.value = Number(value).toFixed(1);
                                rateLabel.textContent = `${Number(rateInput.value).toFixed(1)}x`;
                            }

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

                            function isChineseVoice(voice) {
                                return /^zh/i.test(voice.lang) || /Chinese|Mandarin|中文|普通话/i.test(voice.name);
                            }

                            function voiceId(voice) {
                                return `${voice.name}||${voice.lang}`;
                            }

                            function rememberedVoice() {
                                try {
                                    return localStorage.getItem(storageKey) || "";
                                } catch (error) {
                                    return "";
                                }
                            }

                            function saveVoice(value) {
                                try {
                                    localStorage.setItem(storageKey, value);
                                } catch (error) {
                                    // Storage can be unavailable in private or embedded browser contexts.
                                }
                            }

                            function selectedVoice() {
                                const selectedId = voiceSelect.value;
                                if (selectedId) {
                                    return availableVoices.find((voice) => voiceId(voice) === selectedId) || null;
                                }
                                return availableVoices.find(isChineseVoice) || null;
                            }

                            function populateVoices() {
                                const voices = synth.getVoices();
                                if (!voices.length) {
                                    voiceHint.textContent = "系统默认";
                                    return;
                                }
                                const displayVoices = voices.filter(isChineseVoice);
                                availableVoices = displayVoices.length ? displayVoices : voices;
                                const previousValue = voiceSelect.value;
                                const targetValue = previousValue || rememberedVoice();
                                voiceSelect.innerHTML = '<option value="">系统默认</option>';
                                availableVoices.forEach((voice) => {
                                    const option = document.createElement("option");
                                    option.value = voiceId(voice);
                                    option.textContent = `${voice.name} (${voice.lang || "默认"})`;
                                    voiceSelect.appendChild(option);
                                });
                                if (targetValue && availableVoices.some((voice) => voiceId(voice) === targetValue)) {
                                    voiceSelect.value = targetValue;
                                }
                                voiceHint.textContent = displayVoices.length ? "中文音色" : "本机音色";
                            }

                            function progressText() {
                                if (!chunks.length) {
                                    return "";
                                }
                                return `${Math.min(chunkIndex + 1, chunks.length)}/${chunks.length}`;
                            }

                            function setStatus(message, state) {
                                const playing = state === "playing";
                                const paused = state === "paused";
                                const percent = chunks.length ? Math.min(100, Math.round((chunkIndex / chunks.length) * 100)) : 0;
                                status.textContent = message;
                                actions.toggle.classList.toggle("is-playing", playing);
                                actions.toggle.setAttribute("aria-label", playing ? "暂停" : (paused ? "继续" : "播放"));
                                progress.style.width = `${percent}%%`;
                            }

                            function createUtterance(text, index) {
                                const utterance = new SpeechSynthesisUtterance(text);
                                utterance.lang = "zh-CN";
                                utterance.rate = Number(rateInput.value) || defaultRate();
                                const voice = selectedVoice();
                                if (voice) {
                                    utterance.voice = voice;
                                    utterance.lang = voice.lang || "zh-CN";
                                }
                                utterance.onstart = () => {
                                    if (stopRequested) {
                                        return;
                                    }
                                    chunkIndex = index;
                                    setStatus(`播放中 ${progressText()}`, "playing");
                                };
                                utterance.onend = () => {
                                    if (stopRequested) {
                                        return;
                                    }
                                    if (index >= chunks.length - 1) {
                                        chunkIndex = chunks.length;
                                        setStatus("播放完成", "idle");
                                        progress.style.width = "100%%";
                                    }
                                };
                                utterance.onerror = () => {
                                    if (!stopRequested) {
                                        setStatus("朗读被浏览器中断，请重试。", "idle");
                                    }
                                };
                                return utterance;
                            }

                            function play() {
                                const nextChunks = splitText(reportText());
                                if (nextChunks.length === 0) {
                                    setStatus("没有可朗读正文", "idle");
                                    return;
                                }
                                stopRequested = true;
                                synth.cancel();
                                chunks = nextChunks;
                                chunkIndex = 0;
                                stopRequested = false;
                                pausedByUser = false;
                                chunks.forEach((chunk, index) => {
                                    synth.speak(createUtterance(chunk, index));
                                });
                                setStatus(`播放中 ${progressText()}`, "playing");
                            }

                            function pause() {
                                if (synth.speaking && !synth.paused) {
                                    pausedByUser = true;
                                    synth.pause();
                                    setStatus("已暂停", "paused");
                                }
                            }

                            function resume() {
                                if (synth.paused) {
                                    pausedByUser = false;
                                    synth.resume();
                                    setStatus(`播放中 ${progressText()}`, "playing");
                                }
                            }

                            function stop() {
                                stopRequested = true;
                                synth.cancel();
                                pausedByUser = false;
                                setStatus("已停止", "idle");
                                progress.style.width = "0%%";
                            }

                            function toggle() {
                                if (synth.speaking && !synth.paused) {
                                    pause();
                                    return;
                                }
                                if (synth.paused) {
                                    resume();
                                    return;
                                }
                                play();
                            }

                            actions.toggle.addEventListener("click", toggle);
                            rateInput.addEventListener("change", () => {
                                setRate(rateInput.value);
                                if (synth.speaking || synth.paused) {
                                    stop();
                                    setStatus("语速已切换，请重新播放", "idle");
                                }
                            });
                            voiceSelect.addEventListener("change", () => {
                                saveVoice(voiceSelect.value);
                                if (synth.speaking || synth.paused) {
                                    stop();
                                    setStatus("音色已切换，请重新播放", "idle");
                                }
                            });
                            document.addEventListener("visibilitychange", () => {
                                if (!document.hidden && pausedByUser && synth.paused) {
                                    setStatus("已暂停", "paused");
                                }
                            });
                            window.addEventListener("beforeunload", () => {
                                stopRequested = true;
                                synth.cancel();
                            });
                            if ("onvoiceschanged" in synth) {
                                synth.onvoiceschanged = populateVoices;
                            }
                            setRate(defaultRate());
                            populateVoices();
                            setStatus("准备播放", "idle");
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
