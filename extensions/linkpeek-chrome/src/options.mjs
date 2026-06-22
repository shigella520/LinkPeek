import {
    DEFAULT_APP_LAUNCH_URL,
    SETUP_MODES,
    configWithDefaults,
    normalizeBaseUrl,
    originPatternForBaseUrl
} from "./shared.mjs";

const form = document.getElementById("options-form");
const feedback = document.getElementById("feedback");
const statusOutput = document.getElementById("status-output");
const setupSteps = document.getElementById("setup-steps");
const permissionState = document.getElementById("permission-state");

const fields = {
    setupModes: Array.from(document.querySelectorAll("input[name='setupMode']")),
    baseUrl: document.getElementById("base-url"),
    adminPassword: document.getElementById("admin-password"),
    defaultStyle: document.getElementById("default-style"),
    linuxDoSyncEnabled: document.getElementById("linuxdo-sync-enabled"),
    ngaSyncEnabled: document.getElementById("nga-sync-enabled"),
    syncIntervalMinutes: document.getElementById("sync-interval-minutes"),
    launchAppEnabled: document.getElementById("launch-app-enabled"),
    appLaunchUrl: document.getElementById("app-launch-url"),
    launcherCloseDelaySeconds: document.getElementById("launcher-close-delay-seconds")
};

let permissionGranted = false;
let permissionOrigin = "";
let commandShortcut = "";
let stylesLoaded = false;
let adminConnectionTested = false;
let appLaunchTested = false;

init().catch((error) => setFeedback(error.message || String(error), "error"));

async function init() {
    bindEvents();
    const state = await sendMessage({type: "get-state"});
    renderConfig(state.config);
    renderStatus(state.lastStatus);
    await renderCommandState();
    await updatePermissionState();
    renderMode();
    renderSetupGuide();
}

function bindEvents() {
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        await withBusy(event.submitter, "正在保存...", async () => {
            const state = await sendMessage({
                type: "save-config",
                config: readConfig()
            });
            renderConfig(state.config);
            renderStatus(state.lastStatus);
            await updatePermissionState();
            setFeedback("设置已保存。", "success");
        });
    });

    for (const mode of fields.setupModes) {
        mode.addEventListener("change", () => {
            renderMode();
            renderSetupGuide();
        });
    }

    fields.baseUrl.addEventListener("change", () => {
        updatePermissionState().catch((error) => setFeedback(error.message || String(error), "error"));
    });

    form.addEventListener("input", (event) => {
        if (event.target === fields.baseUrl) {
            markPermissionNeedsCheck();
        }
        renderMode();
        renderSetupGuide();
    });

    document.getElementById("request-permission-button").addEventListener("click", async (event) => {
        await withBusy(event.currentTarget, "正在授权...", async () => {
            const baseUrl = normalizedBaseUrlFromInput();
            const origin = originPatternForBaseUrl(baseUrl);
            const granted = await chrome.permissions.request({origins: [origin]});
            if (!granted) {
                throw new Error(`未获得 LinkPeek 域名权限：${origin}`);
            }
            await updatePermissionState();
            setFeedback("LinkPeek 域名权限已授权。", "success");
        });
    });

    document.getElementById("test-connection-button").addEventListener("click", async (event) => {
        await withBusy(event.currentTarget, "正在测试...", async () => {
            await saveCurrentConfigForAction();
            const result = await sendMessage({type: "test-connection"});
            renderStatus(await lastStatus());
            markButtonResult(event.currentTarget, "success", "连接正常");
            setFeedback(`服务连接正常：${result.status || "ok"}`, "success");
        }, {resultButton: event.currentTarget});
    });

    document.getElementById("test-admin-connection-button").addEventListener("click", async (event) => {
        await withBusy(event.currentTarget, "正在测试...", async () => {
            await saveCurrentConfigForAction();
            const result = await sendMessage({type: "test-admin-connection"});
            adminConnectionTested = true;
            renderStatus(result.status || await lastStatus());
            markButtonResult(event.currentTarget, "success", "Admin 正常");
            setFeedback("Admin 连接正常。", "success");
        }, {resultButton: event.currentTarget});
    });

    document.getElementById("load-styles-button").addEventListener("click", async (event) => {
        await withBusy(event.currentTarget, "正在加载...", async () => {
            await saveCurrentConfigForAction();
            const result = await sendMessage({type: "load-styles"});
            stylesLoaded = true;
            renderStyleList(result.styles || []);
            renderStatus(await lastStatus());
            setFeedback(`已加载 ${Number(result.styles?.length || 0)} 个 Style。`, "success");
        });
    });

    document.getElementById("manual-sync-button").addEventListener("click", async (event) => {
        await withBusy(event.currentTarget, "正在同步...", async () => {
            await saveCurrentConfigForAction();
            const result = await sendMessage({type: "manual-sync"});
            renderStatus(result.status || await lastStatus());
            setFeedback("同步任务已完成。", result.updated?.length ? "success" : "warning");
        });
    });

    document.getElementById("generate-link-button").addEventListener("click", async (event) => {
        await withBusy(event.currentTarget, "正在生成...", async () => {
            await saveCurrentConfigForAction();
            const result = await sendMessage({type: "generate-current-tab-link"});
            renderStatus(result.status || await lastStatus());
            if (result.copied) {
                setFeedback("当前页 LinkPeek 链接已复制。", "success");
            } else if (result.appLaunch?.launched) {
                setFeedback("链接未复制，已按配置调起应用。", "warning");
            } else {
                setFeedback(result.error || "当前页不支持 LinkPeek 预览。", "warning");
            }
        });
    });

    document.getElementById("test-app-launch-button").addEventListener("click", async (event) => {
        await withBusy(event.currentTarget, "正在调起...", async () => {
            await saveCurrentConfigForAction();
            const result = await sendMessage({
                type: "test-app-launch",
                config: readConfig()
            });
            appLaunchTested = Boolean(result.launchResult?.launched);
            renderStatus(result.status || await lastStatus());
            setFeedback("已打开 URL Scheme 调起测试页。", appLaunchTested ? "success" : "warning");
        });
    });

    document.getElementById("shortcut-settings-button").addEventListener("click", () => {
        chrome.tabs.create({url: "chrome://extensions/shortcuts", active: true});
    });

    document.getElementById("open-admin-button").addEventListener("click", () => {
        const baseUrl = normalizedBaseUrlFromInput(false);
        if (!baseUrl) {
            setFeedback("请先填写 LinkPeek Base URL。", "warning");
            return;
        }
        chrome.tabs.create({url: `${baseUrl}/admin`, active: true});
    });
}

async function renderCommandState() {
    const value = document.getElementById("shortcut-value");
    try {
        const state = await sendMessage({type: "get-command-state"});
        commandShortcut = state.generateLinkShortcut || "";
        value.textContent = commandShortcut || "未绑定";
        value.classList.toggle("is-warning", !commandShortcut);
    } catch {
        commandShortcut = "";
        value.textContent = "读取失败";
        value.classList.add("is-warning");
    }
    renderSetupGuide();
}

async function updatePermissionState() {
    const state = getBaseUrlState();
    if (!state.ok) {
        permissionGranted = false;
        permissionOrigin = "";
        renderPermissionState(state.empty ? "填写 URL 后授权" : "URL 无效", "warning");
        renderSetupGuide();
        return;
    }

    const origin = originPatternForBaseUrl(state.value);
    permissionOrigin = origin;
    permissionGranted = await chrome.permissions.contains({origins: [origin]});
    renderPermissionState(permissionGranted ? "已授权" : "未授权", permissionGranted ? "success" : "warning");
    renderSetupGuide();
}

function markPermissionNeedsCheck() {
    const state = getBaseUrlState();
    if (!state.ok) {
        permissionGranted = false;
        permissionOrigin = "";
        renderPermissionState(state.empty ? "填写 URL 后授权" : "URL 无效", "warning");
        return;
    }
    const origin = originPatternForBaseUrl(state.value);
    if (origin !== permissionOrigin) {
        permissionGranted = false;
        permissionOrigin = origin;
        renderPermissionState("待授权", "warning");
    }
}

function renderPermissionState(label, type) {
    permissionState.textContent = label;
    permissionState.className = `state-pill ${type || ""}`.trim();
}

async function saveCurrentConfigForAction() {
    await sendMessage({
        type: "save-config",
        config: readConfig()
    });
}

function readConfig() {
    return configWithDefaults({
        setupMode: selectedSetupMode(),
        baseUrl: normalizedBaseUrlFromInput(),
        adminPassword: fields.adminPassword.value,
        defaultStyle: fields.defaultStyle.value,
        linuxDoSyncEnabled: fields.linuxDoSyncEnabled.checked,
        ngaSyncEnabled: fields.ngaSyncEnabled.checked,
        syncIntervalMinutes: fields.syncIntervalMinutes.value,
        launchAppEnabled: fields.launchAppEnabled.checked,
        appLaunchUrl: fields.appLaunchUrl.value || DEFAULT_APP_LAUNCH_URL,
        launcherCloseDelaySeconds: fields.launcherCloseDelaySeconds.value
    });
}

function renderConfig(config) {
    const normalized = configWithDefaults(config);
    for (const mode of fields.setupModes) {
        mode.checked = mode.value === normalized.setupMode;
    }
    fields.baseUrl.value = normalized.baseUrl;
    fields.adminPassword.value = normalized.adminPassword;
    fields.defaultStyle.value = normalized.defaultStyle;
    fields.linuxDoSyncEnabled.checked = normalized.linuxDoSyncEnabled;
    fields.ngaSyncEnabled.checked = normalized.ngaSyncEnabled;
    fields.syncIntervalMinutes.value = String(normalized.syncIntervalMinutes);
    fields.launchAppEnabled.checked = normalized.launchAppEnabled;
    fields.appLaunchUrl.value = normalized.appLaunchUrl || DEFAULT_APP_LAUNCH_URL;
    fields.launcherCloseDelaySeconds.value = String(normalized.launcherCloseDelaySeconds);
    renderMode();
    renderSetupGuide();
}

function renderMode() {
    const cookieMode = selectedSetupMode() === SETUP_MODES.COOKIE_SYNC;
    for (const section of document.querySelectorAll("[data-cookie-sync-section]")) {
        section.hidden = !cookieMode;
    }
    document.body.dataset.setupMode = selectedSetupMode();
}

function renderSetupGuide() {
    const baseUrlState = getBaseUrlState();
    const currentPermissionOrigin = baseUrlState.ok ? originPatternForBaseUrl(baseUrlState.value) : "";
    const currentPermissionGranted = permissionGranted && permissionOrigin === currentPermissionOrigin;
    const mode = selectedSetupMode();
    const cookieMode = mode === SETUP_MODES.COOKIE_SYNC;
    const wantsLaunch = fields.launchAppEnabled.checked;
    const hasAdminPassword = Boolean(fields.adminPassword.value.trim());
    const providerSelected = fields.linuxDoSyncEnabled.checked || fields.ngaSyncEnabled.checked;
    const hasStyle = Boolean(fields.defaultStyle.value.trim());

    const steps = [
        {
            title: "配置 Base URL",
            detail: baseUrlState.ok ? baseUrlState.value : "填写 LinkPeek 服务根地址。",
            state: baseUrlState.ok ? "done" : "pending"
        },
        {
            title: "授权 LinkPeek 域名",
            detail: baseUrlState.ok ? "允许扩展访问你的 LinkPeek 服务 API。" : "先填写 Base URL，再点击授权域名。",
            state: currentPermissionGranted ? "done" : "pending"
        },
        {
            title: "设置快捷键绑定",
            detail: commandShortcut ? `当前绑定：${commandShortcut}` : "打开 Chrome 快捷键设置页，为生成链接命令绑定快捷键。",
            state: commandShortcut ? "done" : "pending"
        },
        {
            title: "加载 Style",
            detail: "从 LinkPeek 服务读取可用 Style，仍可手动输入自定义值。",
            state: stylesLoaded ? "done" : "pending"
        }
    ];

    if (cookieMode) {
        steps.push(
            {
                title: "配置 Admin 密码",
                detail: providerSelected ? "Cookie 同步需要 Admin 密码写入 Provider 配置。" : "未启用 LinuxDo/NGA 同步时可留空。",
                state: hasAdminPassword || !providerSelected ? "done" : "pending"
            },
            {
                title: "测试 Admin 连接",
                detail: hasAdminPassword ? "验证密码可登录 Admin 并调用 Provider 配置接口。" : "填写 Admin 密码后再测试。",
                state: adminConnectionTested ? "done" : "optional"
            }
        );
    }

    steps.push(
        {
            title: "选择默认 Style",
            detail: hasStyle ? `当前默认 Style：${fields.defaultStyle.value.trim()}` : "留空表示生成链接时不追加 style 参数。",
            state: hasStyle ? "done" : "optional"
        },
        {
            title: "测试联动 URL Scheme",
            detail: wantsLaunch ? "确认 Chrome 能打开配置的 URL Scheme；链接未复制时也会调起。" : "未开启调起应用时可以跳过。",
            state: wantsLaunch ? (appLaunchTested ? "done" : "pending") : "optional"
        }
    );

    setupSteps.replaceChildren(...steps.map((step, index) => renderStep(step, index + 1)));
}

function renderStep(step, index) {
    const item = document.createElement("li");
    item.className = `setup-step ${step.state}`;

    const marker = document.createElement("span");
    marker.className = "setup-index";
    marker.textContent = step.state === "done" ? "OK" : String(index);

    const body = document.createElement("div");
    const title = document.createElement("strong");
    title.textContent = step.title;
    const detail = document.createElement("small");
    detail.textContent = step.detail;
    body.append(title, detail);

    const state = document.createElement("span");
    state.className = `state-pill ${step.state}`;
    state.textContent = stateLabel(step.state);

    item.append(marker, body, state);
    return item;
}

function stateLabel(state) {
    switch (state) {
        case "done":
            return "完成";
        case "optional":
            return "可选";
        default:
            return "待处理";
    }
}

function renderStyleList(styles) {
    const list = document.getElementById("style-list");
    list.replaceChildren(...styles.map((style) => {
        const option = document.createElement("option");
        option.value = style;
        return option;
    }));
    renderSetupGuide();
}

function renderStatus(status) {
    if (!status) {
        statusOutput.textContent = "暂无状态。";
        return;
    }
    if (Array.isArray(status.details?.styles)) {
        stylesLoaded = true;
        renderStyleList(status.details.styles);
    }
    if (status.details?.adminConnection) {
        adminConnectionTested = true;
    }
    if (status.details?.appLaunch?.test && status.details.appLaunch.launched) {
        appLaunchTested = true;
    }
    statusOutput.textContent = JSON.stringify(status, null, 2);
    renderSetupGuide();
}

function setFeedback(message, type = "") {
    feedback.textContent = message;
    feedback.className = `feedback ${type}`.trim();
}

async function withBusy(button, busyText, task, options = {}) {
    const target = button || form.querySelector("button[type='submit']");
    const original = target.textContent;
    target.dataset.defaultText ||= original;
    target.disabled = true;
    target.textContent = busyText;
    setFeedback(busyText, "");
    try {
        await task();
    } catch (error) {
        if (options.resultButton) {
            markButtonResult(options.resultButton, "error", "测试失败");
        }
        setFeedback(error.message || String(error), "error");
    } finally {
        const showingResult = options.resultButton === target
            && (target.classList.contains("is-success") || target.classList.contains("is-error"));
        if (!showingResult) {
            target.textContent = original;
        }
        target.disabled = false;
        renderSetupGuide();
    }
}

function markButtonResult(button, type, label) {
    if (!button) {
        return;
    }
    const original = button.dataset.defaultText || button.textContent;
    button.dataset.defaultText = original;
    button.classList.remove("is-success", "is-error");
    button.classList.add(type === "success" ? "is-success" : "is-error");
    button.textContent = label;
    clearTimeout(Number(button.dataset.resultTimer || 0));
    const timer = setTimeout(() => {
        button.classList.remove("is-success", "is-error");
        button.textContent = button.dataset.defaultText || original;
        delete button.dataset.resultTimer;
    }, 2600);
    button.dataset.resultTimer = String(timer);
}

async function sendMessage(message) {
    const response = await chrome.runtime.sendMessage(message);
    if (!response?.ok) {
        throw new Error(response?.error || "扩展后台没有返回成功结果。");
    }
    return response.result;
}

async function lastStatus() {
    const state = await sendMessage({type: "get-state"});
    return state.lastStatus;
}

function selectedSetupMode() {
    return fields.setupModes.find((mode) => mode.checked)?.value || SETUP_MODES.COOKIE_SYNC;
}

function getBaseUrlState() {
    const raw = fields.baseUrl.value.trim();
    if (!raw) {
        return {ok: false, empty: true, value: ""};
    }
    try {
        return {ok: true, empty: false, value: normalizeBaseUrl(raw)};
    } catch {
        return {ok: false, empty: false, value: ""};
    }
}

function normalizedBaseUrlFromInput(required = true) {
    const value = fields.baseUrl.value.trim();
    if (!value) {
        if (required) {
            throw new Error("请填写 LinkPeek Base URL。");
        }
        return "";
    }
    return normalizeBaseUrl(value);
}
