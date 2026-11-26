// Admin entry point: wires every panel and starts the session check.
import {bindAiForm, bindAiProviderDowngradeConfig} from "./features/ai-providers.js";
import {bindLogs} from "./features/logs.js";
import {bindNotifications} from "./features/notifications/panel.js";
import {bindPreviewEvents} from "./features/preview-events.js";
import {bindAiTitleConfig, bindPromptForm} from "./features/prompts.js";
import {bindProviderForm, bindPurge} from "./features/provider-config.js";
import {bindShareSummary} from "./features/share-summary/runs.js";
import {bindModalClose} from "./shell/modals.js";
import {bindAdminNavigation} from "./shell/navigation.js";
import {bindLogout, checkSession} from "./shell/session.js";

function init() {
    bindAdminNavigation();
    bindLogout();
    bindPurge();
    bindPromptForm();
    bindAiTitleConfig();
    bindProviderForm();
    bindAiProviderDowngradeConfig();
    bindAiForm();
    bindPreviewEvents();
    bindShareSummary();
    bindNotifications();
    bindLogs();
    bindModalClose();
    checkSession();
}

init();
