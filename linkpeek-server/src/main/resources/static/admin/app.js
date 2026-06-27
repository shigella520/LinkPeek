(function () {
    const FREESTYLE_STYLE = "FREESTYLE";
    const CONFIRM_TIMEOUT_MS = 5000;
    const NOTIFICATION_FILTER_PERIOD_TYPES = [
        {value: "DAILY", label: "每日"},
        {value: "WEEKLY", label: "每周"},
        {value: "MONTHLY", label: "每月"}
    ];
    const NOTIFICATION_FILTER_TRIGGER_TYPES = [
        {value: "SCHEDULED", label: "定时"},
        {value: "MANUAL", label: "手动"}
    ];
    const MIMO_TTS_PRESET_MODEL = "mimo-v2.5-tts";
    const MIMO_TTS_DEFAULT_VOICE = "苏打";
    const MIMO_TTS_DEFAULT_STYLE = "四川话";

    const state = {
        prompts: [],
        aiProviders: [],
        aiProviderDowngradeConfig: {},
        defaultTitleFormatPrompt: "",
        previewEvents: {
            items: [],
            page: 1,
            size: 20,
            total: 0,
            totalPages: 0
        },
        shareSummaryTasks: [],
        shareSummaryRuns: {
            items: [],
            page: 1,
            size: 20,
            total: 0,
            totalPages: 0
        },
        shareSummaryImageConfig: {},
        shareSummaryAudioConfig: {},
        notificationEvents: [],
        notificationChannels: [],
        notificationTasks: [],
        notificationDeliveries: {
            items: [],
            page: 1,
            size: 20,
            total: 0,
            totalPages: 0
        },
        activeShareSummaryRunId: null,
        aiProviderDowngradeSaveTimer: null,
        aiProviderDowngradeSaveVersion: 0,
        logRefreshTimer: null,
        activeDangerButton: null,
        adminReady: false,
        loadedPanels: new Set(),
        loadingPanels: new Map(),
        adminNavHoverOpen: false,
        adminNavHoverCloseTimer: null,
        adminNavLastPointer: null
    };

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

    function bindAdminNavigation() {
        const menuButton = document.getElementById("admin-menu-button");
        const drawer = document.getElementById("admin-nav-drawer");
        const closeButton = document.getElementById("admin-drawer-close-button");
        const backdrop = document.getElementById("admin-drawer-backdrop");
        const pinButton = document.getElementById("admin-pin-sidebar-button");

        menuButton.addEventListener("click", () => openAdminNavigation({source: "click"}));
        menuButton.addEventListener("mouseenter", openAdminNavigationFromHover);
        menuButton.addEventListener("mouseleave", scheduleCloseAdminNavigationFromHover);
        drawer.addEventListener("mouseenter", cancelAdminNavigationHoverClose);
        drawer.addEventListener("mouseleave", scheduleCloseAdminNavigationFromHover);
        closeButton.addEventListener("click", closeAdminNavigation);
        backdrop.addEventListener("click", closeAdminNavigation);
        pinButton.addEventListener("click", () => {
            setAdminSidebarPinned(!isAdminSidebarPinned(), true);
        });
        document.querySelectorAll("[data-admin-nav-target]").forEach((button) => {
            button.addEventListener("click", () => {
                activateAdminPanel(button.dataset.adminNavTarget, true);
                if (!isAdminSidebarPinnedActive()) {
                    closeAdminNavigation();
                }
            });
        });
        window.addEventListener("hashchange", () => {
            activateAdminPanel(panelIdFromLocation(), false);
        });
        window.addEventListener("resize", syncAdminNavigationMode);
        document.addEventListener("keydown", (event) => {
            if (event.key === "Escape" && document.body.classList.contains("admin-nav-open")) {
                closeAdminNavigation();
            }
        });
        setAdminSidebarPinned(localStorage.getItem(pinnedSidebarStorageKey()) === "true", false);
        activateAdminPanel(panelIdFromLocation(), false);
    }

    function openAdminNavigation(options = {}) {
        const drawer = document.getElementById("admin-nav-drawer");
        const menuButton = document.getElementById("admin-menu-button");
        const backdrop = document.getElementById("admin-drawer-backdrop");
        const hoverOpen = options.source === "hover";
        cancelAdminNavigationHoverClose();
        state.adminNavHoverOpen = hoverOpen;
        document.body.classList.add("admin-nav-open");
        document.body.classList.toggle("admin-nav-hover-open", hoverOpen);
        drawer.setAttribute("aria-hidden", "false");
        drawer.removeAttribute("inert");
        menuButton.setAttribute("aria-expanded", "true");
        backdrop.hidden = hoverOpen || isAdminSidebarPinnedActive();
    }

    function closeAdminNavigation() {
        const drawer = document.getElementById("admin-nav-drawer");
        const menuButton = document.getElementById("admin-menu-button");
        const backdrop = document.getElementById("admin-drawer-backdrop");
        cancelAdminNavigationHoverClose();
        state.adminNavHoverOpen = false;
        document.body.classList.remove("admin-nav-open");
        document.body.classList.remove("admin-nav-hover-open");
        if (isAdminSidebarPinnedActive()) {
            drawer.setAttribute("aria-hidden", "false");
            drawer.removeAttribute("inert");
        } else {
            drawer.setAttribute("aria-hidden", "true");
            drawer.setAttribute("inert", "");
        }
        menuButton.setAttribute("aria-expanded", "false");
        backdrop.hidden = true;
    }

    function openAdminNavigationFromHover(event) {
        rememberAdminNavigationPointer(event);
        if (!canHoverAdminNavigation() || isAdminSidebarPinnedActive()) {
            return;
        }
        openAdminNavigation({source: "hover"});
    }

    function scheduleCloseAdminNavigationFromHover(event) {
        rememberAdminNavigationPointer(event);
        if (!state.adminNavHoverOpen || isAdminSidebarPinnedActive()) {
            return;
        }
        cancelAdminNavigationHoverClose();
        state.adminNavHoverCloseTimer = window.setTimeout(() => {
            state.adminNavHoverCloseTimer = null;
            if (state.adminNavHoverOpen && !isAdminNavigationPointerInside()) {
                closeAdminNavigation();
            }
        }, 160);
    }

    function cancelAdminNavigationHoverClose() {
        if (state.adminNavHoverCloseTimer) {
            window.clearTimeout(state.adminNavHoverCloseTimer);
            state.adminNavHoverCloseTimer = null;
        }
    }

    function canHoverAdminNavigation() {
        return window.matchMedia("(hover: hover) and (pointer: fine)").matches;
    }

    function rememberAdminNavigationPointer(event) {
        if (!event || !Number.isFinite(event.clientX) || !Number.isFinite(event.clientY)) {
            return;
        }
        state.adminNavLastPointer = {
            x: event.clientX,
            y: event.clientY
        };
    }

    function isAdminNavigationPointerInside() {
        if (!state.adminNavLastPointer) {
            return false;
        }
        return isPointInsideElement(state.adminNavLastPointer, document.getElementById("admin-menu-button"))
                || isPointInsideElement(state.adminNavLastPointer, document.getElementById("admin-nav-drawer"));
    }

    function isPointInsideElement(point, element) {
        if (!element || element.hidden) {
            return false;
        }
        const rect = element.getBoundingClientRect();
        return point.x >= rect.left
                && point.x <= rect.right
                && point.y >= rect.top
                && point.y <= rect.bottom;
    }

    function setAdminSidebarPinned(pinned, persist) {
        const drawer = document.getElementById("admin-nav-drawer");
        const menuButton = document.getElementById("admin-menu-button");
        const backdrop = document.getElementById("admin-drawer-backdrop");
        const pinButton = document.getElementById("admin-pin-sidebar-button");
        document.body.classList.toggle("admin-sidebar-pinned", pinned);
        pinButton.setAttribute("aria-pressed", pinned ? "true" : "false");
        pinButton.setAttribute("aria-label", pinned ? "取消固定边栏" : "固定边栏");
        pinButton.title = pinned ? "取消固定边栏" : "固定边栏";
        if (persist) {
            localStorage.setItem(pinnedSidebarStorageKey(), pinned ? "true" : "false");
        }
        syncAdminNavigationMode();
    }

    function isAdminSidebarPinned() {
        return document.body.classList.contains("admin-sidebar-pinned");
    }

    function isAdminSidebarPinnedActive() {
        return isAdminSidebarPinned() && window.matchMedia("(min-width: 720px)").matches;
    }

    function syncAdminNavigationMode() {
        const drawer = document.getElementById("admin-nav-drawer");
        const menuButton = document.getElementById("admin-menu-button");
        const backdrop = document.getElementById("admin-drawer-backdrop");
        const pinnedActive = isAdminSidebarPinnedActive();
        if (pinnedActive) {
            cancelAdminNavigationHoverClose();
            state.adminNavHoverOpen = false;
            document.body.classList.remove("admin-nav-open");
            document.body.classList.remove("admin-nav-hover-open");
            drawer.setAttribute("aria-hidden", "false");
            drawer.removeAttribute("inert");
            menuButton.setAttribute("aria-expanded", "false");
            backdrop.hidden = true;
            return;
        }

        const drawerOpen = document.body.classList.contains("admin-nav-open");
        if (!drawerOpen) {
            state.adminNavHoverOpen = false;
            document.body.classList.remove("admin-nav-hover-open");
        }
        drawer.setAttribute("aria-hidden", drawerOpen ? "false" : "true");
        drawer.toggleAttribute("inert", !drawerOpen);
        menuButton.setAttribute("aria-expanded", drawerOpen ? "true" : "false");
        backdrop.hidden = !drawerOpen || state.adminNavHoverOpen;
    }

    function activateAdminPanel(panelId, updateHash) {
        const panel = panelById(panelId) || panelById(defaultPanelId());
        if (!panel) {
            return;
        }

        document.querySelectorAll("#admin-shell .workspace > .panel").forEach((candidate) => {
            candidate.hidden = candidate !== panel;
        });
        document.querySelectorAll("[data-admin-nav-target]").forEach((button) => {
            const active = button.dataset.adminNavTarget === panel.id;
            button.classList.toggle("is-active", active);
            button.setAttribute("aria-current", active ? "page" : "false");
        });
        localStorage.setItem(activePanelStorageKey(), panel.id);

        if (updateHash && window.location.hash !== `#${panel.id}`) {
            history.pushState(null, "", `#${panel.id}`);
        }

        syncLogAutoRefreshForActivePanel(panel.id);
        if (state.adminReady) {
            loadAdminPanel(panel.id);
        }
    }

    function panelById(panelId) {
        if (!panelId) {
            return null;
        }
        return document.getElementById(panelId);
    }

    function panelIdFromLocation() {
        const hashPanelId = window.location.hash.replace(/^#/, "");
        if (panelById(hashPanelId)) {
            return hashPanelId;
        }
        const storedPanelId = localStorage.getItem(activePanelStorageKey());
        if (panelById(storedPanelId)) {
            return storedPanelId;
        }
        return defaultPanelId();
    }

    function defaultPanelId() {
        return "preview-events";
    }

    function activePanelStorageKey() {
        return "linkpeek.admin.activePanel";
    }

    function pinnedSidebarStorageKey() {
        return "linkpeek.admin.sidebarPinned";
    }

    async function checkSession() {
        const session = await fetchJson("/api/admin/session", {method: "GET"}, false);
        if (!session || !session.enabled) {
            redirectToLogin();
            return;
        }
        if (session.authenticated) {
            showAdmin();
            state.adminReady = true;
            const activePanelId = panelIdFromLocation();
            syncLogAutoRefreshForActivePanel(activePanelId);
            await loadAdminPanel(activePanelId, {force: true});
            return;
        }
        redirectToLogin();
    }

    function bindLogout() {
        document.getElementById("logout-button").addEventListener("click", async () => {
            await fetchJson("/api/admin/logout", {method: "POST"}, false);
            window.location.href = "/admin/login";
        });
    }

    function bindPurge() {
        document.getElementById("purge-button").addEventListener("click", async () => {
            const button = document.getElementById("purge-button");
            if (!confirmDangerAction(button, "确认清理")) {
                return;
            }
            button.disabled = true;
            setFeedback("purge-feedback", "正在清理...", "");
            try {
                const result = await fetchJson("/api/admin/stats/purge-all", {method: "POST"});
                setFeedback("purge-feedback", `已清理 ${result.deletedEvents || 0} 条事件，${result.deletedLinks || 0} 条链接。`, "is-success");
            } catch (error) {
                setFeedback("purge-feedback", error.message, "is-error");
            } finally {
                resetDangerAction(button);
                button.disabled = false;
            }
        });
    }

    function bindPromptForm() {
        document.getElementById("prompt-new-button").addEventListener("click", openPromptModalForCreate);
        document.getElementById("prompt-cancel-button").addEventListener("click", closePromptModal);
        document.getElementById("prompt-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            const style = document.getElementById("prompt-style").value.trim();
            const prompt = document.getElementById("prompt-text").value.trim();
            const normalizedStyle = style.toUpperCase();
            if (normalizedStyle === FREESTYLE_STYLE) {
                setFeedback("prompt-modal-feedback", "FREESTYLE 是系统保留模式，不能作为 Style Key。", "is-error");
                document.getElementById("prompt-style").focus();
                return;
            }
            setFeedback("prompt-modal-feedback", "正在保存 Style Prompt...", "");
            try {
                await fetchJson(`/api/admin/prompts/${encodeURIComponent(normalizedStyle)}`, {
                    method: "PUT",
                    body: JSON.stringify({prompt})
                });
                closePromptModal(false);
                await loadPrompts();
                setFeedback("prompt-feedback", "Style Prompt 已保存。", "is-success");
            } catch (error) {
                setFeedback("prompt-modal-feedback", error.message, "is-error");
            }
        });
    }

    function bindAiTitleConfig() {
        document.getElementById("ai-title-config-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            const titleFormatPrompt = document.getElementById("ai-title-format-prompt").value.trim();
            setFeedback("ai-title-config-feedback", "正在保存标题格式...", "");
            try {
                await fetchJson("/api/admin/ai-title-config", {
                    method: "PUT",
                    body: JSON.stringify({titleFormatPrompt})
                });
                await loadAiTitleConfig();
                setFeedback("ai-title-config-feedback", "标题格式已保存。", "is-success");
            } catch (error) {
                setFeedback("ai-title-config-feedback", error.message, "is-error");
            }
        });
        document.getElementById("ai-title-format-default-button").addEventListener("click", () => {
            document.getElementById("ai-title-format-prompt").value = state.defaultTitleFormatPrompt || "";
            setFeedback("ai-title-config-feedback", "已填入默认标题格式，保存后生效。", "");
        });
    }


    function bindProviderForm() {
        document.getElementById("provider-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            const valuesByProvider = {};
            document.querySelectorAll("[data-provider][data-key]").forEach((input) => {
                valuesByProvider[input.dataset.provider] ||= {};
                valuesByProvider[input.dataset.provider][input.dataset.key] = providerInputValue(input);
            });
            setFeedback("provider-feedback", "正在保存 Provider 配置...", "");
            try {
                await Promise.all(Object.entries(valuesByProvider).map(([providerId, values]) => {
                    return fetchJson(`/api/admin/provider-config/${encodeURIComponent(providerId)}`, {
                        method: "PUT",
                        body: JSON.stringify({values})
                    });
                }));
                await loadProviderConfig();
                setFeedback("provider-feedback", "Provider 配置已保存。", "is-success");
            } catch (error) {
                setFeedback("provider-feedback", error.message, "is-error");
            }
        });
    }

    function bindAiProviderDowngradeConfig() {
        const form = document.getElementById("ai-downgrade-config-form");
        const enabledToggle = document.getElementById("ai-auto-downgrade-enabled-toggle");
        const thresholdInput = document.getElementById("ai-auto-downgrade-failure-threshold");
        const shareSummaryTimeoutMultiplierInput = document.getElementById("ai-share-summary-timeout-multiplier");
        form.addEventListener("submit", (event) => {
            event.preventDefault();
            scheduleAiProviderDowngradeSave();
        });
        enabledToggle.addEventListener("click", () => {
            setAiProviderDowngradeEnabled(enabledToggle.dataset.enabled !== "true");
            scheduleAiProviderDowngradeSave();
        });
        thresholdInput.addEventListener("input", scheduleAiProviderDowngradeSave);
        shareSummaryTimeoutMultiplierInput.addEventListener("input", scheduleAiProviderDowngradeSave);
    }

    function bindAiForm() {
        document.getElementById("ai-new-button").addEventListener("click", openAiFormForCreate);
        document.getElementById("ai-cancel-button").addEventListener("click", closeAiModal);
        document.getElementById("ai-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            const id = document.getElementById("ai-id").value;
            const requestTimeoutSeconds = Number(document.getElementById("ai-request-timeout-seconds").value || 0);
            const payload = {
                name: document.getElementById("ai-name").value.trim(),
                baseUrl: document.getElementById("ai-base-url").value.trim(),
                apiKind: document.getElementById("ai-api-kind").value,
                model: document.getElementById("ai-model").value.trim(),
                effort: document.getElementById("ai-effort").value.trim(),
                requestTimeoutSeconds,
                apiKey: document.getElementById("ai-api-key").value.trim()
            };
            if (!Number.isInteger(requestTimeoutSeconds) || requestTimeoutSeconds < 1 || requestTimeoutSeconds > 600) {
                setFeedback("ai-modal-feedback", "AI 请求超时必须是 1-600 秒之间的整数。", "is-error");
                document.getElementById("ai-request-timeout-seconds").focus();
                return;
            }
            const baseUrlError = validateAiBaseUrl(payload.baseUrl);
            if (baseUrlError) {
                setFeedback("ai-modal-feedback", baseUrlError, "is-error");
                document.getElementById("ai-base-url").focus();
                return;
            }
            const url = id ? `/api/admin/ai-providers/${encodeURIComponent(id)}` : "/api/admin/ai-providers";
            const method = id ? "PUT" : "POST";
            setFeedback("ai-modal-feedback", "正在保存 AI Provider...", "");
            try {
                await fetchJson(url, {
                    method,
                    body: JSON.stringify(payload)
                });
                closeAiModal(false);
                await loadAiProviders();
                setFeedback("ai-feedback", "AI Provider 已保存。", "is-success");
            } catch (error) {
                setFeedback("ai-modal-feedback", error.message, "is-error");
            }
        });
    }

    function bindLogs() {
        document.getElementById("log-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            await loadLogs();
        });
        document.getElementById("log-auto-refresh").addEventListener("change", updateLogAutoRefresh);
    }

    function bindPreviewEvents() {
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

    function bindShareSummary() {
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

    function bindNotifications() {
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

    function bindModalClose() {
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

    async function loadAdminPanel(panelId, options = {}) {
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

    async function loadAll() {
        await Promise.all(Object.keys(adminPanelLoaders).map((panelId) => loadAdminPanel(panelId, {force: true})));
    }

    async function loadPrompts() {
        state.prompts = await fetchJson("/api/admin/prompts");
        renderPrompts();
    }

    async function loadAiTitleConfig() {
        const payload = await fetchJson("/api/admin/ai-title-config");
        state.defaultTitleFormatPrompt = payload.defaultTitleFormatPrompt || "";
        document.getElementById("ai-title-format-prompt").value = payload.titleFormatPrompt || "";
    }

    async function loadProviderConfig() {
        const payload = await fetchJson("/api/admin/provider-config");
        const configs = payload.configs || {};
        document.querySelectorAll("[data-provider][data-key]").forEach((input) => {
            const value = (configs[input.dataset.provider] || {})[input.dataset.key];
            if (input.type === "checkbox") {
                input.checked = providerCheckboxChecked(value);
                return;
            }
            input.value = value || "";
        });
    }

    function providerInputValue(input) {
        if (input.type === "checkbox") {
            return input.checked ? "true" : "false";
        }
        return input.value.trim();
    }

    function providerCheckboxChecked(value) {
        if (value == null || value === "") {
            return true;
        }
        return !["false", "0", "off", "no"].includes(String(value).trim().toLowerCase());
    }

    async function loadAiProviders() {
        state.aiProviders = await fetchJson("/api/admin/ai-providers");
        renderAiProviders();
    }

    async function loadAiProviderDowngradeConfig() {
        state.aiProviderDowngradeConfig = await fetchJson("/api/admin/ai-provider-downgrade-config");
        renderAiProviderDowngradeConfig();
    }

    async function loadPreviewEvents() {
        const params = new URLSearchParams();
        params.set("page", String(state.previewEvents.page || 1));
        params.set("size", document.getElementById("preview-event-size").value || "20");
        const query = document.getElementById("preview-event-query").value.trim();
        if (query) {
            params.set("q", query);
        }

        setFeedback("preview-event-feedback", "正在读取链接创建记录...", "");
        try {
            state.previewEvents = await fetchJson(`/api/admin/preview-events?${params.toString()}`);
            renderPreviewEvents();
            setFeedback("preview-event-feedback", `已加载 ${state.previewEvents.items.length} 条，共 ${state.previewEvents.total} 条。`, "is-success");
        } catch (error) {
            setFeedback("preview-event-feedback", error.message, "is-error");
        }
    }

    async function loadShareSummary() {
        await loadShareSummaryImageConfig();
        await loadShareSummaryAudioConfig();
        await loadShareSummaryTasks();
        await loadShareSummaryRuns();
    }

    async function loadShareSummaryImageConfig() {
        state.shareSummaryImageConfig = await fetchJson("/api/admin/share-summary/image-config");
    }

    async function loadShareSummaryAudioConfig() {
        state.shareSummaryAudioConfig = await fetchJson("/api/admin/share-summary/audio-config");
    }

    async function loadShareSummaryTasks() {
        state.shareSummaryTasks = await fetchJson("/api/admin/share-summary/tasks");
        renderShareSummaryTasks();
        renderShareSummaryTaskFilterOptions();
    }

    async function loadShareSummaryRuns() {
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
            state.shareSummaryRuns = await fetchJson(`/api/admin/share-summary/runs?${params.toString()}`);
            renderShareSummaryRuns();
            setFeedback("share-summary-history-feedback", `已加载 ${state.shareSummaryRuns.items.length} 条，共 ${state.shareSummaryRuns.total} 条。`, "is-success");
        } catch (error) {
            setFeedback("share-summary-history-feedback", error.message, "is-error");
        }
    }

    async function loadNotifications() {
        await loadNotificationEvents();
        await loadNotificationChannels();
        await loadNotificationTasks();
        await loadNotificationDeliveries();
    }

    async function loadNotificationEvents() {
        state.notificationEvents = await fetchJson("/api/admin/notifications/events");
        renderNotificationEventOptions();
    }

    async function loadNotificationChannels() {
        state.notificationChannels = await fetchJson("/api/admin/notifications/channels");
        renderNotificationChannels();
        renderNotificationChannelOptions();
    }

    async function loadNotificationTasks() {
        state.notificationTasks = await fetchJson("/api/admin/notifications/tasks");
        renderNotificationTasks();
        renderNotificationTaskOptions();
    }

    async function loadNotificationDeliveries() {
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
            state.notificationDeliveries = await fetchJson(`/api/admin/notifications/deliveries?${params.toString()}`);
            renderNotificationDeliveries();
            setFeedback("notification-delivery-feedback", `已加载 ${state.notificationDeliveries.items.length} 条，共 ${state.notificationDeliveries.total} 条。`, "is-success");
        } catch (error) {
            setFeedback("notification-delivery-feedback", error.message, "is-error");
        }
    }

    function scheduleAiProviderDowngradeSave() {
        state.aiProviderDowngradeSaveVersion += 1;
        const saveVersion = state.aiProviderDowngradeSaveVersion;
        if (state.aiProviderDowngradeSaveTimer) {
            window.clearTimeout(state.aiProviderDowngradeSaveTimer);
            state.aiProviderDowngradeSaveTimer = null;
        }

        const payload = readAiProviderDowngradePayload();
        if (!payload) {
            setFeedback("ai-downgrade-config-feedback", "失败阈值降级次数必须是 1-100 的整数，分享总结超时倍率必须是 1-20。", "is-error");
            return;
        }

        setFeedback("ai-downgrade-config-feedback", "将在 2 秒后自动保存...", "");
        state.aiProviderDowngradeSaveTimer = window.setTimeout(() => {
            state.aiProviderDowngradeSaveTimer = null;
            saveAiProviderDowngradeConfig(payload, saveVersion);
        }, 2000);
    }

    function readAiProviderDowngradePayload() {
        const autoDowngradeEnabled = document.getElementById("ai-auto-downgrade-enabled-toggle").dataset.enabled === "true";
        const autoDowngradeFailureThreshold = Number(document.getElementById("ai-auto-downgrade-failure-threshold").value || 0);
        const shareSummaryTimeoutMultiplier = Number(document.getElementById("ai-share-summary-timeout-multiplier").value || 0);
        if (!Number.isInteger(autoDowngradeFailureThreshold) || autoDowngradeFailureThreshold < 1 || autoDowngradeFailureThreshold > 100) {
            return null;
        }
        if (!Number.isFinite(shareSummaryTimeoutMultiplier) || shareSummaryTimeoutMultiplier < 1 || shareSummaryTimeoutMultiplier > 20) {
            return null;
        }
        return {autoDowngradeEnabled, autoDowngradeFailureThreshold, shareSummaryTimeoutMultiplier};
    }

    async function saveAiProviderDowngradeConfig(payload, saveVersion) {
        setFeedback("ai-downgrade-config-feedback", "正在保存自动降级配置...", "");
        try {
            const response = await fetchJson("/api/admin/ai-provider-downgrade-config", {
                method: "PUT",
                body: JSON.stringify(payload)
            });
            if (saveVersion !== state.aiProviderDowngradeSaveVersion) {
                return;
            }
            state.aiProviderDowngradeConfig = response;
            renderAiProviderDowngradeConfig();
            setFeedback("ai-downgrade-config-feedback", "自动降级配置已保存。", "is-success");
        } catch (error) {
            if (saveVersion !== state.aiProviderDowngradeSaveVersion) {
                return;
            }
            setFeedback("ai-downgrade-config-feedback", error.message, "is-error");
        }
    }

    async function loadLogs() {
        const params = new URLSearchParams();
        params.set("lines", document.getElementById("log-lines").value || "300");
        const level = document.getElementById("log-level").value;
        const query = document.getElementById("log-query").value.trim();
        if (level) {
            params.set("level", level);
        }
        if (query) {
            params.set("q", query);
        }

        setFeedback("log-feedback", "正在读取服务日志...", "");
        try {
            const result = await fetchJson(`/api/admin/logs?${params.toString()}`);
            renderLogs(result);
        } catch (error) {
            setFeedback("log-feedback", error.message, "is-error");
        }
    }

    function renderPrompts() {
        const body = document.getElementById("prompt-table");
        if (!state.prompts.length) {
            body.innerHTML = `<tr><td colspan="3" class="muted">暂无 Style Prompt</td></tr>`;
            return;
        }
        body.innerHTML = state.prompts.map((prompt) => `
            <tr>
                <td>${escapeHtml(prompt.style)}</td>
                <td><div class="prompt-preview">${escapeHtml(promptPreview(prompt.prompt))}</div></td>
                <td>
                    <div class="row-actions">
                        <button type="button" data-edit-prompt="${escapeHtml(prompt.style)}" class="secondary">编辑</button>
                        <button type="button" data-delete-prompt="${escapeHtml(prompt.style)}" class="danger">删除</button>
                    </div>
                </td>
            </tr>
        `).join("");
        body.querySelectorAll("[data-edit-prompt]").forEach((button) => {
            button.addEventListener("click", () => {
                const prompt = state.prompts.find((item) => item.style === button.dataset.editPrompt);
                if (prompt) {
                    openPromptModalForEdit(prompt);
                }
            });
        });
        body.querySelectorAll("[data-delete-prompt]").forEach((button) => {
            button.addEventListener("click", async () => {
                if (!confirmDangerAction(button, "确认删除")) {
                    return;
                }
                button.disabled = true;
                setFeedback("prompt-feedback", "正在删除 Style Prompt...", "");
                try {
                    await fetchJson(`/api/admin/prompts/${encodeURIComponent(button.dataset.deletePrompt)}`, {method: "DELETE"});
                    await loadPrompts();
                    setFeedback("prompt-feedback", "Style Prompt 已删除。", "is-success");
                } catch (error) {
                    resetDangerAction(button);
                    button.disabled = false;
                    setFeedback("prompt-feedback", error.message, "is-error");
                }
            });
        });
    }

    function renderAiProviders() {
        const body = document.getElementById("ai-table");
        if (!state.aiProviders.length) {
            body.innerHTML = `<tr><td colspan="8" class="muted">暂无 AI Provider</td></tr>`;
            return;
        }
        body.innerHTML = state.aiProviders.map((provider) => `
            <tr data-ai-row="${provider.id}">
                <td class="drag-cell">
                    <button type="button" class="drag-handle" data-drag-ai="${provider.id}" draggable="true" title="拖拽排序" aria-label="拖拽排序">↕</button>
                </td>
                <td class="ai-provider-name-cell">${escapeHtml(provider.name)}</td>
                <td class="ai-provider-base-url-cell">${escapeHtml(provider.baseUrl)}</td>
                <td class="ai-provider-format-cell">${escapeHtml(aiKindLabel(providerApiKind(provider)))}</td>
                <td class="ai-provider-model-cell">${escapeHtml(provider.model)}</td>
                <td class="ai-provider-timeout-cell">${providerRequestTimeoutLabel(provider)}</td>
                <td class="ai-provider-enabled-cell">
                    <label class="switch-row">
                        <input type="checkbox" data-toggle-ai="${provider.id}" ${provider.enabled ? "checked" : ""}>
                        <span class="switch-track" aria-hidden="true"><span class="switch-thumb"></span></span>
                        <span class="switch-text">${provider.enabled ? "启用" : "禁用"}</span>
                    </label>
                </td>
                <td class="ai-provider-action-cell">
                    <div class="row-actions ai-provider-actions">
                        <button type="button" data-test-ai="${provider.id}" class="secondary test-button">测试</button>
                        <button type="button" data-edit-ai="${provider.id}" class="secondary">编辑</button>
                        <button type="button" data-delete-ai="${provider.id}" class="danger">删除</button>
                    </div>
                </td>
            </tr>
        `).join("");
        bindAiDragSorting(body);
        body.querySelectorAll("[data-toggle-ai]").forEach((input) => {
            input.addEventListener("change", () => toggleAiProvider(input));
        });
        body.querySelectorAll("[data-test-ai]").forEach((button) => {
            button.addEventListener("click", () => testAiProvider(button));
        });
        body.querySelectorAll("[data-edit-ai]").forEach((button) => {
            button.addEventListener("click", () => openAiFormForEdit(Number(button.dataset.editAi)));
        });
        body.querySelectorAll("[data-delete-ai]").forEach((button) => {
            button.addEventListener("click", async () => {
                if (!confirmDangerAction(button, "确认删除")) {
                    return;
                }
                button.disabled = true;
                setFeedback("ai-feedback", "正在删除 AI Provider...", "");
                try {
                    await fetchJson(`/api/admin/ai-providers/${encodeURIComponent(button.dataset.deleteAi)}`, {method: "DELETE"});
                    if (document.getElementById("ai-id").value === button.dataset.deleteAi) {
                        closeAiModal(false);
                    }
                    await loadAiProviders();
                    setFeedback("ai-feedback", "AI Provider 已删除。", "is-success");
                } catch (error) {
                    resetDangerAction(button);
                    button.disabled = false;
                    setFeedback("ai-feedback", error.message, "is-error");
                }
            });
        });
    }

    function renderAiProviderDowngradeConfig() {
        const config = state.aiProviderDowngradeConfig || {};
        setAiProviderDowngradeEnabled(Boolean(config.autoDowngradeEnabled));
        document.getElementById("ai-auto-downgrade-failure-threshold").value = config.autoDowngradeFailureThreshold || 3;
        document.getElementById("ai-share-summary-timeout-multiplier").value = config.shareSummaryTimeoutMultiplier || 1;
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
        document.getElementById("share-summary-run-filter-task").innerHTML = filterOptions;
        renderNotificationFilterOptions();
    }

    function renderNotificationFilterOptions() {
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

    function renderNotificationEventOptions() {
        const options = state.notificationEvents.map((event) => `<option value="${escapeAttribute(event.eventType)}">${escapeHtml(event.label || event.eventType)}</option>`).join("");
        document.getElementById("notification-task-event-type").innerHTML = options;
        updateNotificationTaskEventTypeState(false);
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

    function addNotificationHeaderRow(name = "", value = "") {
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

    function renderNotificationChannelOptions() {
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

    function renderNotificationTaskOptions() {
        const options = [`<option value="">全部任务</option>`]
                .concat(state.notificationTasks.map((task) => `<option value="${escapeAttribute(task.id)}">${escapeHtml(task.name)}</option>`))
                .join("");
        document.getElementById("notification-delivery-filter-task").innerHTML = options;
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
                    <td>${renderNotificationStatus(delivery.status)}${delivery.errorMessage ? `<div class="keyline summary-error-hint" title="${escapeAttribute(delivery.errorMessage)}">${escapeHtml(delivery.errorMessage)}</div>` : ""}</td>
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

    function renderShareSummaryRuns() {
        const payload = state.shareSummaryRuns || {};
        const items = Array.isArray(payload.items) ? payload.items : [];
        const body = document.getElementById("share-summary-run-table");
        if (!items.length) {
            body.innerHTML = `<tr><td colspan="9" class="muted">暂无分享总结记录</td></tr>`;
        } else {
            body.innerHTML = items.map((run) => `
                <tr class="share-summary-run-row">
                    <td class="nowrap share-summary-run-start-cell">${escapeHtml(formatTimestamp(run.startedAt))}</td>
                    <td class="share-summary-run-task-cell">${escapeHtml(run.taskName || "-")}<div class="keyline">${escapeHtml(run.triggerType || "-")}</div></td>
                    <td class="summary-window-cell">${renderSummaryWindow(run.windowStart, run.windowEnd)}</td>
                    <td class="share-summary-run-status-cell">${renderRunStatus(run.status)}${renderRunErrorHint(run.errorMessage)}</td>
                    <td class="share-summary-run-count-cell">${escapeHtml(run.inputLinkCount || 0)}</td>
                    <td class="share-summary-run-ai-cell">${escapeHtml(run.aiProviderNames || "-")}<div class="keyline">${escapeHtml(formatDuration(run.aiDurationMs))}</div></td>
                    <td class="share-summary-run-image-cell">${renderShareSummaryImageCell(run)}</td>
                    <td class="share-summary-run-audio-cell">${renderShareSummaryAudioCell(run)}</td>
                    <td class="share-summary-run-action-cell">${renderShareSummaryRunActions(run)}</td>
                </tr>
            `).join("");
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

    function renderShareSummaryImageCell(run) {
        const status = run.imageStatus || "NOT_GENERATED";
        const imageUrl = run.latestImageUrl || "";
        const title = run.ogTitle || "暂无分享图";
        const image = run.latestImageUrl
                ? `
                    <button type="button" class="share-summary-image-button" data-view-share-image="${escapeAttribute(imageUrl)}" data-view-share-image-title="${escapeAttribute(title)}" aria-label="查看分享图">
                        <img class="share-summary-thumb" src="${escapeAttribute(imageUrl)}" alt="">
                    </button>
                `
                : `<div class="share-summary-thumb share-summary-thumb-placeholder">-</div>`;
        return `
            <div class="share-summary-image-cell">
                <div class="share-summary-image-thumb-slot">${image}</div>
                <div class="share-summary-image-meta">
                    ${renderImageStatusCompact(status)}
                </div>
                <div class="share-summary-image-title" title="${escapeAttribute(title)}">${escapeHtml(title)}</div>
            </div>
        `;
    }

    function renderShareSummaryAudioCell(run) {
        const status = run.audioStatus || "NOT_GENERATED";
        const playCount = Number(run.audioPlayCount || 0);
        return `
            <div class="share-summary-audio-cell">
                ${renderAudioStatus(status)}
                <div class="keyline">${escapeHtml(playCount)} 次播放</div>
            </div>
        `;
    }

    function renderShareSummaryRunActions(run) {
        const canGenerate = run.status === "SUCCESS";
        const hasImage = Boolean(run.ogImageUrl);
        const hasAudio = Boolean(run.audioUrl);
        return `
            <div class="row-actions share-summary-run-actions">
                <button type="button" class="secondary" data-view-share-run="${escapeAttribute(run.id)}">详情</button>
                ${renderShareSummaryImageActionButton(run, canGenerate, hasImage)}
                ${renderShareSummaryAudioActionButton(run, canGenerate, hasAudio)}
                <button type="button" class="secondary" data-copy-url="${escapeAttribute(shareSummaryOgShareUrl(run))}" ${shareSummaryOgShareUrl(run) ? "" : "disabled"}>复制OG链接</button>
                <button type="button" class="secondary" data-copy-url="${escapeAttribute(run.ogImageUrl || "")}" ${run.ogImageUrl ? "" : "disabled"}>复制图片</button>
                <button type="button" class="secondary" data-copy-url="${escapeAttribute(run.audioUrl || "")}" ${run.audioUrl ? "" : "disabled"}>复制音频</button>
                <button type="button" class="danger" data-delete-share-run="${escapeAttribute(run.id)}">删除</button>
            </div>
        `;
    }

    function renderShareSummaryImageActionButton(run, canGenerate = run.status === "SUCCESS", hasImage = Boolean(run.ogImageUrl)) {
        const imageActionAttribute = hasImage ? "data-regenerate-share-image" : "data-generate-share-image";
        const imageActionLabel = hasImage ? "重新生成图片" : "生成图片";
        return `<button type="button" class="secondary" ${imageActionAttribute}="${escapeAttribute(run.id)}" ${canGenerate ? "" : "disabled"}>${imageActionLabel}</button>`;
    }

    function renderShareSummaryAudioActionButton(run, canGenerate = run.status === "SUCCESS", hasAudio = Boolean(run.audioUrl)) {
        const audioActionAttribute = hasAudio ? "data-regenerate-share-audio" : "data-generate-share-audio";
        const audioActionLabel = hasAudio ? "重新生成音频" : "生成音频";
        return `<button type="button" class="secondary" ${audioActionAttribute}="${escapeAttribute(run.id)}" ${canGenerate ? "" : "disabled"}>${audioActionLabel}</button>`;
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

    function renderRunStatus(status) {
        const normalized = status || "-";
        const className = normalized === "SUCCESS" ? "is-success" : normalized === "FAILED" ? "is-warning" : "";
        return `<span class="status-pill ${className}">${escapeHtml(normalized)}</span>`;
    }

    function renderSummaryWindow(start, end) {
        return `
            <div class="window-range">
                <span>${escapeHtml(formatShortTimestamp(start))}</span>
                <span>${escapeHtml(formatShortTimestamp(end))}</span>
            </div>
        `;
    }

    function renderImageStatus(status) {
        const normalized = status || "NOT_GENERATED";
        const className = normalized === "SUCCESS" ? "is-success" : normalized === "FAILED" || normalized === "TIMEOUT" ? "is-warning" : "";
        return `<span class="status-pill ${className}">${escapeHtml(normalized)}</span>`;
    }

    function renderImageStatusCompact(status) {
        const normalized = status || "NOT_GENERATED";
        const labels = {
            NOT_GENERATED: "未生成",
            PENDING: "排队",
            GENERATING: "生成中",
            SUCCESS: "SUCCESS",
            FAILED: "失败",
            TIMEOUT: "超时"
        };
        const className = normalized === "SUCCESS" ? "is-success" : normalized === "FAILED" || normalized === "TIMEOUT" ? "is-warning" : "";
        return `<span class="status-pill status-pill-compact ${className}" title="${escapeAttribute(normalized)}">${escapeHtml(labels[normalized] || normalized)}</span>`;
    }

    function renderAudioStatus(status) {
        return renderImageStatus(status || "NOT_GENERATED");
    }

    function renderNotificationStatus(status) {
        const normalized = status || "-";
        const className = normalized === "SUCCESS" ? "is-success" : normalized === "FAILED" ? "is-warning" : "";
        return `<span class="status-pill ${className}">${escapeHtml(normalized)}</span>`;
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

    function updateNotificationTaskEventTypeState(resetTemplate) {
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

    function insertTextAtCursor(textarea, token) {
        const start = textarea.selectionStart ?? textarea.value.length;
        const end = textarea.selectionEnd ?? textarea.value.length;
        textarea.value = `${textarea.value.slice(0, start)}${token}${textarea.value.slice(end)}`;
        textarea.focus();
        textarea.selectionStart = textarea.selectionEnd = start + token.length;
    }

    function groupBy(items, keyFn) {
        return items.reduce((groups, item) => {
            const key = keyFn(item);
            groups[key] ||= [];
            groups[key].push(item);
            return groups;
        }, {});
    }

    function placeholderGroupLabel(group) {
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

    function bindShareSummaryAssetButtons(root) {
        root.querySelectorAll("[data-generate-share-image]").forEach((button) => {
            button.addEventListener("click", () => generateShareSummaryImage(button.dataset.generateShareImage, false));
        });
        root.querySelectorAll("[data-regenerate-share-image]").forEach((button) => {
            button.addEventListener("click", () => generateShareSummaryImage(button.dataset.regenerateShareImage, true));
        });
        root.querySelectorAll("[data-generate-share-audio]").forEach((button) => {
            button.addEventListener("click", () => generateShareSummaryAudio(button.dataset.generateShareAudio, false));
        });
        root.querySelectorAll("[data-regenerate-share-audio]").forEach((button) => {
            button.addEventListener("click", () => generateShareSummaryAudio(button.dataset.regenerateShareAudio, true));
        });
        root.querySelectorAll("[data-copy-url]").forEach((button) => {
            button.addEventListener("click", () => copyShareSummaryUrl(button.dataset.copyUrl));
        });
        root.querySelectorAll("[data-view-share-image]").forEach((button) => {
            button.addEventListener("click", () => {
                openShareSummaryImageViewer(button.dataset.viewShareImage, button.dataset.viewShareImageTitle || "分享图");
            });
        });
    }

    function openShareSummaryImageViewer(imageUrl, title) {
        if (!imageUrl) {
            return;
        }
        const viewer = document.getElementById("share-summary-image-viewer");
        const image = document.getElementById("share-summary-image-viewer-img");
        const titleNode = document.getElementById("share-summary-image-viewer-title");
        image.src = imageUrl;
        image.alt = title || "分享图";
        titleNode.textContent = title || "分享图";
        viewer.hidden = false;
        document.body.classList.add("modal-open");
        document.body.classList.add("share-summary-image-viewer-open");
        updateShareSummaryImageViewerLayout();
        document.getElementById("share-summary-image-viewer-close").focus({preventScroll: true});
    }

    function closeShareSummaryImageViewer() {
        const viewer = document.getElementById("share-summary-image-viewer");
        const image = document.getElementById("share-summary-image-viewer-img");
        viewer.hidden = true;
        image.removeAttribute("src");
        image.alt = "";
        document.body.classList.remove("share-summary-image-viewer-open");
        if (document.querySelectorAll(".modal-shell:not([hidden]), .share-summary-image-viewer:not([hidden])").length === 0) {
            document.body.classList.remove("modal-open");
        }
    }

    function updateShareSummaryImageViewerLayout() {
        const viewer = document.getElementById("share-summary-image-viewer");
        if (!viewer || viewer.hidden || !window.matchMedia("(max-width: 720px) and (orientation: portrait)").matches) {
            return;
        }
        const stage = viewer.querySelector(".share-summary-image-viewer-stage");
        if (!stage) {
            return;
        }
        const stageRect = stage.getBoundingClientRect();
        if (!stageRect.width || !stageRect.height) {
            return;
        }
        const imageWidth = Math.min(stageRect.height, stageRect.width * 1200 / 630);
        stage.style.setProperty("--share-summary-image-viewer-mobile-width", `${Math.floor(imageWidth)}px`);
    }

    async function generateShareSummaryImage(runId, regenerate) {
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

    async function generateShareSummaryAudio(runId, regenerate) {
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

    async function saveNotificationChannel(event) {
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

    async function saveNotificationTask(event) {
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

    async function validateNotificationTemplate() {
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

    function checkedValues(selector) {
        return Array.from(document.querySelectorAll(selector))
                .map((input) => String(input.value || "").trim())
                .filter(Boolean);
    }

    function checkedNumberValues(selector) {
        return checkedValues(selector)
                .map((value) => Number(value))
                .filter((value) => Number.isInteger(value) && value > 0);
    }

    function setCheckedValues(selector, values) {
        const valueSet = new Set((values || []).map((value) => String(value).toUpperCase()));
        document.querySelectorAll(selector).forEach((input) => {
            input.checked = valueSet.has(String(input.value).toUpperCase());
        });
    }

    async function copyShareSummaryUrl(url) {
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

    function periodLabel(value) {
        if (value === "WEEKLY") {
            return "每周";
        }
        if (value === "MONTHLY") {
            return "每月";
        }
        return "每日";
    }

    function periodSelectionModeLabel(value) {
        return value === "PREVIOUS" ? "上周期" : "本周期";
    }

    function scheduleLabel(task) {
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

    function setAiProviderDowngradeEnabled(enabled) {
        const toggle = document.getElementById("ai-auto-downgrade-enabled-toggle");
        const label = document.getElementById("ai-auto-downgrade-enabled-label");
        toggle.dataset.enabled = enabled ? "true" : "false";
        toggle.setAttribute("aria-pressed", enabled ? "true" : "false");
        toggle.classList.toggle("is-enabled", enabled);
        label.textContent = enabled ? "启用" : "禁用";
    }

    function bindAiDragSorting(body) {
        body.ondragover = (event) => {
            const draggingRow = body.querySelector("tr.is-dragging");
            if (!draggingRow) {
                return;
            }
            event.preventDefault();
            const afterRow = dragAfterRow(body, event.clientY);
            if (afterRow) {
                body.insertBefore(draggingRow, afterRow);
                return;
            }
            body.appendChild(draggingRow);
        };
        body.ondrop = async (event) => {
            event.preventDefault();
            const ids = Array.from(body.querySelectorAll("[data-ai-row]"))
                    .map((row) => Number(row.dataset.aiRow));
            await saveAiProviderOrder(ids);
        };
        body.querySelectorAll("[data-drag-ai]").forEach((handle) => {
            handle.addEventListener("dragstart", (event) => {
                const row = handle.closest("[data-ai-row]");
                if (!row) {
                    return;
                }
                row.classList.add("is-dragging");
                event.dataTransfer.effectAllowed = "move";
                event.dataTransfer.setData("text/plain", handle.dataset.dragAi);
            });
            handle.addEventListener("dragend", () => {
                body.querySelectorAll(".is-dragging").forEach((row) => row.classList.remove("is-dragging"));
            });
        });
    }

    function dragAfterRow(body, y) {
        return Array.from(body.querySelectorAll("tr[data-ai-row]:not(.is-dragging)"))
                .reduce((closest, row) => {
                    const box = row.getBoundingClientRect();
                    const offset = y - box.top - box.height / 2;
                    if (offset < 0 && offset > closest.offset) {
                        return {offset, row};
                    }
                    return closest;
                }, {offset: Number.NEGATIVE_INFINITY, row: null}).row;
    }

    async function saveAiProviderOrder(ids) {
        const currentIds = state.aiProviders.map((provider) => Number(provider.id));
        if (ids.length === currentIds.length && ids.every((id, index) => id === currentIds[index])) {
            return;
        }
        setFeedback("ai-feedback", "正在保存 AI Provider 排序...", "");
        try {
            state.aiProviders = await fetchJson("/api/admin/ai-providers/reorder", {
                method: "PUT",
                body: JSON.stringify({ids})
            });
            renderAiProviders();
            setFeedback("ai-feedback", "AI Provider 排序已保存。", "is-success");
        } catch (error) {
            await loadAiProviders();
            setFeedback("ai-feedback", error.message, "is-error");
        }
    }

    async function toggleAiProvider(input) {
        const providerId = input.dataset.toggleAi;
        const enabled = input.checked;
        input.disabled = true;
        setFeedback("ai-feedback", "正在更新 AI Provider 状态...", "");
        try {
            const updated = await fetchJson(`/api/admin/ai-providers/${encodeURIComponent(providerId)}/enabled`, {
                method: "PUT",
                body: JSON.stringify({enabled})
            });
            state.aiProviders = state.aiProviders.map((provider) => {
                return Number(provider.id) === Number(updated.id) ? updated : provider;
            });
            renderAiProviders();
            setFeedback("ai-feedback", `${updated.name || "AI Provider"} 已${updated.enabled ? "启用" : "禁用"}。`, "is-success");
        } catch (error) {
            input.checked = !enabled;
            setFeedback("ai-feedback", error.message, "is-error");
        } finally {
            input.disabled = false;
        }
    }

    async function testAiProvider(button) {
        const providerId = button.dataset.testAi;
        button.disabled = true;
        button.classList.remove("is-success", "is-error");
        button.textContent = "测试中";
        button.removeAttribute("title");
        setFeedback("ai-feedback", "正在测试 AI Provider...", "");
        try {
            const result = await fetchJson(`/api/admin/ai-providers/${encodeURIComponent(providerId)}/test`, {
                method: "POST"
            });
            button.classList.toggle("is-success", Boolean(result.success));
            button.classList.toggle("is-error", !result.success);
            button.textContent = result.success ? "成功" : "失败";
            button.title = result.message || "";
            const message = result.message || (result.success ? "测试成功。" : "测试失败。");
            const details = [];
            if (typeof result.durationMs === "number") {
                details.push(`耗时 ${result.durationMs}ms`);
            }
            if (result.output) {
                details.push(`返回：${result.output}`);
            }
            const feedback = details.length > 0
                    ? `${trimTrailingPunctuation(message)}，${details.join("，")}`
                    : message;
            setFeedback("ai-feedback", feedback, result.success ? "is-success" : "is-error");
        } catch (error) {
            button.classList.add("is-error");
            button.textContent = "失败";
            button.title = error.message;
            setFeedback("ai-feedback", error.message, "is-error");
        } finally {
            button.disabled = false;
        }
    }

    function renderLogs(result) {
        const output = document.getElementById("log-output");
        const meta = document.getElementById("log-meta");
        const lines = Array.isArray(result.lines) ? result.lines : [];
        meta.textContent = `路径：${result.path || ""} · 大小：${formatBytes(result.sizeBytes || 0)} · 更新：${formatTimestamp(result.modifiedAt)}`;

        if (!result.exists) {
            output.textContent = "";
            setFeedback("log-feedback", "日志文件不存在或不可读。", "is-error");
            return;
        }

        output.textContent = lines.length ? lines.join("\n") : "没有匹配日志。";
        output.scrollTop = output.scrollHeight;
        const truncated = result.truncated ? "，已截断" : "";
        setFeedback("log-feedback", `已加载 ${lines.length} 行${truncated}。`, "is-success");
    }

    function updateLogAutoRefresh() {
        syncLogAutoRefreshForActivePanel(panelIdFromLocation());
    }

    function syncLogAutoRefreshForActivePanel(panelId) {
        if (state.logRefreshTimer) {
            window.clearInterval(state.logRefreshTimer);
            state.logRefreshTimer = null;
        }
        if (state.adminReady && panelId === "service-logs" && document.getElementById("log-auto-refresh").checked) {
            state.logRefreshTimer = window.setInterval(loadLogs, 5000);
        }
    }

    function openPromptModalForCreate() {
        resetPromptForm();
        document.getElementById("prompt-modal-title").textContent = "新建 Style Prompt";
        document.getElementById("prompt-style").disabled = false;
        openModal("prompt-modal");
        setFeedback("prompt-modal-feedback", "", "");
        document.getElementById("prompt-style").focus();
    }

    function openPromptModalForEdit(prompt) {
        resetPromptForm();
        document.getElementById("prompt-modal-title").textContent = `编辑 Style Prompt：${prompt.style}`;
        document.getElementById("prompt-style").value = prompt.style;
        document.getElementById("prompt-style").disabled = true;
        document.getElementById("prompt-text").value = prompt.prompt;
        openModal("prompt-modal");
        setFeedback("prompt-modal-feedback", "", "");
        document.getElementById("prompt-text").focus();
    }

    function closePromptModal(clearFeedback = true) {
        resetPromptForm();
        closeModal("prompt-modal");
        if (clearFeedback) {
            setFeedback("prompt-modal-feedback", "", "");
        }
    }

    function resetPromptForm() {
        document.getElementById("prompt-form").reset();
        document.getElementById("prompt-style").disabled = false;
    }

    function openAiFormForCreate() {
        resetAiForm();
        document.getElementById("ai-modal-title").textContent = "新建 AI Provider";
        openModal("ai-modal");
        setFeedback("ai-modal-feedback", "", "");
        document.getElementById("ai-name").focus();
    }

    function openAiFormForEdit(id) {
        const provider = state.aiProviders.find((item) => item.id === id);
        if (!provider) {
            return;
        }
        document.getElementById("ai-modal-title").textContent = `编辑 AI Provider：${provider.name || provider.id}`;
        openModal("ai-modal");
        document.getElementById("ai-id").value = provider.id;
        document.getElementById("ai-name").value = provider.name || "";
        document.getElementById("ai-request-timeout-seconds").value = providerRequestTimeoutSeconds(provider);
        document.getElementById("ai-base-url").value = provider.baseUrl || "";
        document.getElementById("ai-api-kind").value = providerApiKind(provider);
        document.getElementById("ai-model").value = provider.model || "";
        document.getElementById("ai-effort").value = provider.effort || "";
        document.getElementById("ai-api-key").value = provider.apiKey || "";
        setFeedback("ai-modal-feedback", "", "");
        document.getElementById("ai-name").focus();
    }

    function closeAiModal(clearFeedback = true) {
        resetAiForm();
        closeModal("ai-modal");
        if (clearFeedback) {
            setFeedback("ai-modal-feedback", "", "");
        }
    }

    function resetAiForm() {
        document.getElementById("ai-form").reset();
        document.getElementById("ai-id").value = "";
        document.getElementById("ai-request-timeout-seconds").value = "45";
        document.getElementById("ai-api-kind").value = "CHAT_COMPLETIONS";
    }

    async function saveShareSummaryTask(event) {
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

    async function openShareSummaryImageConfigModal() {
        openModal("share-summary-image-config-modal");
        setFeedback("share-summary-image-config-modal-feedback", "正在读取 AI 生图配置...", "");
        try {
            await loadShareSummaryImageConfig();
            fillShareSummaryImageConfigForm(state.shareSummaryImageConfig || {});
            setFeedback("share-summary-image-config-modal-feedback", "", "");
        } catch (error) {
            setFeedback("share-summary-image-config-modal-feedback", error.message, "is-error");
        }
    }

    function closeShareSummaryImageConfigModal(clearFeedback = true) {
        closeModal("share-summary-image-config-modal");
        document.getElementById("share-summary-image-config-form").reset();
        if (clearFeedback) {
            setFeedback("share-summary-image-config-modal-feedback", "", "");
        }
    }

    function fillShareSummaryImageConfigForm(config) {
        document.getElementById("share-summary-image-enabled").checked = Boolean(config.enabled);
        document.getElementById("share-summary-image-auto-generate").checked = Boolean(config.autoGenerate);
        document.getElementById("share-summary-image-provider-type").value = config.providerType || "OPENAI_COMPATIBLE";
        document.getElementById("share-summary-image-base-url").value = config.baseUrl || "";
        document.getElementById("share-summary-image-endpoint-path").value = config.endpointPath || "/v1/images/generations";
        document.getElementById("share-summary-image-api-key").value = "";
        document.getElementById("share-summary-image-api-key").placeholder = config.apiKeyConfigured ? "已配置，留空表示不修改" : "请输入 API Key";
        document.getElementById("share-summary-image-model").value = config.model || "";
        document.getElementById("share-summary-image-size").value = config.imageSize || "auto";
        document.getElementById("share-summary-image-quality").value = config.quality || "auto";
        document.getElementById("share-summary-image-timeout").value = config.requestTimeoutSeconds || 300;
        document.getElementById("share-summary-image-style-prompt").value = config.stylePrompt || "";
    }

    async function saveShareSummaryImageConfig(event) {
        event.preventDefault();
        const payload = {
            enabled: document.getElementById("share-summary-image-enabled").checked,
            autoGenerate: document.getElementById("share-summary-image-auto-generate").checked,
            providerType: document.getElementById("share-summary-image-provider-type").value,
            baseUrl: document.getElementById("share-summary-image-base-url").value.trim(),
            endpointPath: document.getElementById("share-summary-image-endpoint-path").value.trim(),
            apiKey: document.getElementById("share-summary-image-api-key").value.trim(),
            model: document.getElementById("share-summary-image-model").value.trim(),
            imageSize: document.getElementById("share-summary-image-size").value.trim(),
            quality: document.getElementById("share-summary-image-quality").value.trim(),
            outputFormat: "png",
            stylePrompt: document.getElementById("share-summary-image-style-prompt").value.trim(),
            requestTimeoutSeconds: Number(document.getElementById("share-summary-image-timeout").value || 300)
        };
        const error = validateShareSummaryImageConfig(payload);
        if (error) {
            setFeedback("share-summary-image-config-modal-feedback", error, "is-error");
            return;
        }
        setFeedback("share-summary-image-config-modal-feedback", "正在保存 AI 生图配置...", "");
        try {
            state.shareSummaryImageConfig = await fetchJson("/api/admin/share-summary/image-config", {
                method: "PUT",
                body: JSON.stringify(payload)
            });
            fillShareSummaryImageConfigForm(state.shareSummaryImageConfig);
            setFeedback("share-summary-image-config-modal-feedback", "AI 生图配置已保存。", "is-success");
        } catch (saveError) {
            setFeedback("share-summary-image-config-modal-feedback", saveError.message, "is-error");
        }
    }

    function validateShareSummaryImageConfig(payload) {
        if (payload.enabled || payload.autoGenerate) {
            if (!payload.baseUrl) {
                return "Base URL 不能为空。";
            }
            try {
                const url = new URL(payload.baseUrl);
                if (url.protocol !== "http:" && url.protocol !== "https:") {
                    return "Base URL 必须使用 http 或 https。";
                }
            } catch (error) {
                return "Base URL 必须是合法 URL。";
            }
            if (!payload.model) {
                return "Model 不能为空。";
            }
        }
        if (!payload.endpointPath) {
            return "Endpoint Path 不能为空。";
        }
        if (!["auto", "1024x1024", "1536x1024", "1024x1536"].includes((payload.imageSize || "").toLowerCase())) {
            return "Size 必须是 auto、1024x1024、1536x1024 或 1024x1536。";
        }
        if (!Number.isInteger(payload.requestTimeoutSeconds) || payload.requestTimeoutSeconds < 1 || payload.requestTimeoutSeconds > 1800) {
            return "Timeout 必须是 1-1800 秒之间的整数。";
        }
        return "";
    }

    async function openShareSummaryAudioConfigModal() {
        openModal("share-summary-audio-config-modal");
        resetShareSummaryAudioTestButton();
        setFeedback("share-summary-audio-config-modal-feedback", "正在读取 TTS 配置...", "");
        try {
            await loadShareSummaryAudioConfig();
            fillShareSummaryAudioConfigForm(state.shareSummaryAudioConfig || {});
            setFeedback("share-summary-audio-config-modal-feedback", "", "");
        } catch (error) {
            setFeedback("share-summary-audio-config-modal-feedback", error.message, "is-error");
        }
    }

    function closeShareSummaryAudioConfigModal(clearFeedback = true) {
        closeModal("share-summary-audio-config-modal");
        document.getElementById("share-summary-audio-config-form").reset();
        resetShareSummaryAudioTestButton();
        if (clearFeedback) {
            setFeedback("share-summary-audio-config-modal-feedback", "", "");
        }
    }

    function fillShareSummaryAudioConfigForm(config) {
        const providerType = config.providerType || "OPENAI_COMPATIBLE";
        document.getElementById("share-summary-audio-enabled").checked = Boolean(config.enabled);
        document.getElementById("share-summary-audio-auto-generate").checked = Boolean(config.autoGenerate);
        document.getElementById("share-summary-audio-provider-type").value = providerType;
        document.getElementById("share-summary-audio-base-url").value = config.baseUrl || (providerType === "MIMO_TTS" ? "https://api.xiaomimimo.com" : "https://tts.wangwangit.com");
        document.getElementById("share-summary-audio-endpoint-path").value = config.endpointPath || (providerType === "MIMO_TTS" ? "/v1/chat/completions" : "/v1/audio/speech");
        document.getElementById("share-summary-audio-api-key").value = "";
        document.getElementById("share-summary-audio-api-key").placeholder = config.apiKeyConfigured ? "已配置，留空表示不修改" : "可选";
        document.getElementById("share-summary-audio-model").value = config.model || (providerType === "MIMO_TTS" ? MIMO_TTS_PRESET_MODEL : "");
        document.getElementById("share-summary-audio-voice").value = config.voice || (providerType === "MIMO_TTS" ? MIMO_TTS_DEFAULT_VOICE : "zh-CN-YunhaoNeural");
        document.getElementById("share-summary-audio-voice-select").value = config.voice || MIMO_TTS_DEFAULT_VOICE;
        document.getElementById("share-summary-audio-speed").value = config.speed || 1.2;
        document.getElementById("share-summary-audio-pitch").value = config.pitch ?? 0;
        document.getElementById("share-summary-audio-style").value = config.style || (providerType === "MIMO_TTS" ? MIMO_TTS_DEFAULT_STYLE : "newscast");
        document.getElementById("share-summary-audio-timeout").value = config.requestTimeoutSeconds || 120;
        updateShareSummaryAudioProviderFields(false);
    }

    function isMimoTtsAudioProvider() {
        return document.getElementById("share-summary-audio-provider-type").value === "MIMO_TTS";
    }

    function shareSummaryAudioVoiceValue() {
        return isMimoTtsAudioProvider()
                ? document.getElementById("share-summary-audio-voice-select").value.trim()
                : document.getElementById("share-summary-audio-voice").value.trim();
    }

    function updateShareSummaryAudioProviderFields(applyDefaults) {
        const mimo = isMimoTtsAudioProvider();
        document.getElementById("share-summary-audio-voice-text-row").hidden = mimo;
        document.getElementById("share-summary-audio-voice-select-row").hidden = !mimo;
        document.getElementById("share-summary-audio-speed-row").hidden = mimo;
        document.getElementById("share-summary-audio-pitch-row").hidden = mimo;
        document.getElementById("share-summary-audio-style-label").textContent = mimo ? "音频标签" : "Style";
        document.getElementById("share-summary-audio-model").placeholder = mimo ? MIMO_TTS_PRESET_MODEL : "可选，例如 tts-1";
        if (!applyDefaults) {
            return;
        }
        if (mimo) {
            document.getElementById("share-summary-audio-base-url").value = "https://api.xiaomimimo.com";
            document.getElementById("share-summary-audio-endpoint-path").value = "/v1/chat/completions";
            document.getElementById("share-summary-audio-model").value = MIMO_TTS_PRESET_MODEL;
            document.getElementById("share-summary-audio-voice-select").value = MIMO_TTS_DEFAULT_VOICE;
            document.getElementById("share-summary-audio-style").value = MIMO_TTS_DEFAULT_STYLE;
        } else {
            document.getElementById("share-summary-audio-base-url").value = "https://tts.wangwangit.com";
            document.getElementById("share-summary-audio-endpoint-path").value = "/v1/audio/speech";
            document.getElementById("share-summary-audio-model").value = "";
            document.getElementById("share-summary-audio-voice").value = "zh-CN-YunhaoNeural";
            document.getElementById("share-summary-audio-style").value = "newscast";
        }
    }

    async function saveShareSummaryAudioConfig(event) {
        event.preventDefault();
        const payload = shareSummaryAudioConfigPayload();
        const error = validateShareSummaryAudioConfig(payload);
        if (error) {
            setFeedback("share-summary-audio-config-modal-feedback", error, "is-error");
            return;
        }
        setFeedback("share-summary-audio-config-modal-feedback", "正在保存 TTS 配置...", "");
        try {
            state.shareSummaryAudioConfig = await fetchJson("/api/admin/share-summary/audio-config", {
                method: "PUT",
                body: JSON.stringify(payload)
            });
            fillShareSummaryAudioConfigForm(state.shareSummaryAudioConfig);
            setFeedback("share-summary-audio-config-modal-feedback", "TTS 配置已保存。", "is-success");
        } catch (saveError) {
            setFeedback("share-summary-audio-config-modal-feedback", saveError.message, "is-error");
        }
    }

    async function testShareSummaryAudioConfig() {
        const button = document.getElementById("share-summary-audio-config-test-button");
        const payload = shareSummaryAudioConfigPayload();
        const error = validateShareSummaryAudioConfig(payload, true);
        button.classList.remove("is-success", "is-error");
        button.textContent = "测试 TTS";
        resetShareSummaryAudioTestAudio();
        if (error) {
            setFeedback("share-summary-audio-config-modal-feedback", error, "is-error");
            button.classList.add("is-error");
            return;
        }
        button.disabled = true;
        button.textContent = "测试中";
        setFeedback("share-summary-audio-config-modal-feedback", "正在请求 TTS Provider 生成测试音频...", "");
        try {
            const result = await fetchJson("/api/admin/share-summary/audio-config/test", {
                method: "POST",
                body: JSON.stringify(payload)
            });
            button.classList.toggle("is-success", Boolean(result.success));
            button.classList.toggle("is-error", !result.success);
            button.textContent = "测试 TTS";
            if (result.success) {
                const parts = [];
                if (typeof result.durationMs === "number") {
                    parts.push(`耗时 ${result.durationMs}ms`);
                }
                if (typeof result.responseBytes === "number") {
                    parts.push(`音频 ${formatBytes(result.responseBytes)}`);
                }
                showShareSummaryAudioTestAudio(result.audioUrl);
                setFeedback("share-summary-audio-config-modal-feedback", `TTS 测试成功${parts.length ? "，" + parts.join("，") : ""}。`, "is-success");
            } else {
                resetShareSummaryAudioTestAudio();
                setFeedback("share-summary-audio-config-modal-feedback", result.message || "TTS 测试失败。", "is-error");
            }
        } catch (testError) {
            button.classList.add("is-error");
            button.textContent = "测试 TTS";
            resetShareSummaryAudioTestAudio();
            setFeedback("share-summary-audio-config-modal-feedback", testError.message, "is-error");
        } finally {
            button.disabled = false;
        }
    }

    function resetShareSummaryAudioTestButton() {
        const button = document.getElementById("share-summary-audio-config-test-button");
        button.disabled = false;
        button.textContent = "测试 TTS";
        button.classList.remove("is-success", "is-error");
        resetShareSummaryAudioTestAudio();
    }

    function showShareSummaryAudioTestAudio(audioUrl) {
        const player = document.getElementById("share-summary-audio-test-player");
        const audio = document.getElementById("share-summary-audio-test-audio");
        if (!player || !audio || !audioUrl) {
            resetShareSummaryAudioTestAudio();
            return;
        }
        audio.src = audioUrl;
        audio.load();
        player.hidden = false;
    }

    function resetShareSummaryAudioTestAudio() {
        const player = document.getElementById("share-summary-audio-test-player");
        const audio = document.getElementById("share-summary-audio-test-audio");
        if (audio) {
            audio.pause();
            audio.removeAttribute("src");
            audio.load();
        }
        if (player) {
            player.hidden = true;
        }
    }

    function shareSummaryAudioConfigPayload() {
        return {
            enabled: document.getElementById("share-summary-audio-enabled").checked,
            autoGenerate: document.getElementById("share-summary-audio-auto-generate").checked,
            providerType: document.getElementById("share-summary-audio-provider-type").value,
            baseUrl: document.getElementById("share-summary-audio-base-url").value.trim(),
            endpointPath: document.getElementById("share-summary-audio-endpoint-path").value.trim(),
            apiKey: document.getElementById("share-summary-audio-api-key").value.trim(),
            model: document.getElementById("share-summary-audio-model").value.trim(),
            voice: shareSummaryAudioVoiceValue(),
            speed: isMimoTtsAudioProvider() ? 1.2 : Number(document.getElementById("share-summary-audio-speed").value || 1.2),
            pitch: isMimoTtsAudioProvider() ? 0 : Number(document.getElementById("share-summary-audio-pitch").value || 0),
            style: document.getElementById("share-summary-audio-style").value.trim(),
            outputFormat: isMimoTtsAudioProvider() ? "wav" : "mp3",
            requestTimeoutSeconds: Number(document.getElementById("share-summary-audio-timeout").value || 120)
        };
    }

    function validateShareSummaryAudioConfig(payload, requireProvider = false) {
        if (requireProvider || payload.enabled || payload.autoGenerate) {
            if (!payload.baseUrl) {
                return "Base URL 不能为空。";
            }
            try {
                const url = new URL(payload.baseUrl);
                if (url.protocol !== "http:" && url.protocol !== "https:") {
                    return "Base URL 必须使用 http 或 https。";
                }
            } catch (error) {
                return "Base URL 必须是合法 URL。";
            }
            if (!payload.voice) {
                return "Voice 不能为空。";
            }
        }
        if (!payload.endpointPath) {
            return "Endpoint Path 不能为空。";
        }
        if (payload.providerType !== "MIMO_TTS" && (!Number.isFinite(payload.speed) || payload.speed < 0.25 || payload.speed > 4)) {
            return "Speed 必须是 0.25-4 之间的数字。";
        }
        if (payload.providerType !== "MIMO_TTS" && !Number.isInteger(payload.pitch)) {
            return "Pitch 必须是整数。";
        }
        if (!Number.isInteger(payload.requestTimeoutSeconds) || payload.requestTimeoutSeconds < 1 || payload.requestTimeoutSeconds > 1800) {
            return "Timeout 必须是 1-1800 秒之间的整数。";
        }
        return "";
    }

    function openShareSummaryTaskModalForCreate() {
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

    function closeShareSummaryTaskModal(clearFeedback = true) {
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

    function updateShareSummaryPeriodFields() {
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

    function openNotificationChannelModalForCreate() {
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

    function closeNotificationChannelModal(clearFeedback = true) {
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

    function openNotificationTaskModalForCreate() {
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

    function closeNotificationTaskModal(clearFeedback = true) {
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

    async function openShareSummaryRunDetail(runId) {
        state.activeShareSummaryRunId = runId;
        openModal("share-summary-run-modal");
        document.getElementById("share-summary-run-detail").innerHTML = `<p class="muted">正在读取详情...</p>`;
        setFeedback("share-summary-run-modal-feedback", "", "");
        try {
            const run = await fetchJson(`/api/admin/share-summary/runs/${encodeURIComponent(runId)}`);
            const [images, audios] = await Promise.all([
                fetchJson(`/api/admin/share-summary/runs/${encodeURIComponent(runId)}/images`),
                fetchJson(`/api/admin/share-summary/runs/${encodeURIComponent(runId)}/audios`)
            ]);
            document.getElementById("share-summary-run-modal-title").textContent = `分享总结详情：${run.taskName || run.id}`;
            document.getElementById("share-summary-run-detail").innerHTML = renderShareSummaryRunDetail(run, images, audios);
            bindShareSummaryAssetButtons(document.getElementById("share-summary-run-detail"));
        } catch (error) {
            setFeedback("share-summary-run-modal-feedback", error.message, "is-error");
        }
    }

    function closeShareSummaryRunModal() {
        closeModal("share-summary-run-modal");
        state.activeShareSummaryRunId = null;
        setFeedback("share-summary-run-modal-feedback", "", "");
    }

    function openModal(id) {
        document.getElementById(id).hidden = false;
        document.body.classList.add("modal-open");
    }

    function closeModal(id) {
        document.getElementById(id).hidden = true;
        if (document.querySelectorAll(".modal-shell:not([hidden])").length === 0) {
            document.body.classList.remove("modal-open");
        }
    }

    async function fetchJson(url, options = {}, redirectOnUnauthorized = true) {
        const response = await fetch(url, {
            headers: {
                Accept: "application/json",
                "Content-Type": "application/json"
            },
            ...options
        });
        if (response.status === 401 && redirectOnUnauthorized) {
            redirectToLogin();
            throw new Error("登录已失效。");
        }
        if (!response.ok) {
            throw new Error(await errorMessage(response));
        }
        if (response.status === 204) {
            return null;
        }
        return response.json();
    }

    function validateAiBaseUrl(value) {
        if (!value) {
            return "BaseURL 不能为空。";
        }
        let url;
        try {
            url = new URL(value);
        } catch (error) {
            return "BaseURL 必须是合法 URL。";
        }
        if ((url.protocol !== "http:" && url.protocol !== "https:") || !url.host) {
            return "BaseURL 必须包含 http/https 协议和域名。";
        }
        const path = url.pathname.replace(/\/+$/, "").toLowerCase();
        if (!path.endsWith("/v1")) {
            return "BaseURL 应填写到 /v1，例如 https://api.openai.com/v1。";
        }
        return "";
    }

    async function errorMessage(response) {
        try {
            const payload = await response.json();
            return payload.message || payload.error || `HTTP ${response.status}`;
        } catch (error) {
            return `HTTP ${response.status}`;
        }
    }

    function showAdmin() {
        document.getElementById("admin-shell").hidden = false;
        document.getElementById("admin-menu-button").hidden = false;
        document.getElementById("logout-button").hidden = false;
    }

    function redirectToLogin() {
        const next = `${window.location.pathname}${window.location.search}${window.location.hash}`;
        window.location.replace(`/admin/login?next=${encodeURIComponent(next)}`);
    }

    function setFeedback(id, message, className) {
        const node = document.getElementById(id);
        if (!node) {
            return;
        }
        node.textContent = message || "";
        node.className = `feedback ${className || ""}`.trim();
    }

    function confirmDangerAction(button, confirmLabel) {
        if (button.dataset.confirming === "true") {
            clearDangerConfirmTimer(button);
            state.activeDangerButton = null;
            return true;
        }

        resetActiveDangerAction(button);
        ensureDangerButtonLabels(button, confirmLabel);
        forceDangerButtonTransitionStart(button);
        button.dataset.confirming = "true";
        state.activeDangerButton = button;
        button.classList.add("is-confirming");
        button.title = "再次点击执行";
        button.setAttribute("aria-label", `${confirmLabel}，再次点击执行`);
        button.dataset.confirmTimer = String(window.setTimeout(() => {
            resetDangerAction(button);
        }, CONFIRM_TIMEOUT_MS));
        return false;
    }

    function resetDangerAction(button) {
        if (!button) {
            return;
        }
        clearDangerConfirmTimer(button);
        button.dataset.confirming = "false";
        button.classList.remove("is-confirming");
        if (button.dataset.defaultLabel) {
            button.setAttribute("aria-label", button.dataset.defaultLabel);
        }
        button.removeAttribute("title");
        if (state.activeDangerButton === button) {
            state.activeDangerButton = null;
        }
    }

    function clearDangerConfirmTimer(button) {
        const timer = Number(button.dataset.confirmTimer || 0);
        if (timer) {
            window.clearTimeout(timer);
        }
        delete button.dataset.confirmTimer;
    }

    function resetActiveDangerAction(exceptButton) {
        if (state.activeDangerButton && state.activeDangerButton !== exceptButton) {
            resetDangerAction(state.activeDangerButton);
        }
    }

    function ensureDangerButtonLabels(button, confirmLabel) {
        if (button.querySelector(".danger-button-label")) {
            button.querySelector(".danger-button-confirm").textContent = confirmLabel;
            return;
        }

        const defaultLabel = button.textContent.trim();
        button.dataset.defaultLabel = defaultLabel;
        button.setAttribute("aria-label", defaultLabel);
        button.textContent = "";

        const wrapper = document.createElement("span");
        wrapper.className = "danger-button-label";

        const defaultNode = document.createElement("span");
        defaultNode.className = "danger-button-text danger-button-default";
        defaultNode.textContent = defaultLabel;

        const confirmNode = document.createElement("span");
        confirmNode.className = "danger-button-text danger-button-confirm";
        confirmNode.textContent = confirmLabel;

        wrapper.append(defaultNode, confirmNode);
        button.append(wrapper);
    }

    function forceDangerButtonTransitionStart(button) {
        button.getBoundingClientRect();
    }

    function trimTrailingPunctuation(value) {
        return String(value ?? "").replace(/[。.!！?？,，;；:：]+$/u, "");
    }

    function formatTimestamp(value) {
        if (!value) {
            return "n/a";
        }
        return new Date(value).toLocaleString();
    }

    function formatShortTimestamp(value) {
        if (!value) {
            return "n/a";
        }
        const date = new Date(value);
        const datePart = date.toLocaleDateString();
        const timePart = date.toLocaleTimeString([], {hour: "2-digit", minute: "2-digit"});
        return `${datePart} ${timePart}`;
    }

    function formatBytes(value) {
        const bytes = Number(value || 0);
        if (bytes < 1024) {
            return `${bytes} B`;
        }
        if (bytes < 1024 * 1024) {
            return `${(bytes / 1024).toFixed(1)} KB`;
        }
        return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    }

    function formatDuration(value) {
        const duration = Number(value || 0);
        if (!Number.isFinite(duration) || duration <= 0) {
            return "-";
        }
        return `${Math.round(duration)}ms`;
    }

    function formatWindow(start, end) {
        return `${formatTimestamp(start)} ~ ${formatTimestamp(end)}`;
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

    function renderRunErrorHint(errorMessage) {
        if (!errorMessage) {
            return "";
        }
        return `<div class="keyline summary-error-hint" title="${escapeAttribute(errorMessage)}">${escapeHtml(errorMessage)}</div>`;
    }

    function renderShareSummaryRunDetail(run, images = [], audios = []) {
        return `
            <div class="summary-detail-grid">
                <div><b>状态</b><span>${renderRunStatus(run.status)}</span></div>
                <div><b>触发方式</b><span>${escapeHtml(run.triggerType || "-")}</span></div>
                <div><b>窗口</b><span>${escapeHtml(formatWindow(run.windowStart, run.windowEnd))}</span></div>
                <div><b>链接</b><span>${escapeHtml(run.inputLinkCount || 0)}</span></div>
                <div><b>AI Provider</b><span>${escapeHtml(run.aiProviderNames || "-")}</span></div>
                <div><b>AI 耗时</b><span>${escapeHtml(formatDuration(run.aiDurationMs))}</span></div>
            </div>
            <section class="summary-detail-section">
                <div class="section-title-row">
                    <h4>分享图</h4>
                    <div class="row-actions">
                        ${renderShareSummaryImageActionButton(run)}
                        <button type="button" class="secondary" data-copy-url="${escapeAttribute(shareSummaryOgShareUrl(run))}" ${shareSummaryOgShareUrl(run) ? "" : "disabled"}>复制OG链接</button>
                        <button type="button" class="secondary" data-copy-url="${escapeAttribute(run.ogImageUrl || "")}" ${run.ogImageUrl ? "" : "disabled"}>复制图片链接</button>
                    </div>
                </div>
                ${renderShareSummaryImageDetail(run, images)}
            </section>
            <section class="summary-detail-section">
                <div class="section-title-row">
                    <h4>语音文件</h4>
                    <div class="row-actions">
                        ${renderShareSummaryAudioActionButton(run)}
                        <button type="button" class="secondary" data-copy-url="${escapeAttribute(run.audioUrl || "")}" ${run.audioUrl ? "" : "disabled"}>复制音频链接</button>
                    </div>
                </div>
                ${renderShareSummaryAudioDetail(run, audios)}
            </section>
            ${run.errorMessage ? `<section class="summary-detail-section"><h4>错误信息</h4><pre class="summary-report is-error">${escapeHtml(run.errorMessage)}</pre></section>` : ""}
            <section class="summary-detail-section">
                <h4>总结报告</h4>
                <article class="summary-report summary-report-main">${renderSummaryReport(run.report || "")}</article>
            </section>
            <section class="summary-detail-section">
                <h4>提示词快照</h4>
                <pre class="summary-report summary-report-prompt">${escapeHtml(run.promptSnapshot || "")}</pre>
            </section>
        `;
    }

    function renderShareSummaryImageDetail(run, images) {
        const attempts = Array.isArray(images) ? images : [];
        const imageUrl = run.latestImageUrl || "";
        const title = run.ogTitle || "分享图";
        const preview = run.latestImageUrl
                ? `
                    <button type="button" class="share-summary-preview-button" data-view-share-image="${escapeAttribute(imageUrl)}" data-view-share-image-title="${escapeAttribute(title)}" aria-label="查看分享图">
                        <img class="share-summary-preview" src="${escapeAttribute(imageUrl)}" alt="">
                    </button>
                `
                : `<div class="share-summary-preview-placeholder">暂无分享图</div>`;
        const meta = `
            <div class="summary-detail-grid">
                <div><b>图片状态</b><span>${renderImageStatus(run.imageStatus || "NOT_GENERATED")}</span></div>
                <div><b>OG 标题</b><span>${escapeHtml(run.ogTitle || "-")}</span></div>
                <div><b>OG 描述</b><span>${escapeHtml(run.ogDescription || "-")}</span></div>
                <div><b>OG 分享链接</b><span class="url-cell">${escapeHtml(shareSummaryOgShareUrl(run) || "-")}</span></div>
                <div><b>图片直链</b><span class="url-cell">${escapeHtml(run.ogImageUrl || "-")}</span></div>
                <div><b>错误</b><span>${escapeHtml(run.imageErrorMessage || "-")}</span></div>
            </div>
        `;
        const rows = attempts.length ? attempts.map((image) => `
            <tr>
                <td>${escapeHtml(image.attemptNo || "-")}</td>
                <td>${renderImageStatus(image.status)}</td>
                <td>${escapeHtml(image.model || "-")}<div class="keyline">${escapeHtml(image.imageSize || "-")} · ${escapeHtml(image.quality || "-")}</div></td>
                <td>${escapeHtml(formatDuration(image.durationMs))}</td>
                <td>${escapeHtml(formatTimestamp(image.createdAt))}</td>
                <td>${escapeHtml(image.errorMessage || "-")}</td>
            </tr>
        `).join("") : `<tr><td colspan="6" class="muted">暂无生成记录</td></tr>`;
        return `
            <div class="share-summary-image-detail">
                ${preview}
                ${meta}
            </div>
            <div class="table-shell">
                <table class="share-summary-image-attempt-table">
                    <thead>
                    <tr>
                        <th>次数</th>
                        <th>状态</th>
                        <th>模型</th>
                        <th>耗时</th>
                        <th>创建时间</th>
                        <th>错误</th>
                    </tr>
                    </thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        `;
    }

    function renderShareSummaryAudioDetail(run, audios) {
        const attempts = Array.isArray(audios) ? audios : [];
        const player = run.audioUrl
                ? `<audio class="share-summary-audio-preview" controls preload="metadata" src="${escapeAttribute(run.audioUrl)}"></audio>`
                : `<div class="share-summary-preview-placeholder">暂无音频文件</div>`;
        const meta = `
            <div class="summary-detail-grid">
                <div><b>音频状态</b><span>${renderAudioStatus(run.audioStatus || "NOT_GENERATED")}</span></div>
                <div><b>播放次数</b><span>${escapeHtml(run.audioPlayCount || 0)}</span></div>
                <div><b>音频链接</b><span class="url-cell">${escapeHtml(run.audioUrl || "-")}</span></div>
                <div><b>错误</b><span>${escapeHtml(run.audioErrorMessage || "-")}</span></div>
            </div>
        `;
        const rows = attempts.length ? attempts.map((audio) => `
            <tr>
                <td>${escapeHtml(audio.attemptNo || "-")}</td>
                <td>${renderAudioStatus(audio.status)}</td>
                <td>${escapeHtml(audio.voice || "-")}<div class="keyline">${escapeHtml(audioMetaLine(audio))}</div></td>
                <td>${escapeHtml(audio.playCount || 0)}</td>
                <td>${escapeHtml(formatDuration(audio.durationMs))}</td>
                <td>${escapeHtml(formatTimestamp(audio.createdAt))}</td>
                <td>${escapeHtml(audio.errorMessage || "-")}</td>
            </tr>
        `).join("") : `<tr><td colspan="7" class="muted">暂无生成记录</td></tr>`;
        return `
            <div class="share-summary-image-detail">
                ${player}
                ${meta}
            </div>
            <div class="table-shell">
                <table class="share-summary-image-attempt-table">
                    <thead>
                    <tr>
                        <th>次数</th>
                        <th>状态</th>
                        <th>语音</th>
                        <th>播放</th>
                        <th>耗时</th>
                        <th>创建时间</th>
                        <th>错误</th>
                    </tr>
                    </thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        `;
    }

    function audioMetaLine(audio) {
        const model = audio.model || "无 model";
        const style = audio.style || "-";
        if (isMimoAudioRecord(audio)) {
            return `${model} · ${style}`;
        }
        return `${model} · ${audio.speed || "-"}x · pitch ${audio.pitch ?? "-"} · ${style}`;
    }

    function isMimoAudioRecord(audio) {
        const providerType = (audio.providerType || "").toUpperCase();
        const model = (audio.model || "").toLowerCase();
        return providerType === "MIMO_TTS" || model.startsWith("mimo-");
    }

    function shareSummaryOgShareUrl(run) {
        return run.ogShareUrl || run.ogPageUrl || "";
    }

    function notificationChannelNames(channelIds) {
        const ids = new Set((channelIds || []).map((id) => String(id)));
        return state.notificationChannels
                .filter((channel) => ids.has(String(channel.id)))
                .map((channel) => channel.name);
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

    function defaultNotificationChannelBodyTemplate() {
        return "{{message.bodyJson}}";
    }

    function shortPreviewKey(value) {
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

    function promptPreview(value) {
        const text = String(value ?? "").replace(/\s+/g, " ").trim();
        if (text.length <= 120) {
            return text;
        }
        return `${text.slice(0, 120)}...`;
    }

    function renderSummaryReport(value) {
        return linkifySummaryText(escapeHtml(value));
    }

    function linkifySummaryText(value) {
        let rendered = value.replace(/\[([^\]]+)]\((https?:\/\/[^\s)]+)\)/g, (_match, label, url) => linkHtml(url, label));
        rendered = rendered
            .split(/(<a\b[^>]*>.*?<\/a>)/g)
            .map((part) => part.startsWith("<a ") ? part : linkifyPlainUrls(part))
            .join("");
        return rendered;
    }

    function linkifyPlainUrls(value) {
        return value.replace(/(?<!["'=])(https?:\/\/[^\s<]+)/g, (match) => {
            const url = stripTrailingUrlPunctuation(match);
            return `${linkHtml(url, url)}${match.slice(url.length)}`;
        });
    }

    function stripTrailingUrlPunctuation(value) {
        return value.replace(/[.,;:!?，。；：！？]+$/g, "");
    }

    function linkHtml(escapedUrl, escapedLabel) {
        return `<a href="${escapedUrl}" target="_blank" rel="noreferrer">${escapedLabel}</a>`;
    }

    function providerApiKind(provider) {
        return provider.apiKind || "CHAT_COMPLETIONS";
    }

    function providerRequestTimeoutSeconds(provider) {
        const value = Number(provider.requestTimeoutSeconds || 45);
        return Number.isFinite(value) && value > 0 ? value : 45;
    }

    function providerRequestTimeoutLabel(provider) {
        return `${providerRequestTimeoutSeconds(provider)}s`;
    }

    function aiKindLabel(value) {
        return value === "RESPONSES" ? "Responses" : "Chat";
    }

    function escapeHtml(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#39;");
    }

    function escapeAttribute(value) {
        return escapeHtml(value);
    }

    init();
})();
