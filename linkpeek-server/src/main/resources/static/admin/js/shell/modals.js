// Global modal behaviour: focus trap, close buttons and Escape handling.
import {isVisibleModalControl} from "../core/ui.js";
import {closeAiModal} from "../features/ai-providers.js";
import {closeNotificationChannelModal} from "../features/notifications/channels.js";
import {closeNotificationTaskModal} from "../features/notifications/tasks.js";
import {closePromptModal} from "../features/prompts.js";
import {
    closeShareSummaryAudioConfigModal,
    closeShareSummaryImageConfigModal,
} from "../features/share-summary/asset-config.js";
import {closeShareSummaryImageViewer, closeShareSummaryRunModal} from "../features/share-summary/run-detail.js";
import {closeShareSummaryTaskModal} from "../features/share-summary/tasks.js";

export function bindModalClose() {
    document.addEventListener("keydown", event => {
        if (event.key !== "Tab") return;
        const modal = Array.from(document.querySelectorAll(".modal-shell:not([hidden])")).at(-1);
        if (!modal) return;
        const focusable = Array.from(modal.querySelectorAll("button, input, select, textarea, a[href], summary"))
            .filter(node => isVisibleModalControl(node, modal));
        const first = focusable[0], last = focusable.at(-1);
        if (!first) { event.preventDefault(); return; }
        if (event.shiftKey && (document.activeElement === first || !modal.contains(document.activeElement))) {
            event.preventDefault(); last.focus();
        } else if (!event.shiftKey && (document.activeElement === last || !modal.contains(document.activeElement))) {
            event.preventDefault(); first.focus();
        }
    });
    document.querySelectorAll("[data-close-modal]").forEach((node) => {
        node.addEventListener("click", () => {
            if (node.dataset.closeModal === "prompt") {
                closePromptModal();
                return;
            }
            if (node.dataset.closeModal === "ai") {
                closeAiModal();
                return;
            }
            if (node.dataset.closeModal === "share-summary-task") {
                closeShareSummaryTaskModal();
                return;
            }
            if (node.dataset.closeModal === "share-summary-image-config") {
                closeShareSummaryImageConfigModal();
                return;
            }
            if (node.dataset.closeModal === "share-summary-audio-config") {
                closeShareSummaryAudioConfigModal();
                return;
            }
            if (node.dataset.closeModal === "share-summary-run") {
                closeShareSummaryRunModal();
                return;
            }
            if (node.dataset.closeModal === "notification-channel") {
                closeNotificationChannelModal();
                return;
            }
            if (node.dataset.closeModal === "notification-task") {
                closeNotificationTaskModal();
            }
        });
    });
    document.addEventListener("keydown", (event) => {
        if (event.key !== "Escape") {
            return;
        }
        if (!document.getElementById("share-summary-image-viewer").hidden) {
            closeShareSummaryImageViewer();
            return;
        }
        if (!document.getElementById("prompt-modal").hidden) {
            closePromptModal();
            return;
        }
        if (!document.getElementById("ai-modal").hidden) {
            closeAiModal();
            return;
        }
        if (!document.getElementById("share-summary-task-modal").hidden) {
            closeShareSummaryTaskModal();
            return;
        }
        if (!document.getElementById("share-summary-image-config-modal").hidden) {
            closeShareSummaryImageConfigModal();
            return;
        }
        if (!document.getElementById("share-summary-audio-config-modal").hidden) {
            closeShareSummaryAudioConfigModal();
            return;
        }
        if (!document.getElementById("share-summary-run-modal").hidden) {
            closeShareSummaryRunModal();
            return;
        }
        if (!document.getElementById("notification-channel-modal").hidden) {
            closeNotificationChannelModal();
            return;
        }
        if (!document.getElementById("notification-task-modal").hidden) {
            closeNotificationTaskModal();
        }
    });
}
