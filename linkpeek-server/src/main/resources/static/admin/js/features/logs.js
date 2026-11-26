// Service log viewer with follow-latest auto refresh.
import {fetchJson} from "../core/api.js";
import {formatBytes, formatTimestamp} from "../core/format.js";
import {state} from "../core/state.js";
import {setFeedback} from "../core/ui.js";
import {panelIdFromLocation} from "../shell/navigation.js";

export function bindLogs() {
    document.getElementById("log-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        await loadLogs();
    });
    document.getElementById("log-auto-refresh").addEventListener("change", updateLogAutoRefresh);
    const output = document.getElementById("log-output");
    const follow = document.getElementById("log-follow-latest");
    output.addEventListener("scroll", () => {
        if (output.scrollHeight - output.scrollTop - output.clientHeight > 24) follow.checked = false;
    });
    follow.addEventListener("change", () => { if (follow.checked) output.scrollTop = output.scrollHeight; });
}

export async function loadLogs() {
    const request = (state.requests.loadLogs || 0) + 1;
    state.requests.loadLogs = request;
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
        if (state.requests.loadLogs !== request) return;
        renderLogs(result);
    } catch (error) {
        if (state.requests.loadLogs !== request) return;
        setFeedback("log-feedback", error.message, "is-error");
    }
}

export function renderLogs(result) {
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
    if (document.getElementById("log-follow-latest").checked) output.scrollTop = output.scrollHeight;
    const truncated = result.truncated ? "，已截断" : "";
    setFeedback("log-feedback", `已加载 ${lines.length} 行${truncated}。`, "is-success");
}

function updateLogAutoRefresh() {
    syncLogAutoRefreshForActivePanel(panelIdFromLocation());
}

export function syncLogAutoRefreshForActivePanel(panelId) {
    if (state.logRefreshTimer) {
        window.clearInterval(state.logRefreshTimer);
        state.logRefreshTimer = null;
    }
    if (state.adminReady && panelId === "service-logs" && document.getElementById("log-auto-refresh").checked) {
        state.logRefreshTimer = window.setInterval(loadLogs, 5000);
    }
}
