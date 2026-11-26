// Notification tasks: event filters, templates and placeholders.
import {fetchJson} from "../../core/api.js";
import {escapeAttribute, escapeHtml, groupBy} from "../../core/format.js";
import {NOTIFICATION_FILTER_PERIOD_TYPES, NOTIFICATION_FILTER_TRIGGER_TYPES, state} from "../../core/state.js";
import {
    checkedNumberValues,
    checkedValues,
    closeModal,
    confirmDangerAction,
    insertTextAtCursor,
    openModal,
    resetDangerAction,
    setCheckedValues,
    setFeedback,
} from "../../core/ui.js";
import {notificationChannelNames, renderNotificationChannelOptions} from "./channels.js";
import {loadNotificationDeliveries} from "./deliveries.js";
import {periodLabel, periodSelectionModeLabel, scheduleLabel} from "../share-summary/tasks.js";

export async function loadNotificationTasks() {
    state.notificationTasks = await fetchJson("/api/admin/notifications/tasks");
    renderNotificationTasks();
    renderNotificationTaskOptions();
}

export function renderNotificationFilterOptions() {
    renderNotificationShareTaskFilterOptions();
    renderNotificationEnumFilterOptions("notification-filter-period-options", NOTIFICATION_FILTER_PERIOD_TYPES, "notification-filter-period");
    renderNotificationEnumFilterOptions("notification-filter-trigger-options", NOTIFICATION_FILTER_TRIGGER_TYPES, "notification-filter-trigger");
}

function renderNotificationShareTaskFilterOptions() {
    const container = document.getElementById("notification-filter-share-task-options");
    if (!container) {
        return;
    }
    container.innerHTML = state.shareSummaryTasks.map((task) => `
        <label class="checkbox-row">
            <input type="checkbox" value="${escapeAttribute(task.id)}" data-notification-filter-share-task>
            <span>${escapeHtml(task.name)}${task.enabled ? "" : "（停用）"}<span class="keyline">${escapeHtml(periodLabel(task.periodType))} · ${escapeHtml(periodSelectionModeLabel(task.periodSelectionMode))} · ${escapeHtml(scheduleLabel(task))}</span></span>
        </label>
    `).join("") || `<p class="muted">暂无分享总结任务</p>`;
    container.querySelectorAll("[data-notification-filter-share-task]").forEach((input) => {
        input.addEventListener("change", updateNotificationFilterSummaries);
    });
    updateNotificationFilterSummaries();
}

function renderNotificationEnumFilterOptions(containerId, options, dataAttribute) {
    const container = document.getElementById(containerId);
    if (!container) {
        return;
    }
    container.innerHTML = options.map((option) => `
        <label class="checkbox-row">
            <input type="checkbox" value="${escapeAttribute(option.value)}" data-${dataAttribute}>
            <span>${escapeHtml(option.label)}<span class="keyline">${escapeHtml(option.value)}</span></span>
        </label>
    `).join("");
    container.querySelectorAll(`input[data-${dataAttribute}]`).forEach((input) => {
        input.addEventListener("change", updateNotificationFilterSummaries);
    });
    updateNotificationFilterSummaries();
}

function updateNotificationFilterSummaries() {
    setNotificationFilterSummary(
            "notification-filter-share-task-summary",
            checkedOptionLabels("[data-notification-filter-share-task]:checked"),
            "全部任务"
    );
    setNotificationFilterSummary(
            "notification-filter-period-summary",
            checkedOptionLabels("[data-notification-filter-period]:checked"),
            "全部周期"
    );
    setNotificationFilterSummary(
            "notification-filter-trigger-summary",
            checkedOptionLabels("[data-notification-filter-trigger]:checked"),
            "全部方式"
    );
}

function checkedOptionLabels(selector) {
    return Array.from(document.querySelectorAll(selector))
            .map((input) => {
                const label = input.closest("label");
                return label?.querySelector("span")?.childNodes?.[0]?.textContent?.trim() || input.value;
            })
            .filter(Boolean);
}

function setNotificationFilterSummary(elementId, labels, fallback) {
    const element = document.getElementById(elementId);
    if (!element) {
        return;
    }
    if (!labels.length) {
        element.textContent = fallback;
        return;
    }
    element.textContent = labels.length > 2 ? `${labels.slice(0, 2).join("、")} 等 ${labels.length} 项` : labels.join("、");
}

export function renderNotificationEventOptions() {
    const options = state.notificationEvents.map((event) => `<option value="${escapeAttribute(event.eventType)}">${escapeHtml(event.label || event.eventType)}</option>`).join("");
    document.getElementById("notification-task-event-type").innerHTML = options;
    updateNotificationTaskEventTypeState(false);
}

function renderNotificationTasks() {
    const body = document.getElementById("notification-task-table");
    if (!state.notificationTasks.length) {
        body.innerHTML = `<tr><td colspan="5" class="muted">暂无通知任务</td></tr>`;
        return;
    }
    body.innerHTML = state.notificationTasks.map((task) => `
        <tr>
            <td><strong>${escapeHtml(task.name)}</strong><div class="keyline">${escapeHtml(notificationFilterPreview(task.eventType, task.filters))}</div></td>
            <td>${escapeHtml(task.eventType)}</td>
            <td>${escapeHtml(notificationChannelNames(task.channelIds).join(" / ") || "-")}</td>
            <td>${task.enabled ? `<span class="status-pill is-success">启用</span>` : `<span class="status-pill">停用</span>`}</td>
            <td>
                <div class="row-actions">
                    <button type="button" class="secondary" data-edit-notification-task="${escapeAttribute(task.id)}">编辑</button>
                    <button type="button" class="danger" data-delete-notification-task="${escapeAttribute(task.id)}">删除</button>
                </div>
            </td>
        </tr>
    `).join("");
    body.querySelectorAll("[data-edit-notification-task]").forEach((button) => {
        button.addEventListener("click", () => {
            const task = state.notificationTasks.find((item) => String(item.id) === button.dataset.editNotificationTask);
            if (task) {
                openNotificationTaskModalForEdit(task);
            }
        });
    });
    body.querySelectorAll("[data-delete-notification-task]").forEach((button) => {
        button.addEventListener("click", async () => {
            if (!confirmDangerAction(button, "确认删除")) {
                return;
            }
            button.disabled = true;
            setFeedback("notification-feedback", "正在删除通知任务...", "");
            try {
                await fetchJson(`/api/admin/notifications/tasks/${encodeURIComponent(button.dataset.deleteNotificationTask)}`, {method: "DELETE"});
                await loadNotificationTasks();
                await loadNotificationDeliveries();
                setFeedback("notification-feedback", "通知任务已删除。", "is-success");
            } catch (error) {
                resetDangerAction(button);
                button.disabled = false;
                setFeedback("notification-feedback", error.message, "is-error");
            }
        });
    });
}

function renderNotificationTaskOptions() {
    const options = [`<option value="">全部任务</option>`]
            .concat(state.notificationTasks.map((task) => `<option value="${escapeAttribute(task.id)}">${escapeHtml(task.name)}</option>`))
            .join("");
    document.getElementById("notification-delivery-filter-task").innerHTML = options;
}

function renderNotificationPlaceholders() {
    const eventType = document.getElementById("notification-task-event-type").value;
    const eventSchema = state.notificationEvents.find((event) => event.eventType === eventType);
    const container = document.getElementById("notification-placeholder-list");
    if (!eventSchema) {
        container.innerHTML = `<p class="muted">请先选择事件类型。</p>`;
        return;
    }
    const groups = groupBy(eventSchema.placeholders || [], (placeholder) => placeholder.group || "other");
    container.innerHTML = Object.entries(groups).map(([group, placeholders]) => `
        <div class="placeholder-group">
            <h5>${escapeHtml(placeholderGroupLabel(group))}</h5>
            <div class="placeholder-buttons">
                ${placeholders.map((placeholder) => `
                    <button type="button" class="secondary placeholder-token" data-placeholder="${escapeAttribute(placeholder.name)}" title="${escapeAttribute(placeholder.description || "")}">
                        ${escapeHtml(placeholder.name)}
                    </button>
                `).join("")}
            </div>
        </div>
    `).join("");
    container.querySelectorAll("[data-placeholder]").forEach((button) => {
        button.addEventListener("click", () => insertNotificationPlaceholder(button.dataset.placeholder));
    });
}

export function updateNotificationTaskEventTypeState(resetTemplate) {
    const eventType = document.getElementById("notification-task-event-type").value || "SHARE_SUMMARY_IMAGE_SUCCESS";
    const filterPanel = document.getElementById("notification-task-filter-panel");
    if (filterPanel) {
        filterPanel.hidden = eventType !== "SHARE_SUMMARY_IMAGE_SUCCESS";
    }
    if (resetTemplate) {
        document.getElementById("notification-task-template").value = defaultNotificationTemplate(eventType);
    }
    renderNotificationPlaceholders();
    updateNotificationFilterSummaries();
}

function insertNotificationPlaceholder(name) {
    const textarea = document.getElementById("notification-task-template");
    insertTextAtCursor(textarea, `{{${name}}}`);
}

export function placeholderGroupLabel(group) {
    return {
        event: "事件信息",
        run: "分享总结",
        image: "分享图",
        provider: "AI Provider",
        request: "请求信息",
        error: "错误信息",
        downgrade: "降级信息",
        preview: "预览信息",
        message: "消息正文",
        system: "系统信息"
    }[group] || group;
}

export async function saveNotificationTask(event) {
    event.preventDefault();
    const id = document.getElementById("notification-task-id").value;
    const payload = notificationTaskPayload();
    if (!payload) {
        return;
    }
    const url = id ? `/api/admin/notifications/tasks/${encodeURIComponent(id)}` : "/api/admin/notifications/tasks";
    const method = id ? "PUT" : "POST";
    setFeedback("notification-task-modal-feedback", "正在保存通知任务...", "");
    try {
        await fetchJson(url, {
            method,
            body: JSON.stringify(payload)
        });
        closeNotificationTaskModal(false);
        await loadNotificationTasks();
        await loadNotificationDeliveries();
        setFeedback("notification-feedback", "通知任务已保存。", "is-success");
    } catch (error) {
        setFeedback("notification-task-modal-feedback", error.message, "is-error");
    }
}

export async function validateNotificationTemplate() {
    const payload = notificationTaskPayload(false);
    if (!payload) {
        return;
    }
    setFeedback("notification-task-modal-feedback", "正在校验模板...", "");
    try {
        const result = await fetchJson("/api/admin/notifications/tasks/validate-template", {
            method: "POST",
            body: JSON.stringify({
                eventType: payload.eventType,
                templateJson: payload.templateJson
            })
        });
        setFeedback(
                "notification-task-modal-feedback",
                result.valid ? "模板校验通过。" : `模板包含非法占位符：${(result.invalidPlaceholders || []).join(", ")}`,
                result.valid ? "is-success" : "is-error"
        );
    } catch (error) {
        setFeedback("notification-task-modal-feedback", error.message, "is-error");
    }
}

function notificationTaskPayload(requireChannels = true) {
    const channelIds = Array.from(document.querySelectorAll("[data-notification-task-channel]:checked"))
            .map((input) => Number(input.value))
            .filter((value) => Number.isInteger(value) && value > 0);
    if (requireChannels && channelIds.length === 0) {
        setFeedback("notification-task-modal-feedback", "通知任务至少需要关联一个渠道。", "is-error");
        return null;
    }
    return {
        name: document.getElementById("notification-task-name").value.trim(),
        enabled: document.getElementById("notification-task-enabled").checked,
        eventType: document.getElementById("notification-task-event-type").value,
        filters: notificationTaskFiltersPayload(document.getElementById("notification-task-event-type").value),
        templateJson: document.getElementById("notification-task-template").value.trim(),
        channelIds
    };
}

function notificationTaskFiltersPayload(eventType) {
    if (eventType !== "SHARE_SUMMARY_IMAGE_SUCCESS") {
        return {
            shareSummaryTaskIds: [],
            periodTypes: [],
            triggerTypes: []
        };
    }
    return {
        shareSummaryTaskIds: checkedNumberValues("[data-notification-filter-share-task]:checked"),
        periodTypes: checkedValues("[data-notification-filter-period]:checked"),
        triggerTypes: checkedValues("[data-notification-filter-trigger]:checked")
    };
}

export function openNotificationTaskModalForCreate() {
    resetNotificationTaskForm();
    document.getElementById("notification-task-modal-title").textContent = "新建通知任务";
    openModal("notification-task-modal");
    renderNotificationChannelOptions();
    renderNotificationFilterOptions();
    renderNotificationEventOptions();
    document.getElementById("notification-task-template").value = defaultNotificationTemplate(document.getElementById("notification-task-event-type").value);
    setFeedback("notification-task-modal-feedback", "", "");
    document.getElementById("notification-task-name").focus();
}

function openNotificationTaskModalForEdit(task) {
    resetNotificationTaskForm();
    renderNotificationChannelOptions();
    renderNotificationFilterOptions();
    renderNotificationEventOptions();
    document.getElementById("notification-task-modal-title").textContent = `编辑通知任务：${task.name || task.id}`;
    document.getElementById("notification-task-id").value = task.id || "";
    document.getElementById("notification-task-name").value = task.name || "";
    document.getElementById("notification-task-event-type").value = task.eventType || "SHARE_SUMMARY_IMAGE_SUCCESS";
    document.getElementById("notification-task-enabled").checked = Boolean(task.enabled);
    document.getElementById("notification-task-template").value = task.templateJson || defaultNotificationTemplate(task.eventType);
    setCheckedValues("[data-notification-filter-share-task]", task.filters?.shareSummaryTaskIds || []);
    setCheckedValues("[data-notification-filter-period]", task.filters?.periodTypes || []);
    setCheckedValues("[data-notification-filter-trigger]", task.filters?.triggerTypes || []);
    (task.channelIds || []).forEach((channelId) => {
        const input = document.querySelector(`[data-notification-task-channel][value="${CSS.escape(String(channelId))}"]`);
        if (input) {
            input.checked = true;
        }
    });
    updateNotificationTaskEventTypeState(false);
    updateNotificationFilterSummaries();
    openModal("notification-task-modal");
    setFeedback("notification-task-modal-feedback", "", "");
    document.getElementById("notification-task-name").focus();
}

export function closeNotificationTaskModal(clearFeedback = true) {
    resetNotificationTaskForm();
    closeModal("notification-task-modal");
    if (clearFeedback) {
        setFeedback("notification-task-modal-feedback", "", "");
    }
}

function resetNotificationTaskForm() {
    document.getElementById("notification-task-form").reset();
    document.getElementById("notification-task-id").value = "";
    document.getElementById("notification-task-enabled").checked = true;
    document.querySelectorAll("[data-notification-task-channel]").forEach((input) => {
        input.checked = false;
    });
    document.querySelectorAll("[data-notification-filter-share-task], [data-notification-filter-period], [data-notification-filter-trigger]").forEach((input) => {
        input.checked = false;
    });
    document.querySelectorAll("#notification-filter-fields details").forEach((details) => {
        details.open = false;
    });
    updateNotificationFilterSummaries();
}

function notificationFilterPreview(eventType, filters) {
    if (eventType !== "SHARE_SUMMARY_IMAGE_SUCCESS") {
        return "匹配全部同类事件";
    }
    const parts = [];
    if (filters?.shareSummaryTaskIds?.length) {
        parts.push(`任务 ${filters.shareSummaryTaskIds.join(",")}`);
    }
    if (filters?.periodTypes?.length) {
        parts.push(filters.periodTypes.join("/"));
    }
    if (filters?.triggerTypes?.length) {
        parts.push(filters.triggerTypes.join("/"));
    }
    return parts.join(" · ") || "匹配全部分享图成功事件";
}

function defaultNotificationTemplate(eventType = "SHARE_SUMMARY_IMAGE_SUCCESS") {
    const templates = {
        SHARE_SUMMARY_IMAGE_SUCCESS: `{{run.taskName}} 已生成分享图

周期：{{run.periodType}}
范围：{{run.windowStartLabel}} 至 {{run.windowEndLabel}}
链接：{{run.inputLinkCount}} 条

标题：{{image.ogTitle}}
链接：{{image.ogShareUrl}}`,
        SHARE_SUMMARY_IMAGE_FAILED: `分享总结图片生成记录失败

任务：{{run.taskName}} (#{{run.taskId}})
执行：#{{run.id}}
周期：{{run.periodType}}
范围：{{run.windowStartLabel}} 至 {{run.windowEndLabel}}

图片记录：#{{image.id}}
第几次生成：{{image.attemptNo}}
状态：{{image.status}}
Provider：{{image.providerType}}
模型：{{image.model}}
尺寸：{{image.imageSize}}
格式：{{image.outputFormat}}
耗时：{{image.durationMs}} ms

错误：{{error.type}} - {{error.message}}`,
        SHARE_SUMMARY_AUDIO_FAILED: `分享总结音频生成记录失败

任务：{{run.taskName}} (#{{run.taskId}})
执行：#{{run.id}}
周期：{{run.periodType}}
范围：{{run.windowStartLabel}} 至 {{run.windowEndLabel}}

音频记录：#{{audio.id}}
第几次生成：{{audio.attemptNo}}
状态：{{audio.status}}
Provider：{{audio.providerType}}
模型：{{audio.model}}
声音：{{audio.voice}}
语速：{{audio.speed}}
音调：{{audio.pitch}}
标签：{{audio.style}}
格式：{{audio.outputFormat}}
耗时：{{audio.durationMs}} ms

错误：{{error.type}} - {{error.message}}`,
        AI_PROVIDER_REQUEST_FAILED: `AI Provider 请求失败

Provider：{{provider.name}} (#{{provider.id}})
操作：{{request.operation}}
耗时：{{request.durationMs}} ms
错误：{{error.type}} - {{error.message}}

自动降级启用：{{downgrade.enabled}}
本次失败计数：{{downgrade.failureCount}} / {{downgrade.failureThreshold}}
本次触发自动降级：{{downgrade.triggered}}`,
        AI_PROVIDER_AUTO_DOWNGRADED: `AI Provider 已触发自动降级

Provider：{{provider.name}} (#{{provider.id}})
操作：{{request.operation}}
触发失败计数：{{downgrade.failureCount}} / {{downgrade.failureThreshold}}
排序调整：{{downgrade.oldSortOrder}} -> {{downgrade.newSortOrder}}
Provider 总数：{{downgrade.providerCount}}

错误：{{error.type}} - {{error.message}}`,
        DATA_CRAWL_REQUEST_FAILED: `数据爬取请求失败

Provider：{{preview.providerId}}
Preview：{{preview.previewKey}}
来源 URL：{{preview.sourceUrl}}
规范 URL：{{preview.canonicalUrl}}
请求客户端：{{request.clientType}}
响应状态：{{request.httpStatus}}
耗时：{{request.durationMs}} ms

错误：{{error.code}} / {{error.type}} - {{error.message}}`
    };
    return templates[eventType] || templates.SHARE_SUMMARY_IMAGE_SUCCESS;
}
