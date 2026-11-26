// Share summary report list: statuses, actions, regeneration and asset generation.
import {fetchJson} from "../../core/api.js";
import {escapeAttribute, escapeHtml, formatShortTimestamp, formatTimestamp} from "../../core/format.js";
import {state} from "../../core/state.js";
import {confirmDangerAction, resetDangerAction, setFeedback} from "../../core/ui.js";
import {
    closeShareSummaryAudioConfigModal,
    closeShareSummaryImageConfigModal,
    loadShareSummaryAudioConfig,
    loadShareSummaryImageConfig,
    openShareSummaryAudioConfigModal,
    openShareSummaryImageConfigModal,
    saveShareSummaryAudioConfig,
    saveShareSummaryImageConfig,
    testShareSummaryAudioConfig,
    updateShareSummaryAudioProviderFields,
} from "./asset-config.js";
import {
    bindShareSummaryAssetButtons,
    closeShareSummaryImageViewer,
    closeShareSummaryRunModal,
    openShareSummaryRunDetail,
    refreshShareSummaryRunDetail,
    updateShareSummaryImageViewerLayout,
} from "./run-detail.js";
import {
    closeShareSummaryTaskModal,
    loadShareSummaryTasks,
    openShareSummaryTaskModalForCreate,
    saveShareSummaryTask,
    updateShareSummaryPeriodFields,
} from "./tasks.js";

export function bindShareSummary() {
    document.querySelectorAll("[data-summary-view]").forEach(button => {
        button.addEventListener("click", () => setSummaryView(button.dataset.summaryView));
    });
    setSummaryView(new URLSearchParams(location.search).get("summaryView") || "reports", false);
    document.getElementById("share-summary-new-button").addEventListener("click", openShareSummaryTaskModalForCreate);
    document.getElementById("share-summary-image-config-button").addEventListener("click", openShareSummaryImageConfigModal);
    document.getElementById("share-summary-image-config-cancel-button").addEventListener("click", closeShareSummaryImageConfigModal);
    document.getElementById("share-summary-image-config-form").addEventListener("submit", saveShareSummaryImageConfig);
    document.getElementById("share-summary-audio-config-button").addEventListener("click", openShareSummaryAudioConfigModal);
    document.getElementById("share-summary-audio-config-cancel-button").addEventListener("click", closeShareSummaryAudioConfigModal);
    document.getElementById("share-summary-audio-config-test-button").addEventListener("click", testShareSummaryAudioConfig);
    document.getElementById("share-summary-audio-config-form").addEventListener("submit", saveShareSummaryAudioConfig);
    document.getElementById("share-summary-audio-provider-type").addEventListener("change", () => updateShareSummaryAudioProviderFields(true));
    document.getElementById("share-summary-task-cancel-button").addEventListener("click", closeShareSummaryTaskModal);
    document.getElementById("share-summary-run-cancel-button").addEventListener("click", closeShareSummaryRunModal);
    document.getElementById("share-summary-task-period").addEventListener("change", updateShareSummaryPeriodFields);
    document.getElementById("share-summary-task-period-selection-mode").addEventListener("change", updateShareSummaryPeriodFields);
    document.getElementById("share-summary-task-run-time").addEventListener("input", updateShareSummaryPeriodFields);
    document.getElementById("share-summary-task-day-of-week").addEventListener("change", updateShareSummaryPeriodFields);
    document.getElementById("share-summary-task-form").addEventListener("submit", saveShareSummaryTask);
    document.getElementById("share-summary-refresh-runs").addEventListener("click", async () => {
        await loadShareSummaryRuns();
    });
    document.getElementById("share-summary-run-filter-form").addEventListener("change", async () => {
        state.shareSummaryRuns.page = 1;
        await loadShareSummaryRuns();
    });
    document.getElementById("share-summary-run-prev").addEventListener("click", async () => {
        if (state.shareSummaryRuns.page <= 1) {
            return;
        }
        state.shareSummaryRuns.page -= 1;
        await loadShareSummaryRuns();
    });
    document.getElementById("share-summary-run-next").addEventListener("click", async () => {
        if (state.shareSummaryRuns.totalPages > 0 && state.shareSummaryRuns.page >= state.shareSummaryRuns.totalPages) {
            return;
        }
        state.shareSummaryRuns.page += 1;
        await loadShareSummaryRuns();
    });
    document.getElementById("share-summary-image-viewer-close").addEventListener("click", closeShareSummaryImageViewer);
    document.getElementById("share-summary-image-viewer").addEventListener("click", (event) => {
        if (event.target === event.currentTarget) {
            closeShareSummaryImageViewer();
        }
    });
    document.getElementById("share-summary-image-viewer-img").addEventListener("load", updateShareSummaryImageViewerLayout);
    window.addEventListener("resize", updateShareSummaryImageViewerLayout);
}

function setSummaryView(view, updateUrl = true) {
    const selected = view === "tasks" ? "tasks" : "reports";
    for (const name of ["reports", "tasks"]) {
        document.getElementById(`summary-${name}-panel`).hidden = selected !== name;
        document.getElementById(`summary-${name}-tab`).setAttribute("aria-selected", String(selected === name));
    }
    if (updateUrl) {
        const url = new URL(location.href);
        url.searchParams.set("summaryView", selected);
        history.replaceState(null, "", url);
    }
}

export async function loadShareSummary() {
    await loadShareSummaryImageConfig();
    await loadShareSummaryAudioConfig();
    await loadShareSummaryTasks();
    await loadShareSummaryRuns();
}

export async function loadShareSummaryRuns() {
    const request = (state.requests.loadShareSummaryRuns || 0) + 1;
    state.requests.loadShareSummaryRuns = request;
    const params = new URLSearchParams();
    params.set("page", String(state.shareSummaryRuns.page || 1));
    params.set("size", document.getElementById("share-summary-run-size").value || "20");
    const taskId = document.getElementById("share-summary-run-filter-task").value;
    const status = document.getElementById("share-summary-run-filter-status").value;
    const triggerType = document.getElementById("share-summary-run-filter-trigger").value;
    if (taskId) {
        params.set("taskId", taskId);
    }
    if (status) {
        params.set("status", status);
    }
    if (triggerType) {
        params.set("triggerType", triggerType);
    }

    setFeedback("share-summary-history-feedback", "正在读取分享总结记录...", "");
    try {
        const result = await fetchJson(`/api/admin/share-summary/runs?${params.toString()}`);
        if (state.requests.loadShareSummaryRuns !== request) return;
        state.shareSummaryRuns = result;
        scheduleShareSummaryRefresh();
        renderShareSummaryRuns();
        setFeedback("share-summary-history-feedback", `已加载 ${state.shareSummaryRuns.items.length} 条，共 ${state.shareSummaryRuns.total} 条。`, "is-success");
    } catch (error) {
        if (state.requests.loadShareSummaryRuns !== request) return;
        setFeedback("share-summary-history-feedback", error.message, "is-error");
    }
}

function renderShareSummaryRuns() {
    const payload = state.shareSummaryRuns || {};
    const items = Array.isArray(payload.items) ? payload.items : [];
    const body = document.getElementById("share-summary-run-table");
    if (!items.length) {
        body.innerHTML = `<tr><td colspan="4" class="muted">暂无分享总结记录</td></tr>`;
    } else {
        body.innerHTML = items.map(run => `
            <tr class="share-summary-run-row">
                <td data-label="报告"><strong>${escapeHtml(run.ogTitle || run.taskName || "分享总结")}</strong>
                    <div class="keyline">${escapeHtml(run.taskName || "")} · ${run.triggerType === "MANUAL" ? "手动" : "定时"} · ${escapeHtml(run.inputLinkCount || 0)} 条链接</div>
                    <div class="keyline">${escapeHtml(formatTimestamp(run.startedAt))}</div></td>
                <td data-label="统计窗口" class="summary-window-cell">${renderSummaryWindow(run.windowStart, run.windowEnd)}</td>
                <td data-label="生成状态"><div class="summary-stage-list">
                    <span>文字 ${renderRunStatus(run.status)}</span>
                    <span>图片 ${renderImageStatus(run.imageStatus)}</span>
                    <span>音频 ${renderAudioStatus(run.audioStatus)}</span>
                    ${run.regenerationStatus ? `<span>整体生成：${escapeHtml(regenerationLabel(run.regenerationStatus))}</span>` : ""}
                </div>${renderRunErrorHint(run.errorMessage)}</td>
                <td data-label="操作" class="share-summary-run-action-cell">${renderShareSummaryRunActions(run)}</td>
            </tr>`).join("");
    }
    body.querySelectorAll("[data-view-share-run]").forEach((button) => {
        button.addEventListener("click", () => openShareSummaryRunDetail(button.dataset.viewShareRun));
    });
    body.querySelectorAll("[data-delete-share-run]").forEach((button) => {
        button.addEventListener("click", async () => deleteShareSummaryRun(button));
    });
    bindShareSummaryAssetButtons(body);
    renderShareSummaryRunPagination(payload);
}

async function deleteShareSummaryRun(button) {
    if (!confirmDangerAction(button, "确认删除")) {
        return;
    }
    const runId = button.dataset.deleteShareRun;
    button.disabled = true;
    setFeedback("share-summary-history-feedback", "正在删除分享总结记录...", "");
    try {
        await fetchJson(`/api/admin/share-summary/runs/${encodeURIComponent(runId)}`, {method: "DELETE"});
        if (state.activeShareSummaryRunId && String(state.activeShareSummaryRunId) === String(runId)) {
            closeShareSummaryRunModal(false);
        }
        await reloadShareSummaryRunsAfterDelete();
        setFeedback("share-summary-history-feedback", "分享总结记录已删除。", "is-success");
    } catch (error) {
        resetDangerAction(button);
        button.disabled = false;
        setFeedback("share-summary-history-feedback", error.message, "is-error");
    }
}

async function reloadShareSummaryRunsAfterDelete() {
    const remainingItems = Math.max(0, (state.shareSummaryRuns.items || []).length - 1);
    if (remainingItems === 0 && state.shareSummaryRuns.page > 1) {
        state.shareSummaryRuns.page -= 1;
    }
    await loadShareSummaryRuns();
}

export function shareSummaryBusy(run) {
    return ["QUEUED", "TEXT", "ASSETS"].includes(run.regenerationStatus)
        || run.status === "RUNNING" || ["PENDING", "GENERATING"].includes(run.imageStatus)
        || ["PENDING", "GENERATING"].includes(run.audioStatus);
}

export function regenerationLabel(status) {
    return {QUEUED: "排队中", TEXT: "生成文字中", ASSETS: "生成图片和音频中", SUCCESS: "已完成",
        PARTIAL_FAILED: "素材生成失败", FAILED: "生成失败", EMPTY: "无可生成内容"}[status] || status || "";
}

export function renderRegenerationButton(run) {
    return `<button type="button" data-regenerate-share-summary="${escapeAttribute(run.id)}" ${shareSummaryBusy(run) ? "disabled" : ""}>重新生成分享总结</button>`;
}

export function renderShareSummaryRunActions(run) {
    const canGenerate = run.status === "SUCCESS";
    const hasImage = Boolean(run.ogImageUrl);
    const hasAudio = Boolean(run.audioUrl);
    return `<div class="row-actions share-summary-run-actions">
        <button type="button" class="secondary" data-view-share-run="${escapeAttribute(run.id)}">查看</button>
        <details class="summary-action-menu"><summary>分享</summary><div class="summary-action-options">
            <button type="button" class="secondary" data-copy-url="${escapeAttribute(shareSummaryOgShareUrl(run))}" ${shareSummaryOgShareUrl(run) ? "" : "disabled"}>复制分享链接</button>
            <button type="button" class="secondary" data-copy-url="${escapeAttribute(run.ogImageUrl || "")}" ${hasImage ? "" : "disabled"}>复制图片链接</button>
            <button type="button" class="secondary" data-copy-url="${escapeAttribute(run.audioUrl || "")}" ${hasAudio ? "" : "disabled"}>复制音频链接</button>
            ${!hasImage && !hasAudio ? '<span class="keyline">素材生成后可分享</span>' : ''}
        </div></details>
        <details class="summary-action-menu"><summary>更多</summary><div class="summary-action-options">
            ${renderRegenerationButton(run)}
            ${renderShareSummaryImageActionButton(run, canGenerate, hasImage)}
            ${renderShareSummaryAudioActionButton(run, canGenerate, hasAudio)}
            <button type="button" class="danger" data-delete-share-run="${escapeAttribute(run.id)}" ${shareSummaryBusy(run) ? "disabled" : ""}>删除报告</button>
        </div></details>
    </div>`;
}

export function renderShareSummaryImageActionButton(run, canGenerate = run.status === "SUCCESS", hasImage = Boolean(run.ogImageUrl)) {
    const imageActionAttribute = hasImage ? "data-regenerate-share-image" : "data-generate-share-image";
    const imageActionLabel = hasImage ? "重新生成图片" : "生成图片";
    return `<button type="button" class="secondary" ${imageActionAttribute}="${escapeAttribute(run.id)}" ${canGenerate && !shareSummaryBusy(run) ? "" : "disabled"}>${imageActionLabel}</button>`;
}

export function renderShareSummaryAudioActionButton(run, canGenerate = run.status === "SUCCESS", hasAudio = Boolean(run.audioUrl)) {
    const audioActionAttribute = hasAudio ? "data-regenerate-share-audio" : "data-generate-share-audio";
    const audioActionLabel = hasAudio ? "重新生成音频" : "生成音频";
    return `<button type="button" class="secondary" ${audioActionAttribute}="${escapeAttribute(run.id)}" ${canGenerate && !shareSummaryBusy(run) ? "" : "disabled"}>${audioActionLabel}</button>`;
}

function renderShareSummaryRunPagination(payload) {
    const page = Number(payload.page || 1);
    const totalPages = Number(payload.totalPages || 0);
    const total = Number(payload.total || 0);
    document.getElementById("share-summary-run-page-info").textContent = totalPages > 0
            ? `第 ${page} / ${totalPages} 页 · ${total} 条`
            : "暂无记录";
    document.getElementById("share-summary-run-prev").disabled = page <= 1;
    document.getElementById("share-summary-run-next").disabled = totalPages === 0 || page >= totalPages;
}

export function renderRunStatus(status) {
    const normalized = status || "-";
    const className = normalized === "SUCCESS" ? "is-success" : normalized === "FAILED" ? "is-warning" : "";
    const labels = {SUCCESS: "已完成", RUNNING: "生成中", EMPTY: "无可总结内容", FAILED: "失败", SKIPPED: "已跳过"};
    return `<span class="status-pill ${className}" title="${escapeAttribute(normalized)}">${escapeHtml(labels[normalized] || normalized)}</span>`;
}

function renderSummaryWindow(start, end) {
    return `
        <div class="window-range">
            <span>${escapeHtml(formatShortTimestamp(start))}</span>
            <span>${escapeHtml(formatShortTimestamp(end))}</span>
        </div>
    `;
}

export function renderImageStatus(status) {
    const normalized = status || "NOT_GENERATED";
    const className = normalized === "SUCCESS" ? "is-success" : normalized === "FAILED" || normalized === "TIMEOUT" ? "is-warning" : "";
    const labels = {NOT_GENERATED: "未生成", PENDING: "排队中", GENERATING: "生成中", SUCCESS: "已完成", FAILED: "失败", TIMEOUT: "超时"};
    return `<span class="status-pill ${className}" title="${escapeAttribute(normalized)}">${escapeHtml(labels[normalized] || normalized)}</span>`;
}

export function renderAudioStatus(status) {
    return renderImageStatus(status || "NOT_GENERATED");
}

export async function regenerateShareSummary(runId, versionId = null) {
    if (state.shareSummarySubmitting) return;
    state.shareSummarySubmitting = true;
    document.querySelectorAll("[data-regenerate-share-summary], [data-retry-share-version]").forEach(button => { button.disabled = true; });
    setFeedback("share-summary-history-feedback", "正在提交重新生成任务...", "");
    try {
        const path = versionId ? `versions/${encodeURIComponent(versionId)}/retry` : "regenerate";
        await fetchJson(`/api/admin/share-summary/runs/${encodeURIComponent(runId)}/${path}`, {
            method: "POST",
            headers: {Accept: "application/json", "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID()}
        });
        await loadShareSummaryRuns();
        await openShareSummaryRunDetail(runId);
        setFeedback("share-summary-run-modal-feedback", "已提交。三项全部成功后原链接统一更新，完成前继续展示旧版本。", "is-success");
    } catch (error) {
        setFeedback("share-summary-history-feedback", error.message, "is-error");
        setFeedback("share-summary-run-modal-feedback", error.message, "is-error");
    } finally {
        state.shareSummarySubmitting = false;
        // Refresh eligibility from server state after both successful and failed admission.
        await loadShareSummaryRuns();
        if (state.activeShareSummaryRunId) refreshShareSummaryRunDetail(state.activeShareSummaryRunId).catch(() => {});
    }
}

export function scheduleShareSummaryRefresh() {
    window.clearTimeout(state.shareSummaryRefreshTimer);
    const busy = (state.shareSummaryRuns.items || []).some(shareSummaryBusy);
    if (!busy && !(state.activeShareSummaryRunId && state.activeShareSummaryBusy)) return;
    state.shareSummaryRefreshTimer = window.setTimeout(async () => {
        try {
            await loadShareSummaryRuns();
            if (state.activeShareSummaryRunId) await refreshShareSummaryRunDetail(state.activeShareSummaryRunId);
        } catch (error) {
            setFeedback("share-summary-history-feedback", error.message, "is-error");
        } finally {
            scheduleShareSummaryRefresh();
        }
    }, 3000);
}

export async function generateShareSummaryImage(runId, regenerate) {
    if (!runId) {
        return;
    }
    setFeedback("share-summary-history-feedback", regenerate ? "正在重新生成图片..." : "正在生成图片...", "");
    try {
        const path = regenerate ? "image/regenerate" : "image";
        await fetchJson(`/api/admin/share-summary/runs/${encodeURIComponent(runId)}/${path}`, {method: "POST"});
        await loadShareSummaryRuns();
        if (state.activeShareSummaryRunId && String(state.activeShareSummaryRunId) === String(runId)) {
            await openShareSummaryRunDetail(runId);
        }
        setFeedback("share-summary-history-feedback", "图片任务已提交。", "is-success");
    } catch (error) {
        setFeedback("share-summary-history-feedback", error.message, "is-error");
        setFeedback("share-summary-run-modal-feedback", error.message, "is-error");
    }
}

export async function generateShareSummaryAudio(runId, regenerate) {
    if (!runId) {
        return;
    }
    setFeedback("share-summary-history-feedback", regenerate ? "正在重新生成音频..." : "正在生成音频...", "");
    try {
        const path = regenerate ? "audio/regenerate" : "audio";
        await fetchJson(`/api/admin/share-summary/runs/${encodeURIComponent(runId)}/${path}`, {method: "POST"});
        await loadShareSummaryRuns();
        if (state.activeShareSummaryRunId && String(state.activeShareSummaryRunId) === String(runId)) {
            await openShareSummaryRunDetail(runId);
        }
        setFeedback("share-summary-history-feedback", "音频任务已提交。", "is-success");
    } catch (error) {
        setFeedback("share-summary-history-feedback", error.message, "is-error");
        setFeedback("share-summary-run-modal-feedback", error.message, "is-error");
    }
}

export async function copyShareSummaryUrl(url) {
    if (!url) {
        return;
    }
    try {
        await navigator.clipboard.writeText(url);
        setFeedback("share-summary-history-feedback", "链接已复制。", "is-success");
        setFeedback("share-summary-run-modal-feedback", "链接已复制。", "is-success");
    } catch (error) {
        window.prompt("复制链接", url);
    }
}

function renderRunErrorHint(errorMessage) {
    if (!errorMessage) {
        return "";
    }
    return `<div class="keyline summary-error-hint" title="${escapeAttribute(errorMessage)}">${escapeHtml(errorMessage)}</div>`;
}

export function shareSummaryOgShareUrl(run) {
    return run.ogShareUrl || run.ogPageUrl || "";
}
