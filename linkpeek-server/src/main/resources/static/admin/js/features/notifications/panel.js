// Notifications panel wiring and event list loading.
import {fetchJson} from "../../core/api.js";
import {state} from "../../core/state.js";
import {
    addNotificationHeaderRow,
    closeNotificationChannelModal,
    loadNotificationChannels,
    openNotificationChannelModalForCreate,
    saveNotificationChannel,
} from "./channels.js";
import {loadNotificationDeliveries} from "./deliveries.js";
import {
    closeNotificationTaskModal,
    loadNotificationTasks,
    openNotificationTaskModalForCreate,
    renderNotificationEventOptions,
    saveNotificationTask,
    updateNotificationTaskEventTypeState,
    validateNotificationTemplate,
} from "./tasks.js";

export function bindNotifications() {
    document.getElementById("notification-channel-new-button").addEventListener("click", openNotificationChannelModalForCreate);
    document.getElementById("notification-task-new-button").addEventListener("click", openNotificationTaskModalForCreate);
    document.getElementById("notification-channel-cancel-button").addEventListener("click", closeNotificationChannelModal);
    document.getElementById("notification-task-cancel-button").addEventListener("click", closeNotificationTaskModal);
    document.getElementById("notification-channel-add-header").addEventListener("click", () => addNotificationHeaderRow());
    document.getElementById("notification-channel-form").addEventListener("submit", saveNotificationChannel);
    document.getElementById("notification-task-form").addEventListener("submit", saveNotificationTask);
    document.getElementById("notification-task-event-type").addEventListener("change", () => {
        updateNotificationTaskEventTypeState(true);
    });
    document.getElementById("notification-template-validate-button").addEventListener("click", validateNotificationTemplate);
    document.getElementById("notification-refresh-deliveries").addEventListener("click", loadNotificationDeliveries);
    document.getElementById("notification-delivery-filter-form").addEventListener("change", async () => {
        state.notificationDeliveries.page = 1;
        await loadNotificationDeliveries();
    });
    document.getElementById("notification-delivery-prev").addEventListener("click", async () => {
        if (state.notificationDeliveries.page <= 1) {
            return;
        }
        state.notificationDeliveries.page -= 1;
        await loadNotificationDeliveries();
    });
    document.getElementById("notification-delivery-next").addEventListener("click", async () => {
        if (state.notificationDeliveries.totalPages > 0 && state.notificationDeliveries.page >= state.notificationDeliveries.totalPages) {
            return;
        }
        state.notificationDeliveries.page += 1;
        await loadNotificationDeliveries();
    });
}

export async function loadNotifications() {
    await loadNotificationEvents();
    await loadNotificationChannels();
    await loadNotificationTasks();
    await loadNotificationDeliveries();
}

async function loadNotificationEvents() {
    state.notificationEvents = await fetchJson("/api/admin/notifications/events");
    renderNotificationEventOptions();
}
