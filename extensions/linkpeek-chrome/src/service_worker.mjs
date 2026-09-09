import {
    DEFAULT_CONFIG,
    LINUXDO_COOKIE_NAMES,
    NGA_COOKIE_NAMES,
    SETUP_MODES,
    buildPreviewUrl,
    buildProviderPayload,
    buildSupportUrl,
    configWithDefaults,
    isHttpUrl,
    originPatternForBaseUrl,
    selectCookieValues,
    shouldLaunchApp
} from "./shared.mjs";

const STORAGE_KEYS = {
    config: "config",
    lastHttpTab: "lastHttpTab",
    lastStatus: "lastStatus"
};

const SYNC_ALARM_NAME = "sync-provider-cookies";
const OFFSCREEN_DOCUMENT_PATH = "offscreen.html";
const LAUNCHER_PAGE_PATH = "launcher.html";
const BADGE_RESET_DELAY_MS = 1800;
const ADMIN_SYNC_TAB_TIMEOUT_MS = 30000;
const ADMIN_SYNC_SCRIPT_TIMEOUT_MS = 20000;
const LINUXDO_COOKIE_DOMAIN = "linux.do";
const LINUXDO_PARTITION_KEY = Object.freeze({topLevelSite: "https://linux.do"});

chrome.runtime.onInstalled.addListener(() => {
    bootstrap({sync: true}).catch((error) => recordFailure("初始化失败", error));
});

chrome.runtime.onStartup.addListener(() => {
    bootstrap({sync: true}).catch((error) => recordFailure("启动失败", error));
});

chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === SYNC_ALARM_NAME) {
        syncProviderCookies("定时同步").catch((error) => recordFailure("定时同步失败", error));
    }
});

chrome.commands.onCommand.addListener((command) => {
    if (command === "generate-linkpeek-link") {
        setStatus("收到快捷键，正在生成 LinkPeek 链接...", "info")
            .then(() => showBadge("...", "#0a84ff"))
            .then(() => generateCurrentTabLink())
            .catch((error) => recordFailure("生成链接失败", error, "ERR"));
    }
});

chrome.action.onClicked.addListener(() => {
    openAdminOrOptions().catch((error) => recordFailure("打开后台失败", error, "ERR"));
});

chrome.tabs.onActivated.addListener(({tabId}) => {
    rememberHttpTabById(tabId).catch(() => {});
});

chrome.tabs.onUpdated.addListener((_tabId, changeInfo, tab) => {
    if (tab.active && (changeInfo.url || changeInfo.status === "complete")) {
        rememberHttpTab(tab).catch(() => {});
    }
});

chrome.windows.onFocusChanged.addListener((windowId) => {
    if (windowId !== chrome.windows.WINDOW_ID_NONE) {
        rememberActiveHttpTab(windowId).catch(() => {});
    }
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message?.type === "copy-to-clipboard") {
        return false;
    }
    handleMessage(message)
        .then((result) => sendResponse({ok: true, result}))
        .catch((error) => sendResponse({ok: false, error: messageFromError(error)}));
    return true;
});

async function bootstrap({sync = false} = {}) {
    const config = await getConfig();
    await ensureAlarm(config);
    if (sync && shouldAutoSync(config)) {
        await syncProviderCookies("启动同步");
    }
}

async function handleMessage(message) {
    switch (message?.type) {
        case "get-state":
            return {
                config: await getConfig(),
                lastStatus: await getLastStatus()
            };
        case "save-config": {
            const config = configWithDefaults(message.config || {});
            await saveConfig(config);
            await ensureAlarm(config);
            await setStatus("配置已保存", "success");
            return {config, lastStatus: await getLastStatus()};
        }
        case "test-connection":
            return testConnection();
        case "test-admin-connection":
            return testAdminConnection();
        case "load-styles":
            return loadStyles();
        case "manual-sync":
            return syncProviderCookies("手动同步");
        case "generate-current-tab-link":
            return generateCurrentTabLink();
        case "test-app-launch":
            return testAppLaunch(message.config || {});
        case "get-command-state":
            return getCommandState();
        default:
            throw new Error("Unknown extension message.");
    }
}

async function getConfig() {
    const data = await chrome.storage.local.get(STORAGE_KEYS.config);
    return configWithDefaults(data[STORAGE_KEYS.config] || DEFAULT_CONFIG);
}

async function saveConfig(config) {
    await chrome.storage.local.set({[STORAGE_KEYS.config]: configWithDefaults(config)});
}

async function getLastStatus() {
    const data = await chrome.storage.local.get(STORAGE_KEYS.lastStatus);
    return data[STORAGE_KEYS.lastStatus] || null;
}

async function setStatus(message, type = "info", details = {}) {
    const status = {
        type,
        message,
        details,
        updatedAt: new Date().toISOString()
    };
    await chrome.storage.local.set({[STORAGE_KEYS.lastStatus]: status});
    return status;
}

async function ensureAlarm(config) {
    const normalized = configWithDefaults(config);
    await chrome.alarms.clear(SYNC_ALARM_NAME);
    if (shouldAutoSync(normalized)) {
        chrome.alarms.create(SYNC_ALARM_NAME, {
            periodInMinutes: normalized.syncIntervalMinutes
        });
    }
}

async function ensureLinkPeekPermission(config) {
    const origin = originPatternForBaseUrl(config.baseUrl);
    if (!origin) {
        throw new Error("请先填写 LinkPeek Base URL。");
    }
    const granted = await chrome.permissions.contains({origins: [origin]});
    if (!granted) {
        throw new Error(`缺少 LinkPeek 域名权限，请在配置页授权：${origin}`);
    }
    return origin;
}

async function testConnection() {
    const config = await getConfig();
    await ensureLinkPeekPermission(config);
    const healthUrl = new URL("/api/health", `${config.baseUrl}/`);
    const response = await fetch(healthUrl.toString(), {
        headers: {Accept: "application/json"},
        cache: "no-store"
    });
    if (!response.ok) {
        throw new Error(`LinkPeek 健康检查失败：HTTP ${response.status}`);
    }
    const body = await response.json();
    await setStatus("LinkPeek 连接正常", "success", {health: body});
    return body;
}

async function testAdminConnection() {
    const config = await getConfig();
    await ensureLinkPeekPermission(config);
    if (!config.adminPassword) {
        throw new Error("请先在配置页保存 Admin 密码。");
    }

    const result = await saveProviderPayloadsInAdminContext(config, []);
    const status = await setStatus("Admin 连接正常", "success", {
        adminConnection: result
    });
    return {status, adminConnection: result};
}

async function loadStyles() {
    const config = await getConfig();
    await ensureLinkPeekPermission(config);
    const stylesUrl = new URL("/api/preview/styles", `${config.baseUrl}/`);
    const response = await fetch(stylesUrl.toString(), {
        headers: {Accept: "application/json"},
        cache: "no-store"
    });
    if (!response.ok) {
        throw new Error(`加载 Style 失败：HTTP ${response.status}`);
    }
    const body = await response.json();
    await setStatus("Style 列表已加载", "success", {styles: body.styles || []});
    return body;
}

async function syncProviderCookies(trigger) {
    const config = await getConfig();
    if (config.setupMode !== SETUP_MODES.COOKIE_SYNC) {
        throw new Error("当前使用场景未启用 Cookie 同步。");
    }
    await ensureLinkPeekPermission(config);
    if (!config.linuxDoSyncEnabled && !config.ngaSyncEnabled) {
        const status = await setStatus(`${trigger}未写入配置，没有启用的同步项`, "warning", {skipped: []});
        await showBadge("NO", "#8a6d00");
        return {updated: [], skipped: [], status};
    }
    if (!config.adminPassword) {
        throw new Error("请先在配置页保存 Admin 密码。");
    }

    const payloads = [];
    const skipped = [];

    if (config.linuxDoSyncEnabled) {
        const cookies = await readLinuxDoCookies();
        const values = selectCookieValues(cookies, LINUXDO_COOKIE_NAMES);
        const payload = buildProviderPayload("linuxdo", values);
        if (payload.values) {
            payloads.push(payload);
        } else {
            skipped.push({providerId: payload.providerId, missing: payload.missing});
        }
    }

    if (config.ngaSyncEnabled) {
        const cookies = await readNgaCookies();
        const values = selectCookieValues(cookies, NGA_COOKIE_NAMES);
        const payload = buildProviderPayload("nga", values);
        if (payload.values) {
            payloads.push(payload);
        } else {
            skipped.push({providerId: payload.providerId, missing: payload.missing});
        }
    }

    if (payloads.length === 0) {
        const detail = skipped.length ? `缺少 Cookie：${formatSkipped(skipped)}` : "没有启用的同步项";
        const status = await setStatus(`${trigger}未写入配置，${detail}`, "warning", {skipped});
        await showBadge("NO", "#8a6d00");
        return {updated: [], skipped, status};
    }

    const result = await saveProviderPayloadsInAdminContext(config, payloads);
    const status = await setStatus(`${trigger}完成：${payloads.map((item) => item.providerId).join(", ")}`, "success", {
        updated: payloads.map((item) => item.providerId),
        skipped,
        adminResult: result
    });
    await showBadge("OK", "#1f7a3a");
    return {
        updated: payloads.map((item) => item.providerId),
        skipped,
        status
    };
}

async function readLinuxDoCookies() {
    const cookieGroups = await Promise.all([
        chrome.cookies.getAll({domain: LINUXDO_COOKIE_DOMAIN}),
        readPartitionedCookies({domain: LINUXDO_COOKIE_DOMAIN}, LINUXDO_PARTITION_KEY)
    ]);
    return cookieGroups.flat();
}

async function readNgaCookies() {
    const cookieGroups = await Promise.all([
        chrome.cookies.getAll({url: "https://bbs.nga.cn/"}),
        chrome.cookies.getAll({url: "https://nga.178.com/"}),
        chrome.cookies.getAll({url: "https://ngabbs.com/"})
    ]);
    return cookieGroups.flat();
}

async function readPartitionedCookies(details, partitionKey) {
    try {
        return await chrome.cookies.getAll({
            ...details,
            partitionKey
        });
    } catch (error) {
        console.debug("partitioned_cookie_read_failed", messageFromError(error));
        return [];
    }
}

async function saveProviderPayloadsInAdminContext(config, payloads) {
    let tab;
    try {
        tab = await chrome.tabs.create({
            url: `${config.baseUrl}/admin`,
            active: false
        });
        await waitForTabComplete(tab.id, ADMIN_SYNC_TAB_TIMEOUT_MS);
        const [{result}] = await chrome.scripting.executeScript({
            target: {tabId: tab.id},
            func: adminSyncScript,
            args: [{
                adminPassword: config.adminPassword,
                payloads,
                timeoutMs: ADMIN_SYNC_SCRIPT_TIMEOUT_MS
            }]
        });
        if (!result || result.ok !== true) {
            throw new Error(result?.error || "Admin 同步脚本没有返回成功结果。");
        }
        return result;
    } finally {
        if (tab?.id) {
            try {
                await chrome.tabs.remove(tab.id);
            } catch {
                // The tab may already be gone.
            }
        }
    }
}

function adminSyncScript({adminPassword, payloads, timeoutMs}) {
    const headers = {
        Accept: "application/json",
        "Content-Type": "application/json"
    };

    function withTimeout(promise, label) {
        return Promise.race([
            promise,
            new Promise((_, reject) => {
                setTimeout(() => reject(new Error(`${label} timeout`)), timeoutMs);
            })
        ]);
    }

    async function readJson(response) {
        const text = await response.text();
        if (!text) {
            return null;
        }
        try {
            return JSON.parse(text);
        } catch {
            throw new Error(text);
        }
    }

    async function fetchJson(url, options = {}) {
        const response = await fetch(url, {
            headers,
            credentials: "same-origin",
            cache: "no-store",
            ...options
        });
        const body = await readJson(response);
        if (!response.ok) {
            throw new Error(body?.message || body?.error || `HTTP ${response.status}`);
        }
        return body;
    }

    return withTimeout((async () => {
        let session = await fetchJson("/api/admin/session");
        if (!session?.enabled) {
            throw new Error("LinkPeek Admin 未启用。");
        }
        if (!session.authenticated) {
            await fetchJson("/api/admin/login", {
                method: "POST",
                body: JSON.stringify({password: adminPassword})
            });
            session = await fetchJson("/api/admin/session");
        }
        if (!session.authenticated) {
            throw new Error("Admin 登录失败。");
        }

        const updated = [];
        for (const payload of payloads) {
            await fetchJson(`/api/admin/provider-config/${encodeURIComponent(payload.providerId)}`, {
                method: "PUT",
                body: JSON.stringify({values: payload.values})
            });
            updated.push(payload.providerId);
        }
        return {ok: true, updated};
    })(), "Admin sync").catch((error) => ({ok: false, error: error.message || String(error)}));
}

async function waitForTabComplete(tabId, timeoutMs) {
    const current = await chrome.tabs.get(tabId);
    if (current.status === "complete") {
        return;
    }

    await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
            chrome.tabs.onUpdated.removeListener(listener);
            reject(new Error("打开 LinkPeek Admin 超时。"));
        }, timeoutMs);

        function listener(updatedTabId, changeInfo) {
            if (updatedTabId === tabId && changeInfo.status === "complete") {
                clearTimeout(timeout);
                chrome.tabs.onUpdated.removeListener(listener);
                resolve();
            }
        }

        chrome.tabs.onUpdated.addListener(listener);
    });
}

async function generateCurrentTabLink() {
    const config = await getConfig();
    const sourceTab = await resolveSourceTab();
    const generationResult = await generatePreviewLink(config, sourceTab);
    const launchResult = await launchConfiguredApp(config);
    const copied = generationResult.copied === true;
    const launchFailed = launchResult.enabled && !launchResult.launched;
    const statusType = copied && generationResult.supported && !launchFailed ? "success" : "warning";
    const statusMessage = statusMessageForGeneration(generationResult, launchResult);
    const status = await setStatus(statusMessage, statusType, {
        ...generationResult,
        appLaunch: launchResult
    });
    await showBadge(
        copied && generationResult.supported ? "OK" : copied ? "URL" : "NO",
        copied && generationResult.supported ? "#1f7a3a" : "#8a6d00"
    );
    return {
        ...generationResult,
        appLaunch: launchResult,
        status
    };
}

async function generatePreviewLink(config, sourceTab = null) {
    try {
        await ensureLinkPeekPermission(config);
        const tab = sourceTab || await resolveSourceTab();
        if (!tab?.url) {
            throw new Error("当前窗口没有可用页面。");
        }
        if (!isHttpUrl(tab.url)) {
            throw new Error("没有找到可用于生成 LinkPeek 链接的 http/https 标签页，未修改剪贴板。");
        }

        const supportUrl = buildSupportUrl(config.baseUrl, tab.url);
        const supportResponse = await fetch(supportUrl, {
            headers: {Accept: "application/json"},
            cache: "no-store"
        });
        if (!supportResponse.ok) {
            throw new Error(`支持判定失败：HTTP ${supportResponse.status}`);
        }
        const support = await supportResponse.json();
        if (!support?.supported) {
            await copyText(tab.url);
            return {
                copied: true,
                supported: false,
                copiedOriginal: true,
                sourceUrl: tab.url,
                clipboardUrl: tab.url,
                support,
                message: "当前页面不支持 LinkPeek 预览，已复制原始链接。"
            };
        }

        const previewUrl = buildPreviewUrl(config.baseUrl, tab.url, config.defaultStyle);
        await copyText(previewUrl);
        return {
            copied: true,
            supported: true,
            copiedOriginal: false,
            sourceUrl: tab.url,
            clipboardUrl: previewUrl,
            previewUrl
        };
    } catch (error) {
        return {
            copied: false,
            supported: false,
            error: messageFromError(error)
        };
    }
}

function statusMessageForGeneration(generationResult, launchResult) {
    if (generationResult.copied && !generationResult.supported) {
        return launchResult.enabled && launchResult.launched
            ? "当前页面不支持 LinkPeek 预览，已复制原始链接并按配置调起应用"
            : launchResult.enabled
                ? "当前页面不支持 LinkPeek 预览，已复制原始链接，但调起应用失败"
                : "当前页面不支持 LinkPeek 预览，已复制原始链接";
    }
    if (generationResult.copied) {
        return launchResult.enabled && !launchResult.launched
            ? "LinkPeek 链接已复制，但调起应用失败"
            : "LinkPeek 链接已复制";
    }
    return launchResult.enabled
        ? "LinkPeek 链接未复制，已按配置调起应用"
        : `LinkPeek 链接未复制：${generationResult.error || "生成失败"}`;
}

async function resolveSourceTab() {
    const [activeTab] = await chrome.tabs.query({active: true, currentWindow: true});
    if (isHttpUrl(activeTab?.url)) {
        await rememberHttpTab(activeTab);
        return activeTab;
    }

    const stored = await chrome.storage.local.get(STORAGE_KEYS.lastHttpTab);
    const candidate = stored[STORAGE_KEYS.lastHttpTab];
    if (candidate?.tabId) {
        try {
            const tab = await chrome.tabs.get(candidate.tabId);
            if (isHttpUrl(tab?.url)) {
                return tab;
            }
        } catch {
            // The remembered tab may have been closed.
        }
    }

    const tabs = await chrome.tabs.query({windowType: "normal"});
    const httpTabs = tabs
        .filter((tab) => isHttpUrl(tab.url))
        .sort((left, right) => Number(right.lastAccessed || 0) - Number(left.lastAccessed || 0));
    return httpTabs[0] || activeTab;
}

async function rememberActiveHttpTab(windowId) {
    const [tab] = await chrome.tabs.query({active: true, windowId});
    await rememberHttpTab(tab);
}

async function rememberHttpTabById(tabId) {
    const tab = await chrome.tabs.get(tabId);
    await rememberHttpTab(tab);
}

async function rememberHttpTab(tab) {
    if (!isHttpUrl(tab?.url)) {
        return;
    }
    await chrome.storage.local.set({
        [STORAGE_KEYS.lastHttpTab]: {
            tabId: tab.id,
            windowId: tab.windowId,
            url: tab.url,
            title: tab.title || "",
            updatedAt: Date.now()
        }
    });
}

async function launchConfiguredApp(config) {
    if (!shouldLaunchApp(config)) {
        return {enabled: false, launched: false};
    }
    try {
        const params = new URLSearchParams({
            target: config.appLaunchUrl,
            closeDelaySeconds: String(config.launcherCloseDelaySeconds)
        });
        const launcherUrl = chrome.runtime.getURL(`${LAUNCHER_PAGE_PATH}?${params.toString()}`);
        const tab = await chrome.tabs.create({url: launcherUrl, active: false});
        return {
            enabled: true,
            launched: true,
            tabId: tab.id
        };
    } catch (error) {
        return {
            enabled: true,
            launched: false,
            error: messageFromError(error)
        };
    }
}

async function testAppLaunch(configOverride) {
    const currentConfig = await getConfig();
    const config = configWithDefaults({
        ...currentConfig,
        ...configOverride,
        launchAppEnabled: true
    });
    if (!config.appLaunchUrl) {
        throw new Error("请先填写 URL Scheme。");
    }
    const launchResult = await launchConfiguredApp(config);
    const status = await setStatus(
        launchResult.launched ? "URL Scheme 测试已打开调起页" : "URL Scheme 测试未能打开调起页",
        launchResult.launched ? "success" : "warning",
        {appLaunch: {...launchResult, test: true}}
    );
    return {launchResult, status};
}

async function getCommandState() {
    const commands = await chrome.commands.getAll();
    const command = commands.find((item) => item.name === "generate-linkpeek-link") || null;
    return {
        commands,
        generateLinkShortcut: command?.shortcut || "",
        generateLinkDescription: command?.description || ""
    };
}

async function copyText(text) {
    await ensureOffscreenDocument();
    const response = await chrome.runtime.sendMessage({
        type: "copy-to-clipboard",
        text
    });
    if (!response?.ok) {
        throw new Error(response?.error || "复制到剪贴板失败。");
    }
}

async function ensureOffscreenDocument() {
    if (await hasOffscreenDocument()) {
        return;
    }
    await chrome.offscreen.createDocument({
        url: OFFSCREEN_DOCUMENT_PATH,
        reasons: [chrome.offscreen.Reason.CLIPBOARD],
        justification: "Copy generated LinkPeek preview URLs to the clipboard."
    });
}

async function hasOffscreenDocument() {
    if (chrome.offscreen?.hasDocument) {
        return chrome.offscreen.hasDocument();
    }
    const contexts = await chrome.runtime.getContexts({
        contextTypes: ["OFFSCREEN_DOCUMENT"],
        documentUrls: [chrome.runtime.getURL(OFFSCREEN_DOCUMENT_PATH)]
    });
    return contexts.length > 0;
}

async function openAdminOrOptions() {
    const config = await getConfig();
    if (!config.baseUrl) {
        await chrome.runtime.openOptionsPage();
        return;
    }
    await chrome.tabs.create({url: `${config.baseUrl}/admin`, active: true});
}

async function recordFailure(prefix, error, badgeText = "ERR") {
    await setStatus(`${prefix}：${messageFromError(error)}`, "error", {
        error: messageFromError(error)
    });
    await showBadge(badgeText, "#a12727");
}

async function showBadge(text, color) {
    await chrome.action.setBadgeBackgroundColor({color});
    await chrome.action.setBadgeText({text});
    setTimeout(() => {
        chrome.action.setBadgeText({text: ""});
    }, BADGE_RESET_DELAY_MS);
}

function messageFromError(error) {
    return error?.message || String(error || "Unknown error");
}

function shouldAutoSync(config) {
    return Boolean(
        config.baseUrl
        && config.setupMode === SETUP_MODES.COOKIE_SYNC
        && config.adminPassword
        && (config.linuxDoSyncEnabled || config.ngaSyncEnabled)
    );
}

function formatSkipped(skipped) {
    return skipped
        .map((item) => `${item.providerId}(${item.missing.join(", ")})`)
        .join("; ");
}
