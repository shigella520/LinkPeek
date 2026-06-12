package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.service.ShareSummaryAudioService;
import io.github.shigella520.linkpeek.server.admin.service.ShareSummaryImageService;
import io.github.shigella520.linkpeek.server.render.ShareSummaryMarkdownRenderer;
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
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/share-summary")
@Hidden
public class ShareSummaryPublicController {
    private final ShareSummaryImageService shareSummaryImageService;
    private final ShareSummaryAudioService shareSummaryAudioService;

    public ShareSummaryPublicController(
            ShareSummaryImageService shareSummaryImageService,
            ShareSummaryAudioService shareSummaryAudioService
    ) {
        this.shareSummaryImageService = shareSummaryImageService;
        this.shareSummaryAudioService = shareSummaryAudioService;
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

    @GetMapping("/audios/{publicToken}.{ext}")
    public ResponseEntity<Resource> audio(@PathVariable String publicToken, @PathVariable String ext) {
        try {
            ShareSummaryAudioService.PublicAudio audio = shareSummaryAudioService.publicAudio(publicToken, ext);
            return ResponseEntity.ok()
                    .contentType(audio.mediaType())
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                    .body(audio.resource());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    private String reportHtml(ShareSummaryImageRecord image, ShareSummaryRunRecord run) {
        String title = image.getOgTitle();
        String description = image.getOgDescription();
        String imageUrl = image.getOgImageUrl();
        String pageUrl = image.getOgPageUrl();
        String audioUrl = audioUrl(run.getId());
        boolean hasAudio = StringUtils.hasText(audioUrl);
        String audioMeta = audioMeta(audioUrl);
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
                    %s
                    <meta name="twitter:card" content="summary_large_image">
                    <meta name="twitter:title" content="%s">
                    <meta name="twitter:description" content="%s">
                    <meta name="twitter:image" content="%s">
                    <link rel="icon" href="/favicon.ico">
                    <link rel="stylesheet" href="/dashboard/styles.css?v=20260612-report-topbar-polish">
                    <style>
                        .report-shell { max-width: 1180px; padding-bottom: 72px; }
                        .report-topbar { margin-bottom: 24px; }
                        .project-link { width: 48px; height: 48px; background: rgba(255, 255, 255, 0.9); box-shadow: 0 20px 42px rgba(18, 22, 28, 0.14); }
                        .project-link svg { width: 21px; height: 21px; }
                        .report-hero {
                            position: relative;
                            z-index: 1;
                            display: grid;
                            grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
                            gap: 26px;
                            align-items: stretch;
                            padding: 30px;
                            margin-bottom: 34px;
                            border-radius: 44px;
                            background: linear-gradient(145deg, rgba(255, 255, 255, 0.88), rgba(255, 255, 255, 0.58));
                            border: 1px solid rgba(255, 255, 255, 0.82);
                            box-shadow: var(--shadow);
                            backdrop-filter: blur(26px);
                            -webkit-backdrop-filter: blur(26px);
                        }
                        .report-visual { position: relative; min-height: 0; overflow: hidden; border-radius: 38px; box-shadow: var(--shadow-soft); }
                        .report-cover { display: block; width: 100%%; height: 100%%; min-height: 320px; aspect-ratio: 1200 / 630; object-fit: cover; background: rgba(255, 255, 255, 0.74); }
                        .report-visual::after {
                            content: "";
                            position: absolute;
                            inset: auto 0 0;
                            height: 34%%;
                            background: linear-gradient(180deg, transparent, rgba(17, 17, 17, 0.18));
                            pointer-events: none;
                        }
                        .report-copy { display: flex; flex-direction: column; min-width: 0; }
                        .report-copy h1 {
                            margin: 0;
                            font-size: clamp(34px, 5vw, 58px);
                            line-height: 1.02;
                            letter-spacing: -0.05em;
                            background: linear-gradient(120deg, rgba(24, 24, 24, 0.98) 0%%, rgba(24, 24, 24, 0.72) 32%%, rgba(10, 132, 255, 0.82) 70%%, rgba(249, 115, 22, 0.76) 100%%);
                            -webkit-background-clip: text;
                            background-clip: text;
                            color: transparent;
                            filter: drop-shadow(0 14px 30px rgba(10, 132, 255, 0.08));
                        }
                        .report-description { margin: 18px 0 4px; color: var(--muted); font-size: 16px; line-height: 1.75; }
                        .report-main { position: relative; z-index: 1; display: block; }
                        .reader {
                            margin: 18px 0 0;
                            padding: 16px;
                            border-radius: 24px;
                            background: linear-gradient(180deg, rgba(255, 255, 255, 0.82), rgba(255, 255, 255, 0.64));
                            border: 1px solid rgba(255, 255, 255, 0.86);
                            box-shadow: var(--shadow-soft);
                            backdrop-filter: blur(26px);
                            -webkit-backdrop-filter: blur(26px);
                        }
                        .reader[hidden] { display: none !important; }
                        .reader-main { display: grid; grid-template-columns: 44px minmax(0, 1fr); gap: 12px; align-items: center; }
                        .reader button { border: 0; font: inherit; cursor: pointer; position: relative; }
                        .reader button:disabled { cursor: not-allowed; opacity: 0.42; }
                        .reader-play { width: 44px; height: 44px; border-radius: 999px; background: linear-gradient(135deg, #111111, #363636); color: #fff; display: grid; place-items: center; box-shadow: 0 12px 24px rgba(17, 17, 17, 0.16); transition: transform 180ms ease, box-shadow 180ms ease; }
                        .reader-play:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 18px 30px rgba(17, 17, 17, 0.2); }
                        .reader-play::before { content: ""; display: block; width: 0; height: 0; margin-left: 3px; border-top: 7px solid transparent; border-bottom: 7px solid transparent; border-left: 11px solid currentColor; }
                        .reader-play.is-playing::before { width: 4px; height: 14px; margin-left: 0; border: 0; border-radius: 2px; background: currentColor; box-shadow: 8px 0 0 currentColor; transform: translateX(-4px); }
                        .reader-status { min-width: 0; color: var(--muted); font-size: 13px; line-height: 1.35; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                        .reader-progress { height: 5px; margin-top: 10px; border-radius: 999px; background: rgba(10, 132, 255, 0.12); overflow: hidden; }
                        .reader-progress-bar { display: block; width: 0%%; height: 100%%; border-radius: inherit; background: linear-gradient(90deg, var(--accent), var(--accent-3)); transition: width 180ms ease; }
                        .reader-settings { display: grid; grid-template-columns: minmax(128px, 0.7fr) minmax(220px, 1.3fr); gap: 14px; margin-top: 14px; align-items: end; }
                        .reader-control { display: grid; grid-template-columns: minmax(44px, auto) minmax(0, 1fr); gap: 8px; min-width: 0; align-items: center; color: var(--muted); font-size: 13px; }
                        .reader-control-label { display: block; line-height: 34px; white-space: nowrap; }
                        .reader-control-value { min-width: 0; display: grid; gap: 4px; }
                        .reader-control-meta { display: none; }
                        .reader input, .reader select { width: 100%%; min-height: 38px; border: 1px solid rgba(20, 20, 20, 0.08); border-radius: 16px; background: rgba(255, 255, 255, 0.86); color: var(--text); font: inherit; }
                        .reader input { accent-color: var(--accent); }
                        .reader select { padding: 0 12px; }
                        .report-content { line-height: 1.78; font-size: 16px; }
                        .report-content h2 { margin: 30px 0 12px; font-size: clamp(25px, 3vw, 34px); line-height: 1.18; letter-spacing: -0.04em; }
                        .report-content h2:first-child { margin-top: 0; }
                        .report-content h3 { margin: 24px 0 10px; font-size: 21px; line-height: 1.35; letter-spacing: -0.02em; }
                        .report-content p { margin: 0 0 15px; color: rgba(24, 24, 24, 0.82); }
                        .report-content ul, .report-content ol { margin: 0 0 18px 22px; padding: 0; color: rgba(24, 24, 24, 0.82); }
                        .report-content li { margin: 7px 0; }
                        .report-content a { color: var(--accent); text-decoration: none; border-bottom: 1px solid rgba(10, 132, 255, 0.24); }
                        .report-content a:hover { border-bottom-color: currentColor; }
                        .report-content pre { overflow-x: auto; margin: 0 0 18px; padding: 16px; border-radius: 20px; background: rgba(255, 255, 255, 0.72); border: 1px solid rgba(20, 20, 20, 0.06); }
                        .report-content code { padding: 2px 6px; border-radius: 8px; background: rgba(10, 132, 255, 0.1); }
                        .report-content pre code { padding: 0; background: transparent; }
                        @media (max-width: 860px) {
                            .report-hero { grid-template-columns: 1fr; border-radius: 34px; padding: 22px; }
                            .report-cover { height: auto; min-height: 0; }
                        }
                        @media (max-width: 520px) {
                            .page-shell { padding: 12px 10px 40px; }
                            .report-shell { padding-bottom: 40px; }
                            .topbar {
                                flex-direction: row;
                                align-items: center;
                                gap: 8px;
                                padding: 8px 10px;
                                border-radius: 18px;
                                margin-bottom: 14px;
                            }
                            .report-topbar .brand-mark { flex: 1 1 auto; gap: 8px; min-width: 0; overflow: visible; }
                            .report-topbar .topbar-meta { flex: 0 0 auto; min-width: 0; }
                            .report-topbar .brand-dot { flex: 0 0 7px; width: 7px; height: 7px; box-shadow: 0 0 0 6px rgba(10, 132, 255, 0.1); }
                            .report-topbar .brand-text { max-width: clamp(206px, 62vw, 320px); min-width: 0; font-size: 16px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                            .project-link { flex: 0 0 36px; width: 36px; min-width: 36px; height: 36px; aspect-ratio: 1; padding: 0; }
                            .project-link svg { width: 17px; height: 17px; }
                            .report-hero { padding: 12px; border-radius: 22px; gap: 14px; margin-bottom: 22px; }
                            .report-visual { border-radius: 18px; }
                            .report-copy h1 { font-size: 27px; line-height: 1.08; letter-spacing: -0.02em; }
                            .report-description { margin-top: 12px; font-size: 14px; line-height: 1.65; }
                            .report-content { padding: 14px; border-radius: 22px; font-size: 15px; line-height: 1.72; }
                            .report-content h2 { margin: 24px 0 10px; font-size: 23px; letter-spacing: -0.02em; }
                            .report-content h3 { margin: 20px 0 8px; font-size: 18px; letter-spacing: 0; }
                            .report-content ul, .report-content ol { margin-left: 18px; }
                            .reader { margin-top: 14px; padding: 12px; border-radius: 18px; }
                            .reader-main { grid-template-columns: 40px minmax(0, 1fr); gap: 10px; }
                            .reader-play { width: 40px; height: 40px; }
                            .reader-settings { grid-template-columns: 1fr; }
                            .reader-control { grid-template-columns: 40px minmax(0, 1fr); font-size: 12px; }
                        }
                        @media (max-width: 380px) {
                            .page-shell { padding-inline: 8px; }
                            .topbar { padding: 7px 8px; }
                            .report-topbar .brand-text { font-size: 15px; max-width: clamp(176px, 58vw, 260px); }
                            .project-link { flex-basis: 34px; width: 34px; min-width: 34px; height: 34px; }
                            .report-hero { padding: 10px; border-radius: 20px; }
                            .report-copy h1 { font-size: 25px; }
                            .report-description,
                            .report-content { font-size: 14px; }
                        }
                    </style>
                </head>
                <body>
                    <div class="page-shell report-shell">
                        <div class="backdrop-grid"></div>
                        <div class="backdrop-glow backdrop-glow-left"></div>
                        <div class="backdrop-glow backdrop-glow-right"></div>

                        <header class="topbar report-topbar">
                            <div class="brand-mark">
                                <span class="brand-dot"></span>
                                <span class="brand-text">LinkPeek Share Report</span>
                            </div>
                            <div class="topbar-meta">
                                <a
                                    class="icon-link project-link"
                                    href="https://github.com/shigella520/LinkPeek"
                                    target="_blank"
                                    rel="noreferrer"
                                    aria-label="打开 LinkPeek 项目"
                                    title="LinkPeek 项目"
                                >
                                    <svg viewBox="0 0 24 24" aria-hidden="true">
                                        <path d="M12 1.5C6.2 1.5 1.5 6.3 1.5 12.2c0 4.7 3 8.7 7.2 10.1.5.1.7-.2.7-.5v-1.9c-2.9.7-3.5-1.2-3.5-1.2-.5-1.2-1.1-1.5-1.1-1.5-.9-.6.1-.6.1-.6 1 .1 1.6 1 1.6 1 .9 1.5 2.3 1 2.8.8.1-.7.4-1 .6-1.3-2.3-.3-4.8-1.2-4.8-5.3 0-1.2.4-2.2 1-3-.1-.2-.4-1.3.1-2.8 0 0 .9-.3 3 .9a10.2 10.2 0 0 1 5.5 0c2.1-1.2 3-.9 3-.9.6 1.5.2 2.6.1 2.8.7.8 1 1.8 1 3 0 4.1-2.5 5-4.8 5.3.4.3.7.9.7 1.9v2.7c0 .3.2.7.7.5a10.7 10.7 0 0 0 7.2-10C22.5 6.3 17.8 1.5 12 1.5Z"/>
                                    </svg>
                                </a>
                            </div>
                        </header>

                        <section class="report-hero">
                            <div class="report-visual">
                                <img class="report-cover" src="%s" alt="%s">
                            </div>
                            <div class="report-copy">
                                <p class="eyebrow">Share Summary</p>
                                <h1 data-reader-title>%s</h1>
                                <p class="report-description" data-reader-description>%s</p>
                                <section class="reader reader-audio" data-audio-reader data-audio-src="%s" %s aria-label="报告音频播放">
                                    <audio data-audio-element preload="metadata" src="%s"></audio>
                                    <div class="reader-main">
                                        <button class="reader-play" type="button" data-audio-action="toggle" aria-label="播放"></button>
                                        <div>
                                            <div class="reader-status" data-audio-status>准备播放</div>
                                            <div class="reader-progress" aria-hidden="true">
                                                <span class="reader-progress-bar" data-audio-progress></span>
                                            </div>
                                        </div>
                                    </div>
                                </section>
                                <section class="reader reader-system" data-reader hidden aria-label="报告朗读">
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
                            </div>
                        </section>
                        <main class="report-main">
                            <article class="content-card report-content" data-reader-content>%s</article>
                        </main>
                    </div>
                    <script>
                        (() => {
                            const audioRoot = document.querySelector("[data-audio-reader]");
                            const systemRoot = document.querySelector("[data-reader]");
                            const audio = audioRoot ? audioRoot.querySelector("[data-audio-element]") : null;

                            function enableSystemReader() {
                                if (systemRoot) {
                                    systemRoot.dataset.readerFallback = "true";
                                    window.dispatchEvent(new CustomEvent("linkpeek:share-summary-system-reader"));
                                }
                            }

                            if (audioRoot && audio && audioRoot.dataset.audioSrc) {
                                const action = audioRoot.querySelector('[data-audio-action="toggle"]');
                                const status = audioRoot.querySelector("[data-audio-status]");
                                const progress = audioRoot.querySelector("[data-audio-progress]");
                                audioRoot.hidden = false;

                                function setAudioStatus(message, playing) {
                                    status.textContent = message;
                                    action.classList.toggle("is-playing", playing);
                                    action.setAttribute("aria-label", playing ? "暂停" : "播放");
                                }

                                function updateAudioProgress() {
                                    const duration = Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration : 0;
                                    const percent = duration ? Math.min(100, Math.round((audio.currentTime / duration) * 100)) : 0;
                                    progress.style.width = `${percent}%%`;
                                }

                                action.addEventListener("click", async () => {
                                    if (audio.paused) {
                                        try {
                                            await audio.play();
                                        } catch (error) {
                                            audioRoot.hidden = true;
                                            enableSystemReader();
                                        }
                                    } else {
                                        audio.pause();
                                    }
                                });
                                audio.addEventListener("play", () => setAudioStatus("正在播放", true));
                                audio.addEventListener("pause", () => setAudioStatus(audio.ended ? "播放完成" : "已暂停", false));
                                audio.addEventListener("ended", () => {
                                    updateAudioProgress();
                                    setAudioStatus("播放完成", false);
                                });
                                audio.addEventListener("timeupdate", updateAudioProgress);
                                audio.addEventListener("loadedmetadata", updateAudioProgress);
                                audio.addEventListener("error", () => {
                                    audioRoot.hidden = true;
                                    enableSystemReader();
                                });
                            } else {
                                enableSystemReader();
                            }
                        })();

                        (() => {
                            const root = document.querySelector("[data-reader]");
                            const content = document.querySelector("[data-reader-content]");
                            if (!root || !content || !("speechSynthesis" in window) || !("SpeechSynthesisUtterance" in window)) {
                                return;
                            }

                            const hasAudioReader = Boolean(document.querySelector("[data-audio-reader]:not([hidden])"));
                            const enableSystemReader = () => root.dataset.readerFallback === "true";
                            if (hasAudioReader && !enableSystemReader()) {
                                window.addEventListener("linkpeek:share-summary-system-reader", () => {
                                    root.hidden = false;
                                }, {once: true});
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

                            if (!hasAudioReader || enableSystemReader()) {
                                root.hidden = false;
                            }

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
                audioMeta,
                escapeAttribute(title),
                escapeAttribute(description),
                escapeAttribute(imageUrl),
                escapeAttribute(imageUrl),
                escapeAttribute(title),
                escapeHtml(title),
                escapeHtml(description),
                escapeAttribute(audioUrl),
                hasAudio ? "" : "hidden",
                escapeAttribute(audioUrl),
                ShareSummaryMarkdownRenderer.toHtml(report)
        );
    }

    private String audioMeta(String audioUrl) {
        if (!StringUtils.hasText(audioUrl)) {
            return "";
        }
        String escapedAudioUrl = escapeAttribute(audioUrl);
        return """
                <meta property="og:audio" content="%s">
                    <meta property="og:audio:secure_url" content="%s">
                    <meta property="og:audio:type" content="audio/mpeg">""".formatted(
                escapedAudioUrl,
                escapedAudioUrl
        );
    }

    private String audioUrl(long runId) {
        ShareSummaryAudioService.AudioSummary summary = shareSummaryAudioService.audioSummary(runId);
        return summary == null ? "" : summary.audioUrl();
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
