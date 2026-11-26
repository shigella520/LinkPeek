// Share summary report detail modal, image viewer and report rendering.
import {fetchJson} from "../../core/api.js";
import {escapeAttribute, escapeHtml, formatDuration, formatTimestamp, formatWindow} from "../../core/format.js";
import {state} from "../../core/state.js";
import {closeModal, openModal, setFeedback} from "../../core/ui.js";
import {
    copyShareSummaryUrl,
    generateShareSummaryAudio,
    generateShareSummaryImage,
    regenerateShareSummary,
    regenerationLabel,
    renderAudioStatus,
    renderImageStatus,
    renderRegenerationButton,
    renderRunStatus,
    renderShareSummaryAudioActionButton,
    renderShareSummaryImageActionButton,
    scheduleShareSummaryRefresh,
    shareSummaryBusy,
    shareSummaryOgShareUrl,
} from "./runs.js";

export function bindShareSummaryAssetButtons(root) {
    root.querySelectorAll("[data-regenerate-share-summary]").forEach(button => {
        button.addEventListener("click", () => {
            const run = state.shareSummaryRuns.items.find(item => String(item.id) === button.dataset.regenerateShareSummary);
            const windowLabel = run ? formatWindow(run.windowStart, run.windowEnd) : "本条报告的原统计窗口";
            if (window.confirm(`重新生成文字、图片和音频？\n统计窗口：${windowLabel}\n全部完成后更新原分享链接，完成前继续展示旧版。`)) {
                regenerateShareSummary(button.dataset.regenerateShareSummary);
            }
        });
    });
    root.querySelectorAll("[data-retry-share-version]").forEach(button => {
        button.addEventListener("click", () => regenerateShareSummary(button.dataset.rootRun, button.dataset.retryShareVersion));
    });
    root.querySelectorAll("[data-generate-share-image]").forEach((button) => {
        button.addEventListener("click", () => generateShareSummaryImage(button.dataset.generateShareImage, false));
    });
    root.querySelectorAll("[data-regenerate-share-image]").forEach((button) => {
        button.addEventListener("click", () => generateShareSummaryImage(button.dataset.regenerateShareImage, true));
    });
    root.querySelectorAll("[data-generate-share-audio]").forEach((button) => {
        button.addEventListener("click", () => generateShareSummaryAudio(button.dataset.generateShareAudio, false));
    });
    root.querySelectorAll("[data-regenerate-share-audio]").forEach((button) => {
        button.addEventListener("click", () => generateShareSummaryAudio(button.dataset.regenerateShareAudio, true));
    });
    root.querySelectorAll("[data-copy-url]").forEach((button) => {
        button.addEventListener("click", () => copyShareSummaryUrl(button.dataset.copyUrl));
    });
    root.querySelectorAll("[data-view-share-image]").forEach((button) => {
        button.addEventListener("click", () => {
            openShareSummaryImageViewer(button.dataset.viewShareImage, button.dataset.viewShareImageTitle || "分享图");
        });
    });
}

function openShareSummaryImageViewer(imageUrl, title) {
    if (!imageUrl) {
        return;
    }
    const viewer = document.getElementById("share-summary-image-viewer");
    const image = document.getElementById("share-summary-image-viewer-img");
    const titleNode = document.getElementById("share-summary-image-viewer-title");
    image.src = imageUrl;
    image.alt = title || "分享图";
    titleNode.textContent = title || "分享图";
    viewer.hidden = false;
    document.body.classList.add("modal-open");
    document.body.classList.add("share-summary-image-viewer-open");
    updateShareSummaryImageViewerLayout();
    document.getElementById("share-summary-image-viewer-close").focus({preventScroll: true});
}

export function closeShareSummaryImageViewer() {
    const viewer = document.getElementById("share-summary-image-viewer");
    const image = document.getElementById("share-summary-image-viewer-img");
    viewer.hidden = true;
    image.removeAttribute("src");
    image.alt = "";
    document.body.classList.remove("share-summary-image-viewer-open");
    if (document.querySelectorAll(".modal-shell:not([hidden]), .share-summary-image-viewer:not([hidden])").length === 0) {
        document.body.classList.remove("modal-open");
    }
}

export function updateShareSummaryImageViewerLayout() {
    const viewer = document.getElementById("share-summary-image-viewer");
    if (!viewer || viewer.hidden || !window.matchMedia("(max-width: 720px) and (orientation: portrait)").matches) {
        return;
    }
    const stage = viewer.querySelector(".share-summary-image-viewer-stage");
    if (!stage) {
        return;
    }
    const stageRect = stage.getBoundingClientRect();
    if (!stageRect.width || !stageRect.height) {
        return;
    }
    const imageWidth = Math.min(stageRect.height, stageRect.width * 1200 / 630);
    stage.style.setProperty("--share-summary-image-viewer-mobile-width", `${Math.floor(imageWidth)}px`);
}

export async function openShareSummaryRunDetail(runId) {
    const url = new URL(location.href);
    url.searchParams.set("report", runId);
    url.hash = "share-summary";
    history.replaceState(null, "", url);
    state.activeShareSummaryRunId = runId;
    state.shareSummaryDetailSignature = null;
    openModal("share-summary-run-modal");
    document.getElementById("share-summary-run-detail").innerHTML = `<p class="muted">正在读取详情...</p>`;
    setFeedback("share-summary-run-modal-feedback", "", "");
    try {
        await refreshShareSummaryRunDetail(runId);
        scheduleShareSummaryRefresh();
    } catch (error) {
        setFeedback("share-summary-run-modal-feedback", error.message, "is-error");
    }
}

export async function refreshShareSummaryRunDetail(runId) {
    const [run, versions] = await Promise.all([
        fetchJson(`/api/admin/share-summary/runs/${encodeURIComponent(runId)}`),
        fetchJson(`/api/admin/share-summary/runs/${encodeURIComponent(runId)}/versions`)
    ]);
    if (String(state.activeShareSummaryRunId) !== String(runId)) return;
    state.activeShareSummaryBusy = shareSummaryBusy(run);
    if (run.regenerationStatus === "SUCCESS") {
        setFeedback("share-summary-run-modal-feedback", "整体重新生成已完成，原分享链接已更新。", "is-success");
    }
    const signature = JSON.stringify([run, versions]);
    if (signature === state.shareSummaryDetailSignature) return;
    state.shareSummaryDetailSignature = signature;
    const current = versions.find(version => Number(version.run.id) === Number(run.publishedRunId || run.id));
    const detail = document.getElementById("share-summary-run-detail");
    const audio = detail.querySelector("audio");
    const playback = audio ? {src: audio.getAttribute("src"), time: audio.currentTime, paused: audio.paused, rate: audio.playbackRate} : null;
    // Preserve expanded history across progress refreshes.
    const expanded = new Set(Array.from(detail.querySelectorAll("details[data-version-id][open]")).map(node => node.dataset.versionId));
    document.getElementById("share-summary-run-modal-title").textContent = `分享总结详情：${run.taskName || run.id}`;
    detail.innerHTML = renderShareSummaryRunDetail(run, current?.images || [], current?.audios || [], versions)
        + renderShareSummaryVersions(run, versions);
    detail.querySelectorAll("details[data-version-id]").forEach(node => { node.open = expanded.has(node.dataset.versionId); });
    bindShareSummaryAssetButtons(detail);
    restoreReportPlayback(detail.querySelector("audio"), playback);
}

export function restoreReportPlayback(audio, playback) {
    if (!audio || !playback || audio.getAttribute("src") !== playback.src) return;
    const restore = () => {
        audio.playbackRate = playback.rate;
        if (Number.isFinite(playback.time) && playback.time >= 0) {
            audio.currentTime = Number.isFinite(audio.duration) ? Math.min(playback.time, audio.duration) : playback.time;
        }
        if (!playback.paused) audio.play()?.catch(() => {});
    };
    if (audio.readyState >= 1) restore();
    else audio.addEventListener("loadedmetadata", restore, {once: true});
}

function renderShareSummaryProgress(run, versions) {
    const latest = versions.find(version => version.revision);
    const progress = latest ? `<p>最近一次整体生成：${escapeHtml(regenerationLabel(latest.revision.status))}</p>
        <p>文字：${renderRunStatus(latest.run.status)} · 图片：${renderImageStatus(latest.run.imageStatus)} · 音频：${renderAudioStatus(latest.run.audioStatus)}</p>
        ${latest.revision.errorMessage ? `<p class="is-error">${escapeHtml(latest.revision.errorMessage)}</p>` : ""}
        ${["FAILED", "PARTIAL_FAILED"].includes(latest.revision.status) ? `<button type="button" data-retry-share-version="${escapeAttribute(latest.run.id)}" data-root-run="${escapeAttribute(run.id)}" ${shareSummaryBusy(run) ? "disabled" : ""}>重试未完成部分</button>` : ""}` : "";
    return progress;
}

function renderShareSummaryVersions(run, versions) {
    return `<section class="summary-detail-section"><h4>版本历史</h4>
        ${versions.map(version => `<details data-version-id="${escapeAttribute(version.run.id)}">
            <summary>${version.revision ? `版本 #${escapeHtml(version.run.id)}` : "初始版本"} · ${escapeHtml(formatTimestamp(version.revision?.createdAt || version.run.startedAt))} · ${version.published ? "当前展示" : escapeHtml(regenerationLabel(version.revision?.status) || version.run.status)}</summary>
            ${version.run.errorMessage ? `<p>${escapeHtml(version.run.errorMessage)}</p>` : ""}
            <article class="summary-report">${renderSummaryReport(version.run.report || "尚无文字内容")}</article>
            ${version.images.filter(image => image.status === "SUCCESS").map(image => `<p><a href="${escapeAttribute(image.ogImageUrl)}" target="_blank" rel="noopener">查看图片 #${escapeHtml(image.id)}</a></p>`).join("")}
            ${version.audios.filter(audio => audio.status === "SUCCESS").map(audio => `<p><a href="${escapeAttribute(audio.audioUrl)}" target="_blank" rel="noopener">收听音频 #${escapeHtml(audio.id)}</a></p>`).join("")}
        </details>`).join("")}</section>`;
}

export function closeShareSummaryRunModal() {
    const url = new URL(location.href); url.searchParams.delete("report"); history.replaceState(null, "", url);
    closeModal("share-summary-run-modal");
    state.activeShareSummaryRunId = null;
    state.activeShareSummaryBusy = false;
    setFeedback("share-summary-run-modal-feedback", "", "");
}

function renderShareSummaryRunDetail(run, images = [], audios = [], versions = []) {
    return `
        <section class="summary-detail-section">
            <h4>报告预览</h4>
            <article class="summary-report summary-report-main summary-rendered">${run.reportHtml || renderSummaryReport(run.report || "尚无报告内容")}</article>
            <details><summary>查看原始 Markdown</summary><pre class="summary-report">${escapeHtml(run.report || "")}</pre></details>
        </section>
        <section class="summary-detail-section">${renderShareSummaryProgress(run, versions)}</section>
        <details class="summary-detail-section"><summary>重新生成与版本说明</summary>
            <div class="row-actions">${renderRegenerationButton(run)}</div>
            <p class="muted">沿用本条总结的时间范围，按当前配置重新生成文字、图片和音频。全部成功后原分享链接统一更新，旧版本保留。</p>
        </details>
        <div class="summary-detail-grid">
            <div><b>文字状态</b><span>${renderRunStatus(run.status)}</span></div>
            <div><b>触发方式</b><span>${run.triggerType === "MANUAL" ? "手动执行" : "定时执行"}</span></div>
            <div><b>窗口</b><span>${escapeHtml(formatWindow(run.windowStart, run.windowEnd))}</span></div>
            <div><b>链接</b><span>${escapeHtml(run.inputLinkCount || 0)}</span></div>
            <div><b>AI Provider</b><span>${escapeHtml(run.aiProviderNames || "-")}</span></div>
            <div><b>AI 耗时</b><span>${escapeHtml(formatDuration(run.aiDurationMs))}</span></div>
        </div>
        <section class="summary-detail-section">
            <div class="section-title-row">
                <h4>分享图</h4>
                <div class="row-actions">
                    ${renderShareSummaryImageActionButton(run)}
                    <button type="button" class="secondary" data-copy-url="${escapeAttribute(shareSummaryOgShareUrl(run))}" ${shareSummaryOgShareUrl(run) ? "" : "disabled"}>复制分享链接</button>
                    <button type="button" class="secondary" data-copy-url="${escapeAttribute(run.ogImageUrl || "")}" ${run.ogImageUrl ? "" : "disabled"}>复制图片链接</button>
                </div>
            </div>
            ${renderShareSummaryImageDetail(run, images)}
        </section>
        <section class="summary-detail-section">
            <div class="section-title-row">
                <h4>语音文件</h4>
                <div class="row-actions">
                    ${renderShareSummaryAudioActionButton(run)}
                    <button type="button" class="secondary" data-copy-url="${escapeAttribute(run.audioUrl || "")}" ${run.audioUrl ? "" : "disabled"}>复制音频链接</button>
                </div>
            </div>
            ${renderShareSummaryAudioDetail(run, audios)}
        </section>
        ${run.errorMessage ? `<section class="summary-detail-section"><h4>错误信息</h4><pre class="summary-report is-error">${escapeHtml(run.errorMessage)}</pre></section>` : ""}
        <details class="summary-detail-section">
            <summary>提示词快照</summary>
            <pre class="summary-report summary-report-prompt">${escapeHtml(run.promptSnapshot || "")}</pre>
        </details>
    `;
}

function renderShareSummaryImageDetail(run, images) {
    const attempts = Array.isArray(images) ? images : [];
    const imageUrl = run.latestImageUrl || "";
    const title = run.ogTitle || "分享图";
    const preview = run.latestImageUrl
            ? `
                <button type="button" class="share-summary-preview-button" data-view-share-image="${escapeAttribute(imageUrl)}" data-view-share-image-title="${escapeAttribute(title)}" aria-label="查看分享图">
                    <img class="share-summary-preview" src="${escapeAttribute(imageUrl)}" alt="">
                </button>
            `
            : `<div class="share-summary-preview-placeholder">暂无分享图</div>`;
    const meta = `
        <div class="summary-detail-grid">
            <div><b>图片状态</b><span>${renderImageStatus(run.imageStatus || "NOT_GENERATED")}</span></div>
            <div><b>OG 标题</b><span>${escapeHtml(run.ogTitle || "-")}</span></div>
            <div><b>OG 描述</b><span>${escapeHtml(run.ogDescription || "-")}</span></div>
            <div><b>OG 分享链接</b><span class="url-cell">${escapeHtml(shareSummaryOgShareUrl(run) || "-")}</span></div>
            <div><b>图片直链</b><span class="url-cell">${escapeHtml(run.ogImageUrl || "-")}</span></div>
            <div><b>错误</b><span>${escapeHtml(run.imageErrorMessage || "-")}</span></div>
        </div>
    `;
    const rows = attempts.length ? attempts.map((image) => `
        <tr>
            <td>${escapeHtml(image.attemptNo || "-")}</td>
            <td>${renderImageStatus(image.status)}</td>
            <td>${escapeHtml(image.model || "-")}<div class="keyline">${escapeHtml(image.imageSize || "-")} · ${escapeHtml(image.quality || "-")}</div></td>
            <td>${escapeHtml(formatDuration(image.durationMs))}</td>
            <td>${escapeHtml(formatTimestamp(image.createdAt))}</td>
            <td>${escapeHtml(image.errorMessage || "-")}</td>
        </tr>
    `).join("") : `<tr><td colspan="6" class="muted">暂无生成记录</td></tr>`;
    return `
        <div class="share-summary-image-detail">
            ${preview}
            ${meta}
        </div>
        <div class="table-shell">
            <table class="share-summary-image-attempt-table">
                <thead>
                <tr>
                    <th>次数</th>
                    <th>状态</th>
                    <th>模型</th>
                    <th>耗时</th>
                    <th>创建时间</th>
                    <th>错误</th>
                </tr>
                </thead>
                <tbody>${rows}</tbody>
            </table>
        </div>
    `;
}

function renderShareSummaryAudioDetail(run, audios) {
    const attempts = Array.isArray(audios) ? audios : [];
    const player = run.audioUrl
            ? `<audio class="share-summary-audio-preview" controls preload="metadata" src="${escapeAttribute(run.audioUrl)}"></audio>`
            : `<div class="share-summary-preview-placeholder">暂无音频文件</div>`;
    const meta = `
        <div class="summary-detail-grid">
            <div><b>音频状态</b><span>${renderAudioStatus(run.audioStatus || "NOT_GENERATED")}</span></div>
            <div><b>播放次数</b><span>${escapeHtml(run.audioPlayCount || 0)}</span></div>
            <div><b>音频链接</b><span class="url-cell">${escapeHtml(run.audioUrl || "-")}</span></div>
            <div><b>错误</b><span>${escapeHtml(run.audioErrorMessage || "-")}</span></div>
        </div>
    `;
    const rows = attempts.length ? attempts.map((audio) => `
        <tr>
            <td>${escapeHtml(audio.attemptNo || "-")}</td>
            <td>${renderAudioStatus(audio.status)}</td>
            <td>${escapeHtml(audio.voice || "-")}<div class="keyline">${escapeHtml(audioMetaLine(audio))}</div></td>
            <td>${escapeHtml(audio.playCount || 0)}</td>
            <td>${escapeHtml(formatDuration(audio.durationMs))}</td>
            <td>${escapeHtml(formatTimestamp(audio.createdAt))}</td>
            <td>${escapeHtml(audio.errorMessage || "-")}</td>
        </tr>
    `).join("") : `<tr><td colspan="7" class="muted">暂无生成记录</td></tr>`;
    return `
        <div class="share-summary-image-detail">
            ${player}
            ${meta}
        </div>
        <div class="table-shell">
            <table class="share-summary-image-attempt-table">
                <thead>
                <tr>
                    <th>次数</th>
                    <th>状态</th>
                    <th>语音</th>
                    <th>播放</th>
                    <th>耗时</th>
                    <th>创建时间</th>
                    <th>错误</th>
                </tr>
                </thead>
                <tbody>${rows}</tbody>
            </table>
        </div>
    `;
}

function audioMetaLine(audio) {
    const model = audio.model || "无 model";
    const style = audio.style || "-";
    if (isMimoAudioRecord(audio)) {
        return `${model} · ${style}`;
    }
    return `${model} · ${audio.speed || "-"}x · pitch ${audio.pitch ?? "-"} · ${style}`;
}

function isMimoAudioRecord(audio) {
    const providerType = (audio.providerType || "").toUpperCase();
    const model = (audio.model || "").toLowerCase();
    return providerType === "MIMO_TTS" || model.startsWith("mimo-");
}

function renderSummaryReport(value) {
    return linkifySummaryText(escapeHtml(value));
}

function linkifySummaryText(value) {
    let rendered = value.replace(/\[([^\]]+)]\((https?:\/\/[^\s)]+)\)/g, (_match, label, url) => linkHtml(url, label));
    rendered = rendered
        .split(/(<a\b[^>]*>.*?<\/a>)/g)
        .map((part) => part.startsWith("<a ") ? part : linkifyPlainUrls(part))
        .join("");
    return rendered;
}

function linkifyPlainUrls(value) {
    return value.replace(/(?<!["'=])(https?:\/\/[^\s<]+)/g, (match) => {
        const url = stripTrailingUrlPunctuation(match);
        return `${linkHtml(url, url)}${match.slice(url.length)}`;
    });
}

function stripTrailingUrlPunctuation(value) {
    return value.replace(/[.,;:!?，。；：！？]+$/g, "");
}

function linkHtml(escapedUrl, escapedLabel) {
    return `<a href="${escapedUrl}" target="_blank" rel="noreferrer">${escapedLabel}</a>`;
}
