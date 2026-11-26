// Session check, logout and revealing the admin UI.
import {fetchJson, redirectToLogin} from "../core/api.js";
import {state} from "../core/state.js";
import {syncLogAutoRefreshForActivePanel} from "../features/logs.js";
import {openShareSummaryRunDetail} from "../features/share-summary/run-detail.js";
import {panelIdFromLocation} from "./navigation.js";
import {loadAdminPanel} from "./panels.js";

export async function checkSession() {
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
        const reportId = new URLSearchParams(location.search).get("report");
        if (activePanelId === "share-summary" && /^\d+$/.test(reportId || "")) await openShareSummaryRunDetail(reportId);
        return;
    }
    redirectToLogin();
}

export function bindLogout() {
    document.getElementById("logout-button").addEventListener("click", async () => {
        await fetchJson("/api/admin/logout", {method: "POST"}, false);
        window.location.href = "/admin/login";
    });
}

function showAdmin() {
    document.getElementById("admin-shell").hidden = false;
    document.getElementById("admin-menu-button").hidden = false;
    document.getElementById("logout-button").hidden = false;
}
