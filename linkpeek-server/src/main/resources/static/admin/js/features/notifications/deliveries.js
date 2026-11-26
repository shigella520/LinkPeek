// Notification delivery records: status, retry and delete.
import {fetchJson} from "../../core/api.js";
import {escapeAttribute, escapeHtml, formatDuration, formatTimestamp} from "../../core/format.js";
import {state} from "../../core/state.js";
import {confirmDangerAction, resetDangerAction, setFeedback} from "../../core/ui.js";
import {shortPreviewKey} from "../preview-events.js";

export async function loadNotificationDeliveries() {
    const request = (state.requests.loadNotificationDeliveries || 0) + 1;
    state.requests.loadNotificationDeliveries = request;
    const params = new URLSearchParams();
    params.set("page", String(state.notificationDeliveries.page || 1));
    params.set("size", document.getElementById("notification-delivery-size").value || "20");
    const taskId = document.getElementById("notification-delivery-filter-task").value;
    const channelId = document.getElementById("notification-delivery-filter-channel").value;
    const status = document.getElementById("notification-delivery-filter-status").value;
    if (taskId) {
        params.set("taskId", taskId);
    }
    if (channelId) {
        params.set("channelId", channelId);
    }
    if (status) {
        params.set("status", status);
    }
    setFeedback("notification-delivery-feedback", "正在读取发送记录...", "");
    try {
        const result = await fetchJson(`/api/admin/notifications/deliveries?${params.toString()}`);
        if (state.requests.loadNotificationDeliveries !== request) return;
        state.notificationDeliveries = result;
        renderNotificationDeliveries();
        setFeedback("notification-delivery-feedback", `已加载 ${state.notificationDeliveries.items.length} 条，共 ${state.notificationDeliveries.total} 条。`, "is-success");
    } catch (error) {
        if (state.requests.loadNotificationDeliveries !== request) return;
        setFeedback("notification-delivery-feedback", error.message, "is-error");
    }
}

function renderNotificationDeliveries() {
    const payload = state.notificationDeliveries || {};
    const items = Array.isArray(payload.items) ? payload.items : [];
    const body = document.getElementById("notification-delivery-table");
    if (!items.length) {
        body.innerHTML = `<tr><td colspan="7" class="muted">暂无发送记录</td></tr>`;
    } else {
        body.innerHTML = items.map((delivery) => `
            <tr>
                <td class="nowrap">${escapeHtml(formatTimestamp(delivery.createdAt))}</td>
                <td>${escapeHtml(delivery.notificationTaskName || "-")}<div class="keyline">${escapeHtml(delivery.channelName || "-")}</div></td>
                <td>${renderNotificationDeliveryEvent(delivery)}</td>
                <td>${renderNotificationStatus(delivery.pendingExpired ? "EXPIRED_PENDING" : delivery.status)}${delivery.errorMessage ? `<div class="keyline summary-error-hint" title="${escapeAttribute(delivery.errorMessage)}">${escapeHtml(delivery.errorMessage)}</div>` : ""}</td>
                <td>${escapeHtml(delivery.responseStatus || "-")}<div class="keyline">${escapeHtml(delivery.attemptCount || 0)} 次</div></td>
                <td>${escapeHtml(formatDuration(delivery.durationMs))}</td>
                <td>
                    <div class="row-actions notification-delivery-actions">
                        <button type="button" class="secondary" data-retry-notification-delivery="${escapeAttribute(delivery.id)}">重发</button>
                        <button type="button" class="danger" data-delete-notification-delivery="${escapeAttribute(delivery.id)}">删除</button>
                    </div>
                </td>
            </tr>
        `).join("");
    }
    body.querySelectorAll("[data-retry-notification-delivery]").forEach((button) => {
        button.addEventListener("click", async () => retryNotificationDelivery(button));
    });
    body.querySelectorAll("[data-delete-notification-delivery]").forEach((button) => {
        button.addEventListener("click", async () => deleteNotificationDelivery(button));
    });
    renderNotificationDeliveryPagination(payload);
}

async function retryNotificationDelivery(button) {
    const delivery = state.notificationDeliveries.items.find(item => String(item.id) === button.dataset.retryNotificationDelivery);
    if (delivery?.pendingExpired && !window.confirm("这条通知已等待超过 24 小时，重发会向原渠道发送历史消息。确认重发？")) return;
    button.disabled = true;
    setFeedback("notification-delivery-feedback", "正在重新发送 Webhook...", "");
    try {
        await fetchJson(`/api/admin/notifications/deliveries/${encodeURIComponent(button.dataset.retryNotificationDelivery)}/retry`, {method: "POST"});
        await loadNotificationDeliveries();
        setFeedback("notification-delivery-feedback", "已提交重发，请稍后刷新查看结果。", "is-success");
    } catch (error) {
        button.disabled = false;
        setFeedback("notification-delivery-feedback", error.message, "is-error");
    }
}

async function deleteNotificationDelivery(button) {
    if (!confirmDangerAction(button, "确认删除")) {
        return;
    }
    button.disabled = true;
    setFeedback("notification-delivery-feedback", "正在删除发送记录...", "");
    try {
        await fetchJson(`/api/admin/notifications/deliveries/${encodeURIComponent(button.dataset.deleteNotificationDelivery)}`, {method: "DELETE"});
        await reloadNotificationDeliveriesAfterDelete();
        setFeedback("notification-delivery-feedback", "发送记录已删除。", "is-success");
    } catch (error) {
        resetDangerAction(button);
        button.disabled = false;
        setFeedback("notification-delivery-feedback", error.message, "is-error");
    }
}

async function reloadNotificationDeliveriesAfterDelete() {
    const remainingItems = Math.max(0, (state.notificationDeliveries.items || []).length - 1);
    if (remainingItems === 0 && state.notificationDeliveries.page > 1) {
        state.notificationDeliveries.page -= 1;
    }
    await loadNotificationDeliveries();
}

function renderNotificationDeliveryEvent(delivery) {
    const eventType = delivery.eventType || "";
    const eventKey = delivery.eventKey || "";
    const eventLabel = notificationEventLabel(eventType);
    const targetLabel = notificationEventTargetLabel(eventType, eventKey);
    const title = eventKey ? `事件标识：${eventKey}` : eventType;
    return `
        <span title="${escapeAttribute(eventType)}">${escapeHtml(eventLabel || "-")}</span>
        <div class="keyline" title="${escapeAttribute(title)}">${escapeHtml(targetLabel || "关联对象未知")}</div>
    `;
}

function notificationEventLabel(eventType) {
    const schema = state.notificationEvents.find((event) => event.eventType === eventType);
    return schema?.label || notificationEventTypeFallbackLabel(eventType);
}

function notificationEventTypeFallbackLabel(eventType) {
    return {
        SHARE_SUMMARY_IMAGE_SUCCESS: "分享总结图片生成成功",
        SHARE_SUMMARY_IMAGE_FAILED: "分享总结图片生成失败",
        SHARE_SUMMARY_AUDIO_FAILED: "分享总结音频生成失败",
        AI_PROVIDER_REQUEST_FAILED: "AI Provider 请求失败",
        AI_PROVIDER_AUTO_DOWNGRADED: "AI Provider 失败阈值降级",
        DATA_CRAWL_REQUEST_FAILED: "数据爬取请求失败"
    }[eventType] || eventType;
}

function notificationEventTargetLabel(eventType, eventKey) {
    const targetId = eventKeyTargetId(eventType, eventKey);
    if (eventType === "SHARE_SUMMARY_IMAGE_SUCCESS" && targetId) {
        return `分享图记录 #${targetId}`;
    }
    if (eventType === "SHARE_SUMMARY_IMAGE_FAILED" && targetId) {
        return `分享图记录 #${targetId}`;
    }
    if (eventType === "SHARE_SUMMARY_AUDIO_FAILED" && targetId) {
        return `音频记录 #${targetId}`;
    }
    if (eventType === "AI_PROVIDER_REQUEST_FAILED" && targetId) {
        return `AI Provider #${targetId}`;
    }
    if (eventType === "AI_PROVIDER_AUTO_DOWNGRADED" && targetId) {
        return `AI Provider #${targetId}`;
    }
    if (eventType === "DATA_CRAWL_REQUEST_FAILED" && targetId) {
        return `预览 ${shortPreviewKey(targetId)}`;
    }
    return eventKey || "";
}

function eventKeyTargetId(eventType, eventKey) {
    const prefix = `${eventType}:`;
    if (!eventType || !eventKey || !eventKey.startsWith(prefix)) {
        return "";
    }
    return eventKey.slice(prefix.length);
}

function renderNotificationDeliveryPagination(payload) {
    const page = Number(payload.page || 1);
    const totalPages = Number(payload.totalPages || 0);
    const total = Number(payload.total || 0);
    document.getElementById("notification-delivery-page-info").textContent = totalPages > 0
            ? `第 ${page} / ${totalPages} 页 · ${total} 条`
            : "暂无记录";
    document.getElementById("notification-delivery-prev").disabled = page <= 1;
    document.getElementById("notification-delivery-next").disabled = totalPages === 0 || page >= totalPages;
}

export function renderNotificationStatus(status) {
    const normalized = status || "-";
    const className = normalized === "SUCCESS" ? "is-success" : (normalized === "FAILED" || normalized === "EXPIRED_PENDING") ? "is-warning" : "";
    const labels = {PENDING: "等待发送", SUCCESS: "已送达", FAILED: "投递失败", EXPIRED_PENDING: "过期待处理"};
    return `<span class="status-pill ${className}" title="${escapeAttribute(normalized)}">${escapeHtml(labels[normalized] || normalized)}</span>`;
}
