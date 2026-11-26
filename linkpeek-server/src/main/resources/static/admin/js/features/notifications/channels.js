// Notification webhook channels.
import {fetchJson} from "../../core/api.js";
import {escapeAttribute, escapeHtml, groupBy} from "../../core/format.js";
import {state} from "../../core/state.js";
import {
    closeModal,
    confirmDangerAction,
    insertTextAtCursor,
    openModal,
    resetDangerAction,
    setFeedback,
} from "../../core/ui.js";
import {loadNotifications} from "./panel.js";
import {placeholderGroupLabel} from "./tasks.js";

export async function loadNotificationChannels() {
    state.notificationChannels = await fetchJson("/api/admin/notifications/channels");
    renderNotificationChannels();
    renderNotificationChannelOptions();
}

function renderNotificationChannels() {
    const body = document.getElementById("notification-channel-table");
    if (!state.notificationChannels.length) {
        body.innerHTML = `<tr><td colspan="5" class="muted">暂无通知渠道</td></tr>`;
        return;
    }
    body.innerHTML = state.notificationChannels.map((channel) => `
        <tr>
            <td><strong>${escapeHtml(channel.name)}</strong><div class="keyline">${escapeHtml(channel.type || "WEBHOOK")}</div></td>
            <td><span class="url-cell" title="${escapeAttribute(channel.url || "-")}">${escapeHtml(channel.url || "-")}</span></td>
            <td class="nowrap">${escapeHtml(channel.timeoutSeconds || 10)}s</td>
            <td>${channel.enabled ? `<span class="status-pill is-success">启用</span>` : `<span class="status-pill">停用</span>`}</td>
            <td>
                <div class="row-actions">
                    <button type="button" class="secondary" data-test-notification-channel="${escapeAttribute(channel.id)}">测试</button>
                    <button type="button" class="secondary" data-edit-notification-channel="${escapeAttribute(channel.id)}">编辑</button>
                    <button type="button" class="danger" data-delete-notification-channel="${escapeAttribute(channel.id)}">删除</button>
                </div>
            </td>
        </tr>
    `).join("");
    body.querySelectorAll("[data-test-notification-channel]").forEach((button) => {
        button.addEventListener("click", () => testNotificationChannel(button.dataset.testNotificationChannel));
    });
    body.querySelectorAll("[data-edit-notification-channel]").forEach((button) => {
        button.addEventListener("click", () => {
            const channel = state.notificationChannels.find((item) => String(item.id) === button.dataset.editNotificationChannel);
            if (channel) {
                openNotificationChannelModalForEdit(channel);
            }
        });
    });
    body.querySelectorAll("[data-delete-notification-channel]").forEach((button) => {
        button.addEventListener("click", async () => {
            if (!confirmDangerAction(button, "确认删除")) {
                return;
            }
            button.disabled = true;
            setFeedback("notification-feedback", "正在删除通知渠道...", "");
            try {
                await fetchJson(`/api/admin/notifications/channels/${encodeURIComponent(button.dataset.deleteNotificationChannel)}`, {method: "DELETE"});
                await loadNotifications();
                setFeedback("notification-feedback", "通知渠道已删除。", "is-success");
            } catch (error) {
                resetDangerAction(button);
                button.disabled = false;
                setFeedback("notification-feedback", error.message, "is-error");
            }
        });
    });
}

export function addNotificationHeaderRow(name = "", value = "") {
    const container = document.getElementById("notification-channel-headers");
    const row = document.createElement("div");
    row.className = "header-pair-row";
    row.innerHTML = `
        <label>
            <span>Key</span>
            <input type="text" autocomplete="off" data-notification-header-key>
        </label>
        <label>
            <span>Value</span>
            <input type="text" autocomplete="off" data-notification-header-value>
        </label>
        <button type="button" class="secondary" data-remove-notification-header>删除</button>
    `;
    row.querySelector("[data-notification-header-key]").value = name || "";
    row.querySelector("[data-notification-header-value]").value = value == null ? "" : String(value);
    row.querySelector("[data-remove-notification-header]").addEventListener("click", () => {
        row.remove();
        if (!container.querySelector(".header-pair-row")) {
            addNotificationHeaderRow();
        }
    });
    container.appendChild(row);
}

function setNotificationHeaderRows(headers) {
    const container = document.getElementById("notification-channel-headers");
    container.innerHTML = "";
    const entries = headers && typeof headers === "object" && !Array.isArray(headers) ? Object.entries(headers) : [];
    if (!entries.length) {
        addNotificationHeaderRow();
        return;
    }
    entries.forEach(([name, value]) => addNotificationHeaderRow(name, value));
}

function notificationHeadersPayload() {
    const headers = {};
    for (const row of document.querySelectorAll("#notification-channel-headers .header-pair-row")) {
        const key = row.querySelector("[data-notification-header-key]").value.trim();
        const value = row.querySelector("[data-notification-header-value]").value.trim();
        if (!key && !value) {
            continue;
        }
        if (!key) {
            setFeedback("notification-channel-modal-feedback", "Header Key 不能为空。", "is-error");
            return undefined;
        }
        headers[key] = value;
    }
    return Object.keys(headers).length ? headers : null;
}

export function renderNotificationChannelOptions() {
    const deliveryOptions = [`<option value="">全部渠道</option>`]
            .concat(state.notificationChannels.map((channel) => `<option value="${escapeAttribute(channel.id)}">${escapeHtml(channel.name)}</option>`))
            .join("");
    document.getElementById("notification-delivery-filter-channel").innerHTML = deliveryOptions;

    const taskOptions = state.notificationChannels.map((channel) => `
        <label class="checkbox-row">
            <input type="checkbox" value="${escapeAttribute(channel.id)}" data-notification-task-channel>
            <span>${escapeHtml(channel.name)}${channel.enabled ? "" : "（停用）"}</span>
        </label>
    `).join("");
    document.getElementById("notification-task-channel-options").innerHTML = taskOptions || `<p class="muted">暂无通知渠道</p>`;
}

function renderNotificationChannelPlaceholders() {
    const container = document.getElementById("notification-channel-placeholder-list");
    if (!container) {
        return;
    }
    const placeholders = [
        {group: "message", name: "message.body", description: "通知任务渲染后的消息正文，作为字符串插入。"},
        {group: "message", name: "message.bodyJson", description: "通知任务渲染后的消息正文，原样插入 JSON。"}
    ];
    const groups = groupBy(placeholders, (placeholder) => placeholder.group || "other");
    container.innerHTML = Object.entries(groups).map(([group, groupPlaceholders]) => `
        <div class="placeholder-group">
            <h5>${escapeHtml(placeholderGroupLabel(group))}</h5>
            <div class="placeholder-buttons">
                ${groupPlaceholders.map((placeholder) => `
                    <button type="button" class="secondary placeholder-token" data-channel-placeholder="${escapeAttribute(placeholder.name)}" title="${escapeAttribute(placeholder.description || "")}">
                        ${escapeHtml(placeholder.name)}
                    </button>
                `).join("")}
            </div>
        </div>
    `).join("");
    container.querySelectorAll("[data-channel-placeholder]").forEach((button) => {
        button.addEventListener("click", () => {
            insertTextAtCursor(document.getElementById("notification-channel-body-template"), `{{${button.dataset.channelPlaceholder}}}`);
        });
    });
}

async function testNotificationChannel(channelId) {
    if (!channelId) {
        return;
    }
    setFeedback("notification-feedback", "正在测试通知渠道...", "");
    try {
        const result = await fetchJson(`/api/admin/notifications/channels/${encodeURIComponent(channelId)}/test`, {method: "POST"});
        setFeedback(
                "notification-feedback",
                result.success ? `测试成功，HTTP ${result.responseStatus || "-"}` : `测试失败：${result.errorMessage || result.message || "unknown"}`,
                result.success ? "is-success" : "is-error"
        );
    } catch (error) {
        setFeedback("notification-feedback", error.message, "is-error");
    }
}

export async function saveNotificationChannel(event) {
    event.preventDefault();
    const id = document.getElementById("notification-channel-id").value;
    const timeoutSeconds = Number(document.getElementById("notification-channel-timeout").value || 0);
    const headersJson = notificationHeadersPayload();
    if (headersJson === undefined) {
        return;
    }
    const payload = {
        name: document.getElementById("notification-channel-name").value.trim(),
        enabled: document.getElementById("notification-channel-enabled").checked,
        url: document.getElementById("notification-channel-url").value.trim(),
        timeoutSeconds,
        headersJson,
        bodyTemplate: document.getElementById("notification-channel-body-template").value.trim(),
        secret: document.getElementById("notification-channel-secret").value.trim()
    };
    if (!Number.isInteger(timeoutSeconds) || timeoutSeconds < 1 || timeoutSeconds > 60) {
        setFeedback("notification-channel-modal-feedback", "超时秒数必须是 1-60 之间的整数。", "is-error");
        return;
    }
    const url = id ? `/api/admin/notifications/channels/${encodeURIComponent(id)}` : "/api/admin/notifications/channels";
    const method = id ? "PUT" : "POST";
    setFeedback("notification-channel-modal-feedback", "正在保存通知渠道...", "");
    try {
        await fetchJson(url, {
            method,
            body: JSON.stringify(payload)
        });
        closeNotificationChannelModal(false);
        await loadNotificationChannels();
        setFeedback("notification-feedback", "通知渠道已保存。", "is-success");
    } catch (error) {
        setFeedback("notification-channel-modal-feedback", error.message, "is-error");
    }
}

export function openNotificationChannelModalForCreate() {
    resetNotificationChannelForm();
    document.getElementById("notification-channel-modal-title").textContent = "新建通知渠道";
    renderNotificationChannelPlaceholders();
    openModal("notification-channel-modal");
    setFeedback("notification-channel-modal-feedback", "", "");
    document.getElementById("notification-channel-name").focus();
}

function openNotificationChannelModalForEdit(channel) {
    resetNotificationChannelForm();
    document.getElementById("notification-channel-modal-title").textContent = `编辑通知渠道：${channel.name || channel.id}`;
    document.getElementById("notification-channel-id").value = channel.id || "";
    document.getElementById("notification-channel-name").value = channel.name || "";
    document.getElementById("notification-channel-url").value = channel.url || "";
    document.getElementById("notification-channel-timeout").value = channel.timeoutSeconds || 10;
    document.getElementById("notification-channel-enabled").checked = Boolean(channel.enabled);
    setNotificationHeaderRows(channel.headersJson);
    document.getElementById("notification-channel-body-template").value = channel.bodyTemplate || defaultNotificationChannelBodyTemplate();
    document.getElementById("notification-channel-secret").value = "";
    document.getElementById("notification-channel-secret").placeholder = channel.secretConfigured ? "已配置，留空表示不修改" : "可选";
    renderNotificationChannelPlaceholders();
    openModal("notification-channel-modal");
    setFeedback("notification-channel-modal-feedback", "", "");
    document.getElementById("notification-channel-name").focus();
}

export function closeNotificationChannelModal(clearFeedback = true) {
    resetNotificationChannelForm();
    closeModal("notification-channel-modal");
    if (clearFeedback) {
        setFeedback("notification-channel-modal-feedback", "", "");
    }
}

function resetNotificationChannelForm() {
    document.getElementById("notification-channel-form").reset();
    document.getElementById("notification-channel-id").value = "";
    document.getElementById("notification-channel-timeout").value = "10";
    document.getElementById("notification-channel-enabled").checked = true;
    setNotificationHeaderRows(null);
    document.getElementById("notification-channel-body-template").value = defaultNotificationChannelBodyTemplate();
    document.getElementById("notification-channel-secret").placeholder = "可选";
}

export function notificationChannelNames(channelIds) {
    const ids = new Set((channelIds || []).map((id) => String(id)));
    return state.notificationChannels
            .filter((channel) => ids.has(String(channel.id)))
            .map((channel) => channel.name);
}

function defaultNotificationChannelBodyTemplate() {
    return "{{message.bodyJson}}";
}
