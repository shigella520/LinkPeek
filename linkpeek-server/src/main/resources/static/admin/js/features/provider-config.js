// Content provider configuration and the purge-all action.
import {fetchJson} from "../core/api.js";
import {confirmDangerAction, resetDangerAction, setFeedback} from "../core/ui.js";

export function bindPurge() {
    document.getElementById("purge-button").addEventListener("click", async () => {
        const button = document.getElementById("purge-button");
        if (!confirmDangerAction(button, "确认清理")) {
            return;
        }
        button.disabled = true;
        setFeedback("purge-feedback", "正在清理...", "");
        try {
            const result = await fetchJson("/api/admin/stats/purge-all", {method: "POST"});
            setFeedback("purge-feedback", `已清理 ${result.deletedEvents || 0} 条事件，${result.deletedLinks || 0} 条链接。`, "is-success");
        } catch (error) {
            setFeedback("purge-feedback", error.message, "is-error");
        } finally {
            resetDangerAction(button);
            button.disabled = false;
        }
    });
}


export function bindProviderForm() {
    document.getElementById("provider-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const valuesByProvider = {};
        document.querySelectorAll("[data-provider][data-key]").forEach((input) => {
            valuesByProvider[input.dataset.provider] ||= {};
            valuesByProvider[input.dataset.provider][input.dataset.key] = providerInputValue(input);
        });
        setFeedback("provider-feedback", "正在保存 Provider 配置...", "");
        try {
            await Promise.all(Object.entries(valuesByProvider).map(([providerId, values]) => {
                return fetchJson(`/api/admin/provider-config/${encodeURIComponent(providerId)}`, {
                    method: "PUT",
                    body: JSON.stringify({values})
                });
            }));
            await loadProviderConfig();
            setFeedback("provider-feedback", "Provider 配置已保存。", "is-success");
        } catch (error) {
            setFeedback("provider-feedback", error.message, "is-error");
        }
    });
}

export async function loadProviderConfig() {
    const payload = await fetchJson("/api/admin/provider-config");
    const configs = payload.configs || {};
    document.querySelectorAll("[data-provider][data-key]").forEach((input) => {
        const value = (configs[input.dataset.provider] || {})[input.dataset.key];
        if (input.type === "checkbox") {
            input.checked = providerCheckboxChecked(value);
            return;
        }
        input.value = value || "";
    });
}

function providerInputValue(input) {
    if (input.type === "checkbox") {
        return input.checked ? "true" : "false";
    }
    return input.value.trim();
}

function providerCheckboxChecked(value) {
    if (value == null || value === "") {
        return true;
    }
    return !["false", "0", "off", "no"].includes(String(value).trim().toLowerCase());
}
