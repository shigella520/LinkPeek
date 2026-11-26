// Title style prompts and the title format prompt.
import {fetchJson} from "../core/api.js";
import {escapeHtml} from "../core/format.js";
import {FREESTYLE_STYLE, state} from "../core/state.js";
import {closeModal, confirmDangerAction, openModal, resetDangerAction, setFeedback} from "../core/ui.js";

export function bindPromptForm() {
    document.getElementById("prompt-new-button").addEventListener("click", openPromptModalForCreate);
    document.getElementById("prompt-cancel-button").addEventListener("click", closePromptModal);
    document.getElementById("prompt-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const style = document.getElementById("prompt-style").value.trim();
        const prompt = document.getElementById("prompt-text").value.trim();
        const normalizedStyle = style.toUpperCase();
        if (normalizedStyle === FREESTYLE_STYLE) {
            setFeedback("prompt-modal-feedback", "FREESTYLE 是系统保留模式，不能作为 Style Key。", "is-error");
            document.getElementById("prompt-style").focus();
            return;
        }
        setFeedback("prompt-modal-feedback", "正在保存 Style Prompt...", "");
        try {
            await fetchJson(`/api/admin/prompts/${encodeURIComponent(normalizedStyle)}`, {
                method: "PUT",
                body: JSON.stringify({prompt})
            });
            closePromptModal(false);
            await loadPrompts();
            setFeedback("prompt-feedback", "Style Prompt 已保存。", "is-success");
        } catch (error) {
            setFeedback("prompt-modal-feedback", error.message, "is-error");
        }
    });
}

export function bindAiTitleConfig() {
    document.getElementById("ai-title-config-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const titleFormatPrompt = document.getElementById("ai-title-format-prompt").value.trim();
        setFeedback("ai-title-config-feedback", "正在保存标题格式...", "");
        try {
            await fetchJson("/api/admin/ai-title-config", {
                method: "PUT",
                body: JSON.stringify({titleFormatPrompt})
            });
            await loadAiTitleConfig();
            setFeedback("ai-title-config-feedback", "标题格式已保存。", "is-success");
        } catch (error) {
            setFeedback("ai-title-config-feedback", error.message, "is-error");
        }
    });
    document.getElementById("ai-title-format-default-button").addEventListener("click", () => {
        document.getElementById("ai-title-format-prompt").value = state.defaultTitleFormatPrompt || "";
        setFeedback("ai-title-config-feedback", "已填入默认标题格式，保存后生效。", "");
    });
}

export async function loadPrompts() {
    state.prompts = await fetchJson("/api/admin/prompts");
    renderPrompts();
}

export async function loadAiTitleConfig() {
    const payload = await fetchJson("/api/admin/ai-title-config");
    state.defaultTitleFormatPrompt = payload.defaultTitleFormatPrompt || "";
    document.getElementById("ai-title-format-prompt").value = payload.titleFormatPrompt || "";
}

function renderPrompts() {
    const body = document.getElementById("prompt-table");
    if (!state.prompts.length) {
        body.innerHTML = `<tr><td colspan="3" class="muted">暂无 Style Prompt</td></tr>`;
        return;
    }
    body.innerHTML = state.prompts.map((prompt) => `
        <tr>
            <td>${escapeHtml(prompt.style)}</td>
            <td><div class="prompt-preview">${escapeHtml(promptPreview(prompt.prompt))}</div></td>
            <td>
                <div class="row-actions">
                    <button type="button" data-edit-prompt="${escapeHtml(prompt.style)}" class="secondary">编辑</button>
                    <button type="button" data-delete-prompt="${escapeHtml(prompt.style)}" class="danger">删除</button>
                </div>
            </td>
        </tr>
    `).join("");
    body.querySelectorAll("[data-edit-prompt]").forEach((button) => {
        button.addEventListener("click", () => {
            const prompt = state.prompts.find((item) => item.style === button.dataset.editPrompt);
            if (prompt) {
                openPromptModalForEdit(prompt);
            }
        });
    });
    body.querySelectorAll("[data-delete-prompt]").forEach((button) => {
        button.addEventListener("click", async () => {
            if (!confirmDangerAction(button, "确认删除")) {
                return;
            }
            button.disabled = true;
            setFeedback("prompt-feedback", "正在删除 Style Prompt...", "");
            try {
                await fetchJson(`/api/admin/prompts/${encodeURIComponent(button.dataset.deletePrompt)}`, {method: "DELETE"});
                await loadPrompts();
                setFeedback("prompt-feedback", "Style Prompt 已删除。", "is-success");
            } catch (error) {
                resetDangerAction(button);
                button.disabled = false;
                setFeedback("prompt-feedback", error.message, "is-error");
            }
        });
    });
}

function openPromptModalForCreate() {
    resetPromptForm();
    document.getElementById("prompt-modal-title").textContent = "新建 Style Prompt";
    document.getElementById("prompt-style").disabled = false;
    openModal("prompt-modal");
    setFeedback("prompt-modal-feedback", "", "");
    document.getElementById("prompt-style").focus();
}

function openPromptModalForEdit(prompt) {
    resetPromptForm();
    document.getElementById("prompt-modal-title").textContent = `编辑 Style Prompt：${prompt.style}`;
    document.getElementById("prompt-style").value = prompt.style;
    document.getElementById("prompt-style").disabled = true;
    document.getElementById("prompt-text").value = prompt.prompt;
    openModal("prompt-modal");
    setFeedback("prompt-modal-feedback", "", "");
    document.getElementById("prompt-text").focus();
}

export function closePromptModal(clearFeedback = true) {
    resetPromptForm();
    closeModal("prompt-modal");
    if (clearFeedback) {
        setFeedback("prompt-modal-feedback", "", "");
    }
}

function resetPromptForm() {
    document.getElementById("prompt-form").reset();
    document.getElementById("prompt-style").disabled = false;
}

function promptPreview(value) {
    const text = String(value ?? "").replace(/\s+/g, " ").trim();
    if (text.length <= 120) {
        return text;
    }
    return `${text.slice(0, 120)}...`;
}
