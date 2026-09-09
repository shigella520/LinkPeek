import test from "node:test";
import assert from "node:assert/strict";

import {
    DEFAULT_APP_LAUNCH_URL,
    SETUP_MODES,
    buildPreviewUrl,
    buildProviderPayload,
    buildSupportUrl,
    configWithDefaults,
    isHttpUrl,
    normalizeBaseUrl,
    normalizeLauncherCloseDelay,
    normalizeSyncInterval,
    originPatternForBaseUrl,
    selectCookieValues,
    shouldLaunchApp
} from "../src/shared.mjs";

test("normalizeBaseUrl trims paths and defaults public hosts to https", () => {
    assert.equal(normalizeBaseUrl(" preview.example.com/admin "), "https://preview.example.com");
    assert.equal(normalizeBaseUrl("https://preview.example.com/foo?bar=1"), "https://preview.example.com");
});

test("normalizeBaseUrl defaults local hosts to http", () => {
    assert.equal(normalizeBaseUrl("localhost:8080"), "http://localhost:8080");
    assert.equal(normalizeBaseUrl("127.0.0.1:8080"), "http://127.0.0.1:8080");
});

test("isHttpUrl rejects browser and extension pages", () => {
    assert.equal(isHttpUrl("https://bbs.nga.cn/read.php?tid=47028948"), true);
    assert.equal(isHttpUrl("chrome://extensions"), false);
    assert.equal(isHttpUrl("chrome-extension://abc/options.html"), false);
});

test("originPatternForBaseUrl returns exact origin permission pattern", () => {
    assert.equal(originPatternForBaseUrl("https://preview.example.com/admin"), "https://preview.example.com/*");
    assert.equal(originPatternForBaseUrl("http://localhost:8080/admin"), "http://localhost/*");
});

test("normalizeSyncInterval clamps invalid and out-of-range values", () => {
    assert.equal(normalizeSyncInterval("bad"), 60);
    assert.equal(normalizeSyncInterval(0), 1);
    assert.equal(normalizeSyncInterval(2000), 1440);
});

test("normalizeLauncherCloseDelay clamps invalid and out-of-range values", () => {
    assert.equal(normalizeLauncherCloseDelay("bad"), 10);
    assert.equal(normalizeLauncherCloseDelay(0), 1);
    assert.equal(normalizeLauncherCloseDelay(999), 120);
});

test("buildSupportUrl uses URLSearchParams encoding", () => {
    const url = buildSupportUrl("https://preview.example.com", "https://linux.do/t/topic/123?x=1");
    assert.equal(
        url,
        "https://preview.example.com/api/preview/support?url=https%3A%2F%2Flinux.do%2Ft%2Ftopic%2F123%3Fx%3D1"
    );
});

test("buildPreviewUrl appends style only when configured", () => {
    assert.equal(
        buildPreviewUrl("https://preview.example.com", "https://linux.do/t/topic/123", ""),
        "https://preview.example.com/preview?url=https%3A%2F%2Flinux.do%2Ft%2Ftopic%2F123"
    );
    assert.equal(
        buildPreviewUrl("https://preview.example.com", "https://linux.do/t/topic/123", "FREESTYLE"),
        "https://preview.example.com/preview?url=https%3A%2F%2Flinux.do%2Ft%2Ftopic%2F123&style=FREESTYLE"
    );
});

test("selectCookieValues picks the strongest matching cookie", () => {
    const values = selectCookieValues([
        {name: "_t", value: "weak", hostOnly: false, secure: true, path: "/"},
        {name: "_t", value: "strong", hostOnly: true, secure: true, httpOnly: true, path: "/"},
        {name: "other", value: "ignored"}
    ], ["_t"]);
    assert.deepEqual(values, {_t: "strong"});
});

test("buildProviderPayload maps LinuxDo cookie names to LinkPeek config keys", () => {
    const payload = buildProviderPayload("linuxdo", {
        _t: "token",
        cf_clearance: "clearance",
        _forum_session: "session"
    });
    assert.deepEqual(payload, {
        providerId: "linuxdo",
        values: {
            _t: "token",
            cf_clearance: "clearance",
            _forum_session: "session"
        },
        missing: []
    });
});

test("buildProviderPayload maps NGA cookie names to LinkPeek config keys", () => {
    const payload = buildProviderPayload("nga", {
        ngaPassportUid: "uid",
        ngaPassportCid: "cid"
    });
    assert.deepEqual(payload, {
        providerId: "nga",
        values: {
            NGA_PASSPORT_UID: "uid",
            NGA_PASSPORT_CID: "cid"
        },
        missing: []
    });
});

test("buildProviderPayload keeps LinuxDo non-token cookies optional", () => {
    const payload = buildProviderPayload("linuxdo", {
        _t: "token",
        cf_clearance: "",
        _forum_session: ""
    });
    assert.deepEqual(payload, {
        providerId: "linuxdo",
        values: {
            _t: "token"
        },
        missing: []
    });
});

test("buildProviderPayload reports missing required cookies without empty values", () => {
    const payload = buildProviderPayload("linuxdo", {
        _t: "",
        cf_clearance: "clearance",
        _forum_session: "session"
    });
    assert.equal(payload.providerId, "linuxdo");
    assert.equal(payload.values, null);
    assert.deepEqual(payload.missing, ["_t"]);
});

test("configWithDefaults keeps URL scheme default and launch switch explicit", () => {
    const config = configWithDefaults({
        launchAppEnabled: true,
        appLaunchUrl: "",
        syncIntervalMinutes: "15"
    });
    assert.equal(config.appLaunchUrl, DEFAULT_APP_LAUNCH_URL);
    assert.equal(config.syncIntervalMinutes, 15);
    assert.equal(config.launcherCloseDelaySeconds, 10);
    assert.equal(shouldLaunchApp(config), true);
    assert.equal(shouldLaunchApp(configWithDefaults({launchAppEnabled: false})), false);
});

test("configWithDefaults keeps setup mode explicit and defaults unknown values", () => {
    assert.equal(configWithDefaults({setupMode: SETUP_MODES.SHORTCUT_ONLY}).setupMode, SETUP_MODES.SHORTCUT_ONLY);
    assert.equal(configWithDefaults({setupMode: "bad"}).setupMode, SETUP_MODES.COOKIE_SYNC);
});
