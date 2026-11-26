// Preview request event log.
import {fetchJson} from "../core/api.js";
import {escapeAttribute, escapeHtml, formatDuration, formatTimestamp, formatTokenUsage} from "../core/format.js";
import {state} from "../core/state.js";
import {confirmDangerAction, resetDangerAction, setFeedback} from "../core/ui.js";

export function bindPreviewEvents() {
    document.getElementById("preview-event-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        state.previewEvents.page = 1;
        await loadPreviewEvents();
    });
    document.getElementById("preview-event-size").addEventListener("change", async () => {
        state.previewEvents.page = 1;
        await loadPreviewEvents();
    });
    document.getElementById("preview-event-prev").addEventListener("click", async () => {
        if (state.previewEvents.page <= 1) {
            return;
        }
        state.previewEvents.page -= 1;
        await loadPreviewEvents();
    });
    document.getElementById("preview-event-next").addEventListener("click", async () => {
        if (state.previewEvents.totalPages > 0 && state.previewEvents.page >= state.previewEvents.totalPages) {
            return;
        }
        state.previewEvents.page += 1;
        await loadPreviewEvents();
    });
}

export async function loadPreviewEvents() {
    const request = (state.requests.loadPreviewEvents || 0) + 1;
    state.requests.loadPreviewEvents = request;
    const params = new URLSearchParams();
    params.set("page", String(state.previewEvents.page || 1));
    params.set("size", document.getElementById("preview-event-size").value || "20");
    const query = document.getElementById("preview-event-query").value.trim();
    if (query) {
        params.set("q", query);
    }

    setFeedback("preview-event-feedback", "正在读取链接创建记录...", "");
    try {
        const result = await fetchJson(`/api/admin/preview-events?${params.toString()}`);
        if (state.requests.loadPreviewEvents !== request) return;
        state.previewEvents = result;
        renderPreviewEvents();
        setFeedback("preview-event-feedback", `已加载 ${state.previewEvents.items.length} 条，共 ${state.previewEvents.total} 条。`, "is-success");
    } catch (error) {
        if (state.requests.loadPreviewEvents !== request) return;
        setFeedback("preview-event-feedback", error.message, "is-error");
    }
}

function renderPreviewEvents() {
    const payload = state.previewEvents || {};
    const items = Array.isArray(payload.items) ? payload.items : [];
    const body = document.getElementById("preview-event-table");
    if (!items.length) {
        body.innerHTML = `<tr><td colspan="6" class="muted">暂无链接创建记录</td></tr>`;
    } else {
        body.innerHTML = items.map((item) => `
            <tr>
                <td class="nowrap" data-label="创建时间">${escapeHtml(formatTimestamp(item.occurredAt))}</td>
                <td>
                    <a class="url-cell" href="${escapeAttribute(item.sourceUrl || item.canonicalUrl)}" target="_blank" rel="noreferrer" title="${escapeAttribute(item.sourceUrl || item.canonicalUrl || "-")}">${escapeHtml(item.sourceUrl || item.canonicalUrl || "-")}</a>
                    ${renderMetadataTitle(item)}
                    <div class="keyline">${escapeHtml(shortPreviewKey(item.previewKey))}</div>
                </td>
                <td>${escapeHtml(item.providerId || "-")}</td>
                <td>${renderAiStyleDetails(item)}</td>
                <td>${renderDurationDetails(item)}</td>
                <td>${renderCacheState(item)}</td>
            </tr>
        `).join("");
    }
    bindPreviewEventActions(body);
    renderPreviewEventPagination(payload);
}

function bindPreviewEventActions(body) {
    body.querySelectorAll("[data-clear-preview-cache]").forEach((button) => {
        button.addEventListener("click", async () => {
            const previewKey = button.dataset.clearPreviewCache;
            if (!previewKey || !confirmDangerAction(button, "确认清理")) {
                return;
            }
            button.disabled = true;
            setFeedback("preview-event-feedback", "正在清理缓存...", "");
            try {
                const result = await fetchJson(`/api/admin/preview-events/${encodeURIComponent(previewKey)}/cache`, {
                    method: "DELETE"
                });
                await loadPreviewEvents();
                setFeedback("preview-event-feedback", `已清理 ${result.deletedFiles || 0} 个缓存文件。`, "is-success");
            } catch (error) {
                resetDangerAction(button);
                button.disabled = false;
                setFeedback("preview-event-feedback", error.message, "is-error");
            }
        });
    });
}

function renderPreviewEventPagination(payload) {
    const page = Number(payload.page || 1);
    const totalPages = Number(payload.totalPages || 0);
    const total = Number(payload.total || 0);
    document.getElementById("preview-event-page-info").textContent = totalPages > 0
            ? `第 ${page} / ${totalPages} 页 · ${total} 条`
            : "暂无记录";
    document.getElementById("preview-event-prev").disabled = page <= 1;
    document.getElementById("preview-event-next").disabled = totalPages === 0 || page >= totalPages;
}

export function shortPreviewKey(value) {
    const key = String(value || "");
    if (key.length <= 16) {
        return key;
    }
    return `${key.slice(0, 8)}...${key.slice(-8)}`;
}

function renderAiState(item) {
    if (item.aiSucceeded) {
        return `<span class="status-pill is-success">已渲染</span>`;
    }
    if (item.aiRequested) {
        return `<span class="status-pill is-warning">失败</span>`;
    }
    return `<span class="status-pill">未使用</span>`;
}

function renderAiStyleDetails(item) {
    return `
        <div class="cell-stack">
            ${renderAiState(item)}
            <span><b>Style</b>${escapeHtml(stylePair(item))}</span>
            <span><b>Provider</b>${escapeHtml(item.aiProviderNames || "-")}</span>
        </div>
    `;
}

function stylePair(item) {
    const requested = item.requestedStyle || "-";
    const actual = item.actualStyle || "-";
    if (requested === actual) {
        return requested;
    }
    return `${requested} -> ${actual}`;
}

function renderDurationDetails(item) {
    return `
        <div class="cell-stack cell-stack-compact">
            <span><b>AI</b>${formatDuration(item.aiDurationMs)}</span>
            <span><b>爬取</b>${formatDuration(item.crawlDurationMs)}</span>
            <span><b>整体</b>${formatDuration(item.durationMs)}</span>
            ${item.aiRequested ? `<span><b>Token</b>${escapeHtml(formatTokenUsage(item.aiInputTokens, item.aiOutputTokens))}</span>` : ""}
        </div>
    `;
}

function renderCacheState(item) {
    const states = [];
    states.push(cacheBadge("Meta", item.metadataCached));
    states.push(cacheBadge("Thumb", item.thumbnailCached));
    if (item.videoCached) {
        states.push(cacheBadge("Video", true));
    }
    states.push(cacheBadge("Hit", item.cacheHit));
    return `
        <div class="cache-cell">
            <div class="cache-badges">${states.join("")}</div>
            <button type="button" class="danger cache-clear-button" data-clear-preview-cache="${escapeHtml(item.previewKey || "")}" ${item.previewKey ? "" : "disabled"}>清理缓存</button>
        </div>
    `;
}

function cacheBadge(label, enabled) {
    return `<span class="cache-badge ${enabled ? "is-on" : ""}">${escapeHtml(label)}</span>`;
}

function renderMetadataTitle(item) {
    const title = String(item.metadataTitle || "").trim();
    if (!title) {
        return "";
    }
    return `<div class="metadata-title" title="${escapeAttribute(title)}">${escapeHtml(title)}</div>`;
}
