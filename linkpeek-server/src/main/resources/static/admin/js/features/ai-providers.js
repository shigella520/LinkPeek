// AI providers: list, ordering, enable/test, edit form and auto-downgrade settings.
import {fetchJson} from "../core/api.js";
import {escapeHtml, trimTrailingPunctuation} from "../core/format.js";
import {state} from "../core/state.js";
import {closeModal, confirmDangerAction, openModal, resetDangerAction, setFeedback} from "../core/ui.js";

export function bindAiProviderDowngradeConfig() {
    const form = document.getElementById("ai-downgrade-config-form");
    const enabledToggle = document.getElementById("ai-auto-downgrade-enabled-toggle");
    const thresholdInput = document.getElementById("ai-auto-downgrade-failure-threshold");
    const shareSummaryTimeoutMultiplierInput = document.getElementById("ai-share-summary-timeout-multiplier");
    const titleTimeoutInput = document.getElementById("ai-title-total-timeout-seconds");
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
    titleTimeoutInput.addEventListener("input", scheduleAiProviderDowngradeSave);
}

export function bindAiForm() {
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

export async function loadAiProviders() {
    state.aiProviders = await fetchJson("/api/admin/ai-providers");
    renderAiProviders();
}

export async function loadAiProviderDowngradeConfig() {
    state.aiProviderDowngradeConfig = await fetchJson("/api/admin/ai-provider-downgrade-config");
    renderAiProviderDowngradeConfig();
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
        setFeedback("ai-downgrade-config-feedback", "失败阈值降级次数必须是 1-100 的整数，分享总结超时倍率必须是 1-20，AI 标题等待时间必须是 0-180 的整数秒。", "is-error");
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
    const titleTimeoutValue = document.getElementById("ai-title-total-timeout-seconds").value;
    const aiTitleTotalTimeoutSeconds = titleTimeoutValue === "" ? NaN : Number(titleTimeoutValue);
    if (!Number.isInteger(aiTitleTotalTimeoutSeconds) || aiTitleTotalTimeoutSeconds < 0 || aiTitleTotalTimeoutSeconds > 180) {
        return null;
    }
    if (!Number.isInteger(autoDowngradeFailureThreshold) || autoDowngradeFailureThreshold < 1 || autoDowngradeFailureThreshold > 100) {
        return null;
    }
    if (!Number.isFinite(shareSummaryTimeoutMultiplier) || shareSummaryTimeoutMultiplier < 1 || shareSummaryTimeoutMultiplier > 20) {
        return null;
    }
    return {autoDowngradeEnabled, autoDowngradeFailureThreshold, shareSummaryTimeoutMultiplier, aiTitleTotalTimeoutSeconds};
}

async function saveAiProviderDowngradeConfig(payload, saveVersion) {
    setFeedback("ai-downgrade-config-feedback", "正在保存 AI Provider 配置...", "");
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
        setFeedback("ai-downgrade-config-feedback", "AI Provider 配置已保存。", "is-success");
    } catch (error) {
        if (saveVersion !== state.aiProviderDowngradeSaveVersion) {
            return;
        }
        setFeedback("ai-downgrade-config-feedback", error.message, "is-error");
    }
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
    // 0 is a valid value (unlimited), so only fall back to the default when the field is missing.
    document.getElementById("ai-title-total-timeout-seconds").value = config.aiTitleTotalTimeoutSeconds ?? config.defaultAiTitleTotalTimeoutSeconds ?? 60;
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

export function closeAiModal(clearFeedback = true) {
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
