// Sidebar navigation, panel switching and pinned/hover drawer behaviour.
import {state} from "../core/state.js";
import {syncLogAutoRefreshForActivePanel} from "../features/logs.js";
import {loadAdminPanel} from "./panels.js";

export function bindAdminNavigation() {
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

export function panelIdFromLocation() {
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
