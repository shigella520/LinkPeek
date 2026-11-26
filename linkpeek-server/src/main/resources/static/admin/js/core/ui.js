// Small DOM helpers: feedback text, modals, two-step danger buttons, checkbox groups.
import {CONFIRM_TIMEOUT_MS, state} from "./state.js";

export function isVisibleModalControl(node, modal) {
    if (node.disabled || !node.getClientRects().length) return false;
    for (let parent = node.parentElement; parent && parent !== modal; parent = parent.parentElement) {
        if (parent.tagName === "DETAILS" && !parent.open
                && !parent.querySelector(":scope > summary")?.contains(node)) return false;
    }
    return true;
}

export function insertTextAtCursor(textarea, token) {
    const start = textarea.selectionStart ?? textarea.value.length;
    const end = textarea.selectionEnd ?? textarea.value.length;
    textarea.value = `${textarea.value.slice(0, start)}${token}${textarea.value.slice(end)}`;
    textarea.focus();
    textarea.selectionStart = textarea.selectionEnd = start + token.length;
}

export function checkedValues(selector) {
    return Array.from(document.querySelectorAll(selector))
            .map((input) => String(input.value || "").trim())
            .filter(Boolean);
}

export function checkedNumberValues(selector) {
    return checkedValues(selector)
            .map((value) => Number(value))
            .filter((value) => Number.isInteger(value) && value > 0);
}

export function setCheckedValues(selector, values) {
    const valueSet = new Set((values || []).map((value) => String(value).toUpperCase()));
    document.querySelectorAll(selector).forEach((input) => {
        input.checked = valueSet.has(String(input.value).toUpperCase());
    });
}

export function openModal(id) {
    const modal = document.getElementById(id);
    if (modal.hidden) state.modalOpeners.set(id, document.activeElement);
    modal.hidden = false;
    modal.querySelector("button, input, select, textarea, [href]")?.focus();
    document.body.classList.add("modal-open");
}

export function closeModal(id) {
    document.getElementById(id).hidden = true;
    const opener = state.modalOpeners.get(id);
    if (opener?.isConnected) opener.focus();
    state.modalOpeners.delete(id);
    if (document.querySelectorAll(".modal-shell:not([hidden])").length === 0) {
        document.body.classList.remove("modal-open");
    }
}

export function setFeedback(id, message, className) {
    const node = document.getElementById(id);
    if (!node) {
        return;
    }
    node.textContent = message || "";
    node.className = `feedback ${className || ""}`.trim();
}

export function confirmDangerAction(button, confirmLabel) {
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

export function resetDangerAction(button) {
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
