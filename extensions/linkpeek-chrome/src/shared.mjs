export const DEFAULT_APP_LAUNCH_URL = "shortcuts://run-shortcut?name=LinkPeek联动打开";
export const DEFAULT_LAUNCHER_CLOSE_DELAY_SECONDS = 10;

export const SETUP_MODES = Object.freeze({
    COOKIE_SYNC: "cookie-sync",
    SHORTCUT_ONLY: "shortcut-only"
});

export const DEFAULT_CONFIG = Object.freeze({
    setupMode: SETUP_MODES.COOKIE_SYNC,
    baseUrl: "",
    adminPassword: "",
    defaultStyle: "",
    linuxDoSyncEnabled: true,
    ngaSyncEnabled: true,
    syncIntervalMinutes: 60,
    launchAppEnabled: false,
    appLaunchUrl: DEFAULT_APP_LAUNCH_URL,
    launcherCloseDelaySeconds: DEFAULT_LAUNCHER_CLOSE_DELAY_SECONDS
});

export const LINUXDO_COOKIE_NAMES = Object.freeze(["_t", "cf_clearance", "_forum_session"]);
export const NGA_COOKIE_NAMES = Object.freeze(["ngaPassportUid", "ngaPassportCid"]);

export const PROVIDER_COOKIE_DEFINITIONS = Object.freeze({
    linuxdo: Object.freeze({
        providerId: "linuxdo",
        cookieNames: LINUXDO_COOKIE_NAMES,
        requiredCookieNames: Object.freeze(["_t"]),
        fieldByCookie: Object.freeze({
            _t: "_t",
            cf_clearance: "cf_clearance",
            _forum_session: "_forum_session"
        })
    }),
    nga: Object.freeze({
        providerId: "nga",
        cookieNames: NGA_COOKIE_NAMES,
        fieldByCookie: Object.freeze({
            ngaPassportUid: "NGA_PASSPORT_UID",
            ngaPassportCid: "NGA_PASSPORT_CID"
        })
    })
});

const LOCAL_HOST_PATTERN = /^(localhost|127(?:\.\d{1,3}){3}|\[::1\]|::1)(?::|\/|$)/i;

export function normalizeBaseUrl(value) {
    const raw = String(value || "").trim();
    if (!raw) {
        return "";
    }

    let candidate = raw;
    if (!/^[a-z][a-z\d+.-]*:\/\//i.test(candidate)) {
        candidate = `${LOCAL_HOST_PATTERN.test(candidate) ? "http" : "https"}://${candidate}`;
    }

    const url = new URL(candidate);
    if (!["http:", "https:"].includes(url.protocol)) {
        throw new Error("LinkPeek Base URL must use http or https.");
    }
    return url.origin;
}

export function normalizeSyncInterval(value) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) {
        return DEFAULT_CONFIG.syncIntervalMinutes;
    }
    return Math.min(1440, Math.max(1, Math.round(numeric)));
}

export function normalizeLauncherCloseDelay(value) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) {
        return DEFAULT_LAUNCHER_CLOSE_DELAY_SECONDS;
    }
    return Math.min(120, Math.max(1, Math.round(numeric)));
}

export function configWithDefaults(raw = {}) {
    const baseUrl = raw.baseUrl ? normalizeBaseUrl(raw.baseUrl) : "";
    return {
        ...DEFAULT_CONFIG,
        ...raw,
        setupMode: normalizeSetupMode(raw),
        baseUrl,
        adminPassword: String(raw.adminPassword || ""),
        defaultStyle: String(raw.defaultStyle || "").trim(),
        linuxDoSyncEnabled: raw.linuxDoSyncEnabled !== false,
        ngaSyncEnabled: raw.ngaSyncEnabled !== false,
        syncIntervalMinutes: normalizeSyncInterval(raw.syncIntervalMinutes),
        launchAppEnabled: raw.launchAppEnabled === true,
        appLaunchUrl: String(raw.appLaunchUrl || DEFAULT_APP_LAUNCH_URL).trim() || DEFAULT_APP_LAUNCH_URL,
        launcherCloseDelaySeconds: normalizeLauncherCloseDelay(raw.launcherCloseDelaySeconds)
    };
}

export function normalizeSetupMode(raw = {}) {
    if (Object.values(SETUP_MODES).includes(raw.setupMode)) {
        return raw.setupMode;
    }
    return DEFAULT_CONFIG.setupMode;
}

export function originPatternForBaseUrl(baseUrl) {
    const normalized = normalizeBaseUrl(baseUrl);
    if (!normalized) {
        return "";
    }
    const url = new URL(normalized);
    return `${url.protocol}//${url.hostname}/*`;
}

export function isHttpUrl(value) {
    try {
        const url = new URL(String(value || ""));
        return url.protocol === "http:" || url.protocol === "https:";
    } catch {
        return false;
    }
}

export function buildSupportUrl(baseUrl, sourceUrl) {
    if (!isHttpUrl(sourceUrl)) {
        throw new Error("Current tab is not an http or https page.");
    }
    const url = new URL("/api/preview/support", `${normalizeBaseUrl(baseUrl)}/`);
    url.searchParams.set("url", String(sourceUrl).trim());
    return url.toString();
}

export function buildPreviewUrl(baseUrl, sourceUrl, style = "") {
    if (!isHttpUrl(sourceUrl)) {
        throw new Error("Current tab is not an http or https page.");
    }
    const url = new URL("/preview", `${normalizeBaseUrl(baseUrl)}/`);
    url.searchParams.set("url", String(sourceUrl).trim());
    const normalizedStyle = String(style || "").trim();
    if (normalizedStyle) {
        url.searchParams.set("style", normalizedStyle);
    }
    return url.toString();
}

export function selectCookieValues(cookies, cookieNames) {
    const values = {};
    for (const name of cookieNames) {
        const candidates = (cookies || [])
            .filter((cookie) => cookie && cookie.name === name && String(cookie.value || "").trim())
            .sort(compareCookies);
        if (candidates.length > 0) {
            values[name] = String(candidates[0].value).trim();
        }
    }
    return values;
}

export function buildProviderPayload(providerKey, cookieValues) {
    const definition = PROVIDER_COOKIE_DEFINITIONS[providerKey];
    if (!definition) {
        throw new Error(`Unknown provider cookie definition: ${providerKey}`);
    }

    const requiredCookieNames = definition.requiredCookieNames || definition.cookieNames;
    const missing = requiredCookieNames.filter((name) => !String((cookieValues || {})[name] || "").trim());
    if (missing.length > 0) {
        return {
            providerId: definition.providerId,
            values: null,
            missing
        };
    }

    const values = {};
    for (const cookieName of definition.cookieNames) {
        const value = String((cookieValues || {})[cookieName] || "").trim();
        if (value) {
            values[definition.fieldByCookie[cookieName]] = value;
        }
    }
    return {
        providerId: definition.providerId,
        values,
        missing: []
    };
}

export function shouldLaunchApp(config) {
    return config.launchAppEnabled === true && String(config.appLaunchUrl || "").trim().length > 0;
}

function compareCookies(left, right) {
    return cookieScore(right) - cookieScore(left);
}

function cookieScore(cookie) {
    let score = 0;
    if (cookie.hostOnly) {
        score += 8;
    }
    if (cookie.secure) {
        score += 4;
    }
    if (cookie.httpOnly) {
        score += 2;
    }
    score += String(cookie.path || "").length / 1000;
    if (Number.isFinite(cookie.expirationDate)) {
        score += Math.min(cookie.expirationDate, 9999999999) / 1000000000000;
    }
    return score;
}
