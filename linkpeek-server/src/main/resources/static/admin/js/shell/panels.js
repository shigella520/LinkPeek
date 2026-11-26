// Lazy per-panel data loading.
import {state} from "../core/state.js";
import {setFeedback} from "../core/ui.js";
import {loadAiProviderDowngradeConfig, loadAiProviders} from "../features/ai-providers.js";
import {loadLogs} from "../features/logs.js";
import {loadNotifications} from "../features/notifications/panel.js";
import {loadPreviewEvents} from "../features/preview-events.js";
import {loadAiTitleConfig, loadPrompts} from "../features/prompts.js";
import {loadProviderConfig} from "../features/provider-config.js";
import {loadShareSummary} from "../features/share-summary/runs.js";

const adminPanelLoaders = {
    "prompts": {
        mode: "once",
        load: () => Promise.all([
            loadPrompts(),
            loadAiTitleConfig()
        ])
    },
    "ai-providers": {
        mode: "once",
        load: () => Promise.all([
            loadAiProviderDowngradeConfig(),
            loadAiProviders()
        ])
    },
    "preview-events": {
        mode: "always",
        load: loadPreviewEvents
    },
    "share-summary": {
        mode: "always",
        load: loadShareSummary
    },
    "notifications": {
        mode: "always",
        load: loadNotifications
    },
    "provider-config": {
        mode: "once",
        load: loadProviderConfig
    },
    "service-logs": {
        mode: "always",
        load: loadLogs
    },
    "purge": {
        mode: "none",
        load: () => Promise.resolve()
    }
};

export async function loadAdminPanel(panelId, options = {}) {
    const loader = adminPanelLoaders[panelId];
    if (!loader) {
        return;
    }
    const force = options.force === true || loader.mode === "always";
    if (!force && state.loadedPanels.has(panelId)) {
        return;
    }
    if (state.loadingPanels.has(panelId)) {
        return state.loadingPanels.get(panelId);
    }

    const loadPromise = Promise.resolve()
            .then(loader.load)
            .then(() => {
                state.loadedPanels.add(panelId);
            })
            .catch((error) => {
                state.loadedPanels.delete(panelId);
                setAdminPanelLoadError(panelId, error);
            })
            .finally(() => {
                state.loadingPanels.delete(panelId);
            });
    state.loadingPanels.set(panelId, loadPromise);
    return loadPromise;
}

function setAdminPanelLoadError(panelId, error) {
    const feedbackByPanel = {
        "prompts": "prompt-feedback",
        "ai-providers": "ai-feedback",
        "preview-events": "preview-event-feedback",
        "share-summary": "share-summary-feedback",
        "notifications": "notification-feedback",
        "provider-config": "provider-feedback",
        "service-logs": "log-feedback"
    };
    const feedbackId = feedbackByPanel[panelId];
    if (feedbackId) {
        setFeedback(feedbackId, error.message || "加载失败。", "is-error");
    }
}
