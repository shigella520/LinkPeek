// Share summary scheduled tasks and their window descriptions.
import {fetchJson} from "../../core/api.js";
import {escapeAttribute, escapeHtml} from "../../core/format.js";
import {state} from "../../core/state.js";
import {closeModal, confirmDangerAction, openModal, resetDangerAction, setFeedback} from "../../core/ui.js";
import {renderNotificationFilterOptions} from "../notifications/tasks.js";
import {openShareSummaryRunDetail} from "./run-detail.js";
import {loadShareSummary, loadShareSummaryRuns} from "./runs.js";

export async function loadShareSummaryTasks() {
    state.shareSummaryTasks = await fetchJson("/api/admin/share-summary/tasks");
    renderShareSummaryTasks();
    renderShareSummaryTaskFilterOptions();
}

function renderShareSummaryTasks() {
    const body = document.getElementById("share-summary-task-table");
    if (!state.shareSummaryTasks.length) {
        body.innerHTML = `<tr><td colspan="5" class="muted">暂无分享总结任务</td></tr>`;
        return;
    }
    body.innerHTML = state.shareSummaryTasks.map((task) => `
        <tr class="share-summary-task-row">
            <td class="share-summary-task-name-cell">
                <strong>${escapeHtml(task.name)}</strong>
            </td>
            <td class="nowrap share-summary-period-cell">
                <div class="period-window-trigger" tabindex="0" aria-label="查看时间窗口">
                    <span>${escapeHtml(periodLabel(task.periodType))}</span>
                    <div class="keyline">${escapeHtml(periodSelectionModeLabel(task.periodSelectionMode))}</div>
                    ${renderShareSummaryWindowPopover(task)}
                </div>
            </td>
            <td class="nowrap share-summary-schedule-cell">${escapeHtml(scheduleLabel(task))}</td>
            <td class="share-summary-status-cell">${task.enabled ? `<span class="status-pill is-success">启用</span>` : `<span class="status-pill">停用</span>`}</td>
            <td class="share-summary-task-action-cell">
                <div class="row-actions share-summary-task-actions">
                    <button type="button" class="secondary" data-run-share-task="${task.id}">执行</button>
                    <button type="button" class="secondary" data-edit-share-task="${task.id}">编辑</button>
                    <button type="button" class="danger" data-delete-share-task="${task.id}">删除</button>
                </div>
            </td>
        </tr>
    `).join("");
    body.querySelectorAll("[data-run-share-task]").forEach((button) => {
        button.addEventListener("click", async () => {
            await runShareSummaryTask(button.dataset.runShareTask);
        });
    });
    body.querySelectorAll("[data-edit-share-task]").forEach((button) => {
        button.addEventListener("click", () => {
            const task = state.shareSummaryTasks.find((item) => String(item.id) === button.dataset.editShareTask);
            if (task) {
                openShareSummaryTaskModalForEdit(task);
            }
        });
    });
    body.querySelectorAll("[data-delete-share-task]").forEach((button) => {
        button.addEventListener("click", async () => {
            if (!confirmDangerAction(button, "确认删除")) {
                return;
            }
            button.disabled = true;
            setFeedback("share-summary-feedback", "正在删除分享总结任务...", "");
            try {
                await fetchJson(`/api/admin/share-summary/tasks/${encodeURIComponent(button.dataset.deleteShareTask)}`, {method: "DELETE"});
                await loadShareSummary();
                setFeedback("share-summary-feedback", "分享总结任务已删除。", "is-success");
            } catch (error) {
                resetDangerAction(button);
                button.disabled = false;
                setFeedback("share-summary-feedback", error.message, "is-error");
            }
        });
    });
}

function renderShareSummaryWindowPopover(task) {
    return `
        <div class="period-window-popover" role="tooltip">
            <div class="period-window-card">
                <div class="period-window-card-head">
                    <p>时间窗口</p>
                    <span>${escapeHtml(periodSelectionModeLabel(task.periodSelectionMode))}</span>
                </div>
                <dl>
                    <div>
                        <dt>自动执行</dt>
                        <dd>${escapeHtml(autoWindowDescription(task))}</dd>
                    </div>
                    <div>
                        <dt>手动执行</dt>
                        <dd>${escapeHtml(manualWindowDescription(task))}</dd>
                    </div>
                </dl>
                <div class="period-window-card-summary">
                    <span>${escapeHtml(periodLabel(task.periodType))}</span>
                    <span>${escapeHtml(scheduleLabel(task))}</span>
                </div>
            </div>
        </div>
    `;
}

function renderShareSummaryTaskFilterOptions() {
    const filterOptions = [`<option value="">全部任务</option>`]
            .concat(state.shareSummaryTasks.map((task) => `<option value="${escapeAttribute(task.id)}">${escapeHtml(task.name)}</option>`))
            .join("");
    const select = document.getElementById("share-summary-run-filter-task");
    const selected = select.value;
    select.innerHTML = filterOptions;
    if (state.shareSummaryTasks.some(task => String(task.id) === selected)) select.value = selected;
    renderNotificationFilterOptions();
}

export function periodLabel(value) {
    if (value === "WEEKLY") {
        return "每周";
    }
    if (value === "MONTHLY") {
        return "每月";
    }
    return "每日";
}

export function periodSelectionModeLabel(value) {
    return value === "PREVIOUS" ? "上周期" : "本周期";
}

export function scheduleLabel(task) {
    if (task.periodType === "WEEKLY") {
        return `${weekdayLabel(task.dayOfWeek)} ${task.runTime || ""}`;
    }
    if (task.periodType === "MONTHLY") {
        return `${task.periodSelectionMode === "PREVIOUS" ? "月初" : "月末"} ${task.runTime || ""}`;
    }
    return task.runTime || "";
}

function autoWindowDescription(task) {
    const runTime = task.runTime || "09:00";
    if (task.periodSelectionMode === "PREVIOUS") {
        if (task.periodType === "WEEKLY") {
            return "上周一 00:00 到本周一 00:00";
        }
        if (task.periodType === "MONTHLY") {
            return "上月 1 日 00:00 到本月 1 日 00:00";
        }
        return "昨天 00:00 到今天 00:00";
    }
    if (task.periodType === "WEEKLY") {
        return `本周一 00:00 到${weekdayLabel(task.dayOfWeek)} ${runTime}`;
    }
    if (task.periodType === "MONTHLY") {
        return `本月 1 日 00:00 到月末 ${runTime}`;
    }
    return `当天 00:00 到 ${runTime}`;
}

function manualWindowDescription(task) {
    if (task.periodSelectionMode === "PREVIOUS") {
        return autoWindowDescription(task);
    }
    if (task.periodType === "WEEKLY") {
        return "本周一 00:00 到当前时间";
    }
    if (task.periodType === "MONTHLY") {
        return "本月 1 日 00:00 到当前时间";
    }
    return "当天 00:00 到当前时间";
}

function weekdayLabel(value) {
    return ["", "周一", "周二", "周三", "周四", "周五", "周六", "周日"][Number(value || 1)] || "周一";
}

export async function saveShareSummaryTask(event) {
    event.preventDefault();
    const id = document.getElementById("share-summary-task-id").value;
    const periodType = document.getElementById("share-summary-task-period").value;
    const payload = {
        name: document.getElementById("share-summary-task-name").value.trim(),
        enabled: document.getElementById("share-summary-task-enabled").checked,
        periodType,
        periodSelectionMode: document.getElementById("share-summary-task-period-selection-mode").value,
        runTime: document.getElementById("share-summary-task-run-time").value,
        dayOfWeek: periodType === "WEEKLY" ? Number(document.getElementById("share-summary-task-day-of-week").value) : null,
        prompt: document.getElementById("share-summary-task-prompt").value.trim(),
        maxLinks: Number(document.getElementById("share-summary-task-max-links").value || 100),
        minLinks: Number(document.getElementById("share-summary-task-min-links").value || 1)
    };
    const maxLinksError = validateShareSummaryMaxLinks(payload.maxLinks);
    if (maxLinksError) {
        setFeedback("share-summary-task-modal-feedback", maxLinksError, "is-error");
        return;
    }
    const minLinksError = validateShareSummaryMinLinks(payload.minLinks);
    if (minLinksError) {
        setFeedback("share-summary-task-modal-feedback", minLinksError, "is-error");
        return;
    }
    const url = id ? `/api/admin/share-summary/tasks/${encodeURIComponent(id)}` : "/api/admin/share-summary/tasks";
    const method = id ? "PUT" : "POST";
    setFeedback("share-summary-task-modal-feedback", "正在保存分享总结任务...", "");
    try {
        await fetchJson(url, {
            method,
            body: JSON.stringify(payload)
        });
        closeShareSummaryTaskModal(false);
        await loadShareSummary();
        setFeedback("share-summary-feedback", "分享总结任务已保存。", "is-success");
    } catch (error) {
        setFeedback("share-summary-task-modal-feedback", error.message, "is-error");
    }
}

async function runShareSummaryTask(taskId) {
    if (!taskId) {
        setFeedback("share-summary-feedback", "请选择分享总结任务。", "is-error");
        return;
    }
    setFeedback("share-summary-feedback", "正在执行分享总结...", "");
    try {
        const run = await fetchJson(`/api/admin/share-summary/tasks/${encodeURIComponent(taskId)}/run`, {
            method: "POST"
        });
        await loadShareSummaryRuns();
        setFeedback("share-summary-feedback", `执行完成：${run.status || "-"}`, run.status === "FAILED" ? "is-error" : "is-success");
        if (run && run.id) {
            await openShareSummaryRunDetail(run.id);
        }
    } catch (error) {
        setFeedback("share-summary-feedback", error.message, "is-error");
    }
}

export function openShareSummaryTaskModalForCreate() {
    resetShareSummaryTaskForm();
    document.getElementById("share-summary-task-modal-title").textContent = "新建分享总结任务";
    openModal("share-summary-task-modal");
    updateShareSummaryPeriodFields();
    setFeedback("share-summary-task-modal-feedback", "", "");
    document.getElementById("share-summary-task-name").focus();
}

function openShareSummaryTaskModalForEdit(task) {
    resetShareSummaryTaskForm();
    document.getElementById("share-summary-task-modal-title").textContent = `编辑分享总结任务：${task.name || task.id}`;
    document.getElementById("share-summary-task-id").value = task.id || "";
    document.getElementById("share-summary-task-name").value = task.name || "";
    document.getElementById("share-summary-task-period").value = task.periodType || "DAILY";
    document.getElementById("share-summary-task-period-selection-mode").value = task.periodSelectionMode || "CURRENT";
    document.getElementById("share-summary-task-run-time").value = task.runTime || "09:00";
    document.getElementById("share-summary-task-day-of-week").value = task.dayOfWeek || 1;
    document.getElementById("share-summary-task-max-links").value = task.maxLinks || 100;
    document.getElementById("share-summary-task-min-links").value = task.minLinks || 1;
    document.getElementById("share-summary-task-enabled").checked = Boolean(task.enabled);
    document.getElementById("share-summary-task-prompt").value = task.prompt || "";
    openModal("share-summary-task-modal");
    updateShareSummaryPeriodFields();
    setFeedback("share-summary-task-modal-feedback", "", "");
    document.getElementById("share-summary-task-name").focus();
}

export function closeShareSummaryTaskModal(clearFeedback = true) {
    resetShareSummaryTaskForm();
    closeModal("share-summary-task-modal");
    if (clearFeedback) {
        setFeedback("share-summary-task-modal-feedback", "", "");
    }
}

function resetShareSummaryTaskForm() {
    document.getElementById("share-summary-task-form").reset();
    document.getElementById("share-summary-task-id").value = "";
    document.getElementById("share-summary-task-period").value = "DAILY";
    document.getElementById("share-summary-task-period-selection-mode").value = "CURRENT";
    document.getElementById("share-summary-task-run-time").value = "09:00";
    document.getElementById("share-summary-task-day-of-week").value = "1";
    document.getElementById("share-summary-task-max-links").value = "100";
    document.getElementById("share-summary-task-min-links").value = "1";
    updateShareSummaryPeriodFields();
}

export function updateShareSummaryPeriodFields() {
    const periodType = document.getElementById("share-summary-task-period").value;
    const periodSelectionMode = document.getElementById("share-summary-task-period-selection-mode").value;
    document.getElementById("share-summary-task-weekday-field").hidden = periodType !== "WEEKLY";
    document.getElementById("share-summary-task-monthly-field").hidden = periodType !== "MONTHLY";
    document.getElementById("share-summary-task-monthly-trigger-label").value = periodSelectionMode === "PREVIOUS" ? "月初" : "月末";
    renderShareSummaryWindowHelp();
}

function renderShareSummaryWindowHelp() {
    const task = {
        periodType: document.getElementById("share-summary-task-period").value,
        periodSelectionMode: document.getElementById("share-summary-task-period-selection-mode").value,
        runTime: document.getElementById("share-summary-task-run-time").value || "09:00",
        dayOfWeek: Number(document.getElementById("share-summary-task-day-of-week").value || 1)
    };
    document.getElementById("share-summary-window-auto-description").textContent = autoWindowDescription(task);
    document.getElementById("share-summary-window-manual-description").textContent = manualWindowDescription(task);
    document.getElementById("share-summary-window-mode-chip").textContent = periodSelectionModeLabel(task.periodSelectionMode);
    document.getElementById("share-summary-window-period-chip").textContent = periodLabel(task.periodType);
    document.getElementById("share-summary-window-schedule-chip").textContent = scheduleLabel(task);
}

function validateShareSummaryMaxLinks(value) {
    if (!Number.isInteger(value) || value < 1 || value > 2000) {
        return "最大链接数必须是 1-2000 之间的整数。";
    }
    return "";
}

function validateShareSummaryMinLinks(value) {
    if (!Number.isInteger(value) || value < 1 || value > 2000) {
        return "最小链接数必须是 1-2000 之间的整数。";
    }
    return "";
}
