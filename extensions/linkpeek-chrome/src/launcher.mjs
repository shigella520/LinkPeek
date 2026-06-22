const feedback = document.getElementById("launcher-feedback");
const targetOutput = document.getElementById("launcher-target");
const launchButton = document.getElementById("launch-button");
const closeButton = document.getElementById("close-button");

const params = new URLSearchParams(window.location.search);
const target = params.get("target") || "";
const closeDelayMs = normalizeCloseDelayMs(params.get("closeDelaySeconds"));
let closeTimer = null;
let noFocusCloseTimer = null;

targetOutput.textContent = target || "未配置 URL Scheme。";
launchButton.disabled = !target;

launchButton.addEventListener("click", () => launch());
closeButton.addEventListener("click", () => closeLauncher());
window.addEventListener("blur", scheduleCloseAfterBlur);
window.addEventListener("focus", () => {
    cancelNoFocusClose();
    cancelScheduledClose();
});
document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
        scheduleCloseAfterBlur();
    } else {
        cancelNoFocusClose();
        cancelScheduledClose();
    }
});

if (target) {
    setTimeout(launch, 120);
    scheduleNoFocusClose();
} else {
    setFeedback("未配置 URL Scheme。", "error");
}

function launch() {
    if (!target) {
        return;
    }
    setFeedback(`已请求系统打开 URL Scheme；页面 ${closeDelayMs / 1000} 秒内未获得焦点会自动关闭。`, "success");
    window.location.href = target;
}

function setFeedback(message, type) {
    feedback.textContent = message;
    feedback.className = `feedback ${type || ""}`.trim();
}

function scheduleCloseAfterBlur() {
    if (closeTimer) {
        return;
    }
    closeTimer = setTimeout(() => {
        closeTimer = null;
        closeLauncher();
    }, closeDelayMs);
}

function cancelScheduledClose() {
    if (!closeTimer) {
        return;
    }
    clearTimeout(closeTimer);
    closeTimer = null;
}

function scheduleNoFocusClose() {
    if (document.hasFocus() || noFocusCloseTimer) {
        return;
    }
    noFocusCloseTimer = setTimeout(() => {
        noFocusCloseTimer = null;
        closeLauncher();
    }, closeDelayMs);
}

function cancelNoFocusClose() {
    if (!noFocusCloseTimer) {
        return;
    }
    clearTimeout(noFocusCloseTimer);
    noFocusCloseTimer = null;
}

async function closeLauncher() {
    try {
        const tab = await chrome.tabs.getCurrent();
        if (tab?.id) {
            await chrome.tabs.remove(tab.id);
            return;
        }
    } catch {
        // Fall back to window.close below.
    }
    window.close();
}

function normalizeCloseDelayMs(value) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) {
        return 10000;
    }
    return Math.min(120, Math.max(1, Math.round(numeric))) * 1000;
}
