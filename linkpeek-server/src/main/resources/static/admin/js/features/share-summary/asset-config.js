// Share summary image and audio generation settings.
import {fetchJson} from "../../core/api.js";
import {formatBytes} from "../../core/format.js";
import {MIMO_TTS_DEFAULT_STYLE, MIMO_TTS_DEFAULT_VOICE, MIMO_TTS_PRESET_MODEL, state} from "../../core/state.js";
import {closeModal, openModal, setFeedback} from "../../core/ui.js";

export async function loadShareSummaryImageConfig() {
    state.shareSummaryImageConfig = await fetchJson("/api/admin/share-summary/image-config");
}

export async function loadShareSummaryAudioConfig() {
    state.shareSummaryAudioConfig = await fetchJson("/api/admin/share-summary/audio-config");
}

export async function openShareSummaryImageConfigModal() {
    openModal("share-summary-image-config-modal");
    setFeedback("share-summary-image-config-modal-feedback", "正在读取 AI 生图配置...", "");
    try {
        await loadShareSummaryImageConfig();
        fillShareSummaryImageConfigForm(state.shareSummaryImageConfig || {});
        setFeedback("share-summary-image-config-modal-feedback", "", "");
    } catch (error) {
        setFeedback("share-summary-image-config-modal-feedback", error.message, "is-error");
    }
}

export function closeShareSummaryImageConfigModal(clearFeedback = true) {
    closeModal("share-summary-image-config-modal");
    document.getElementById("share-summary-image-config-form").reset();
    if (clearFeedback) {
        setFeedback("share-summary-image-config-modal-feedback", "", "");
    }
}

function fillShareSummaryImageConfigForm(config) {
    document.getElementById("share-summary-image-enabled").checked = Boolean(config.enabled);
    document.getElementById("share-summary-image-auto-generate").checked = Boolean(config.autoGenerate);
    document.getElementById("share-summary-image-provider-type").value = config.providerType || "OPENAI_COMPATIBLE";
    document.getElementById("share-summary-image-base-url").value = config.baseUrl || "";
    document.getElementById("share-summary-image-endpoint-path").value = config.endpointPath || "/v1/images/generations";
    document.getElementById("share-summary-image-api-key").value = "";
    document.getElementById("share-summary-image-api-key").placeholder = config.apiKeyConfigured ? "已配置，留空表示不修改" : "请输入 API Key";
    document.getElementById("share-summary-image-model").value = config.model || "";
    document.getElementById("share-summary-image-size").value = config.imageSize || "auto";
    document.getElementById("share-summary-image-quality").value = config.quality || "auto";
    document.getElementById("share-summary-image-timeout").value = config.requestTimeoutSeconds || 300;
    document.getElementById("share-summary-image-style-prompt").value = config.stylePrompt || "";
}

export async function saveShareSummaryImageConfig(event) {
    event.preventDefault();
    const payload = {
        enabled: document.getElementById("share-summary-image-enabled").checked,
        autoGenerate: document.getElementById("share-summary-image-auto-generate").checked,
        providerType: document.getElementById("share-summary-image-provider-type").value,
        baseUrl: document.getElementById("share-summary-image-base-url").value.trim(),
        endpointPath: document.getElementById("share-summary-image-endpoint-path").value.trim(),
        apiKey: document.getElementById("share-summary-image-api-key").value.trim(),
        model: document.getElementById("share-summary-image-model").value.trim(),
        imageSize: document.getElementById("share-summary-image-size").value.trim(),
        quality: document.getElementById("share-summary-image-quality").value.trim(),
        outputFormat: "png",
        stylePrompt: document.getElementById("share-summary-image-style-prompt").value.trim(),
        requestTimeoutSeconds: Number(document.getElementById("share-summary-image-timeout").value || 300)
    };
    const error = validateShareSummaryImageConfig(payload);
    if (error) {
        setFeedback("share-summary-image-config-modal-feedback", error, "is-error");
        return;
    }
    setFeedback("share-summary-image-config-modal-feedback", "正在保存 AI 生图配置...", "");
    try {
        state.shareSummaryImageConfig = await fetchJson("/api/admin/share-summary/image-config", {
            method: "PUT",
            body: JSON.stringify(payload)
        });
        fillShareSummaryImageConfigForm(state.shareSummaryImageConfig);
        setFeedback("share-summary-image-config-modal-feedback", "AI 生图配置已保存。", "is-success");
    } catch (saveError) {
        setFeedback("share-summary-image-config-modal-feedback", saveError.message, "is-error");
    }
}

function validateShareSummaryImageConfig(payload) {
    if (payload.enabled || payload.autoGenerate) {
        if (!payload.baseUrl) {
            return "Base URL 不能为空。";
        }
        try {
            const url = new URL(payload.baseUrl);
            if (url.protocol !== "http:" && url.protocol !== "https:") {
                return "Base URL 必须使用 http 或 https。";
            }
        } catch (error) {
            return "Base URL 必须是合法 URL。";
        }
        if (!payload.model) {
            return "Model 不能为空。";
        }
    }
    if (!payload.endpointPath) {
        return "Endpoint Path 不能为空。";
    }
    if (!["auto", "1024x1024", "1536x1024", "1024x1536"].includes((payload.imageSize || "").toLowerCase())) {
        return "Size 必须是 auto、1024x1024、1536x1024 或 1024x1536。";
    }
    if (!Number.isInteger(payload.requestTimeoutSeconds) || payload.requestTimeoutSeconds < 1 || payload.requestTimeoutSeconds > 1800) {
        return "Timeout 必须是 1-1800 秒之间的整数。";
    }
    return "";
}

export async function openShareSummaryAudioConfigModal() {
    openModal("share-summary-audio-config-modal");
    resetShareSummaryAudioTestButton();
    setFeedback("share-summary-audio-config-modal-feedback", "正在读取 TTS 配置...", "");
    try {
        await loadShareSummaryAudioConfig();
        fillShareSummaryAudioConfigForm(state.shareSummaryAudioConfig || {});
        setFeedback("share-summary-audio-config-modal-feedback", "", "");
    } catch (error) {
        setFeedback("share-summary-audio-config-modal-feedback", error.message, "is-error");
    }
}

export function closeShareSummaryAudioConfigModal(clearFeedback = true) {
    closeModal("share-summary-audio-config-modal");
    document.getElementById("share-summary-audio-config-form").reset();
    resetShareSummaryAudioTestButton();
    if (clearFeedback) {
        setFeedback("share-summary-audio-config-modal-feedback", "", "");
    }
}

function fillShareSummaryAudioConfigForm(config) {
    const providerType = config.providerType || "OPENAI_COMPATIBLE";
    document.getElementById("share-summary-audio-enabled").checked = Boolean(config.enabled);
    document.getElementById("share-summary-audio-auto-generate").checked = Boolean(config.autoGenerate);
    document.getElementById("share-summary-audio-provider-type").value = providerType;
    document.getElementById("share-summary-audio-base-url").value = config.baseUrl || (providerType === "MIMO_TTS" ? "https://api.xiaomimimo.com" : "https://tts.wangwangit.com");
    document.getElementById("share-summary-audio-endpoint-path").value = config.endpointPath || (providerType === "MIMO_TTS" ? "/v1/chat/completions" : "/v1/audio/speech");
    document.getElementById("share-summary-audio-api-key").value = "";
    document.getElementById("share-summary-audio-api-key").placeholder = config.apiKeyConfigured ? "已配置，留空表示不修改" : "可选";
    document.getElementById("share-summary-audio-model").value = config.model || (providerType === "MIMO_TTS" ? MIMO_TTS_PRESET_MODEL : "");
    document.getElementById("share-summary-audio-voice").value = config.voice || (providerType === "MIMO_TTS" ? MIMO_TTS_DEFAULT_VOICE : "zh-CN-YunhaoNeural");
    document.getElementById("share-summary-audio-voice-select").value = config.voice || MIMO_TTS_DEFAULT_VOICE;
    document.getElementById("share-summary-audio-speed").value = config.speed || 1.2;
    document.getElementById("share-summary-audio-pitch").value = config.pitch ?? 0;
    document.getElementById("share-summary-audio-style").value = config.style || (providerType === "MIMO_TTS" ? MIMO_TTS_DEFAULT_STYLE : "newscast");
    document.getElementById("share-summary-audio-timeout").value = config.requestTimeoutSeconds || 120;
    updateShareSummaryAudioProviderFields(false);
}

function isMimoTtsAudioProvider() {
    return document.getElementById("share-summary-audio-provider-type").value === "MIMO_TTS";
}

function shareSummaryAudioVoiceValue() {
    return isMimoTtsAudioProvider()
            ? document.getElementById("share-summary-audio-voice-select").value.trim()
            : document.getElementById("share-summary-audio-voice").value.trim();
}

export function updateShareSummaryAudioProviderFields(applyDefaults) {
    const mimo = isMimoTtsAudioProvider();
    document.getElementById("share-summary-audio-voice-text-row").hidden = mimo;
    document.getElementById("share-summary-audio-voice-select-row").hidden = !mimo;
    document.getElementById("share-summary-audio-speed-row").hidden = mimo;
    document.getElementById("share-summary-audio-pitch-row").hidden = mimo;
    document.getElementById("share-summary-audio-style-label").textContent = mimo ? "音频标签" : "Style";
    document.getElementById("share-summary-audio-model").placeholder = mimo ? MIMO_TTS_PRESET_MODEL : "可选，例如 tts-1";
    if (!applyDefaults) {
        return;
    }
    if (mimo) {
        document.getElementById("share-summary-audio-base-url").value = "https://api.xiaomimimo.com";
        document.getElementById("share-summary-audio-endpoint-path").value = "/v1/chat/completions";
        document.getElementById("share-summary-audio-model").value = MIMO_TTS_PRESET_MODEL;
        document.getElementById("share-summary-audio-voice-select").value = MIMO_TTS_DEFAULT_VOICE;
        document.getElementById("share-summary-audio-style").value = MIMO_TTS_DEFAULT_STYLE;
    } else {
        document.getElementById("share-summary-audio-base-url").value = "https://tts.wangwangit.com";
        document.getElementById("share-summary-audio-endpoint-path").value = "/v1/audio/speech";
        document.getElementById("share-summary-audio-model").value = "";
        document.getElementById("share-summary-audio-voice").value = "zh-CN-YunhaoNeural";
        document.getElementById("share-summary-audio-style").value = "newscast";
    }
}

export async function saveShareSummaryAudioConfig(event) {
    event.preventDefault();
    const payload = shareSummaryAudioConfigPayload();
    const error = validateShareSummaryAudioConfig(payload);
    if (error) {
        setFeedback("share-summary-audio-config-modal-feedback", error, "is-error");
        return;
    }
    setFeedback("share-summary-audio-config-modal-feedback", "正在保存 TTS 配置...", "");
    try {
        state.shareSummaryAudioConfig = await fetchJson("/api/admin/share-summary/audio-config", {
            method: "PUT",
            body: JSON.stringify(payload)
        });
        fillShareSummaryAudioConfigForm(state.shareSummaryAudioConfig);
        setFeedback("share-summary-audio-config-modal-feedback", "TTS 配置已保存。", "is-success");
    } catch (saveError) {
        setFeedback("share-summary-audio-config-modal-feedback", saveError.message, "is-error");
    }
}

export async function testShareSummaryAudioConfig() {
    const button = document.getElementById("share-summary-audio-config-test-button");
    const payload = shareSummaryAudioConfigPayload();
    const error = validateShareSummaryAudioConfig(payload, true);
    button.classList.remove("is-success", "is-error");
    button.textContent = "测试 TTS";
    resetShareSummaryAudioTestAudio();
    if (error) {
        setFeedback("share-summary-audio-config-modal-feedback", error, "is-error");
        button.classList.add("is-error");
        return;
    }
    button.disabled = true;
    button.textContent = "测试中";
    setFeedback("share-summary-audio-config-modal-feedback", "正在请求 TTS Provider 生成测试音频...", "");
    try {
        const result = await fetchJson("/api/admin/share-summary/audio-config/test", {
            method: "POST",
            body: JSON.stringify(payload)
        });
        button.classList.toggle("is-success", Boolean(result.success));
        button.classList.toggle("is-error", !result.success);
        button.textContent = "测试 TTS";
        if (result.success) {
            const parts = [];
            if (typeof result.durationMs === "number") {
                parts.push(`耗时 ${result.durationMs}ms`);
            }
            if (typeof result.responseBytes === "number") {
                parts.push(`音频 ${formatBytes(result.responseBytes)}`);
            }
            showShareSummaryAudioTestAudio(result.audioUrl);
            setFeedback("share-summary-audio-config-modal-feedback", `TTS 测试成功${parts.length ? "，" + parts.join("，") : ""}。`, "is-success");
        } else {
            resetShareSummaryAudioTestAudio();
            setFeedback("share-summary-audio-config-modal-feedback", result.message || "TTS 测试失败。", "is-error");
        }
    } catch (testError) {
        button.classList.add("is-error");
        button.textContent = "测试 TTS";
        resetShareSummaryAudioTestAudio();
        setFeedback("share-summary-audio-config-modal-feedback", testError.message, "is-error");
    } finally {
        button.disabled = false;
    }
}

function resetShareSummaryAudioTestButton() {
    const button = document.getElementById("share-summary-audio-config-test-button");
    button.disabled = false;
    button.textContent = "测试 TTS";
    button.classList.remove("is-success", "is-error");
    resetShareSummaryAudioTestAudio();
}

function showShareSummaryAudioTestAudio(audioUrl) {
    const player = document.getElementById("share-summary-audio-test-player");
    const audio = document.getElementById("share-summary-audio-test-audio");
    if (!player || !audio || !audioUrl) {
        resetShareSummaryAudioTestAudio();
        return;
    }
    audio.src = audioUrl;
    audio.load();
    player.hidden = false;
}

function resetShareSummaryAudioTestAudio() {
    const player = document.getElementById("share-summary-audio-test-player");
    const audio = document.getElementById("share-summary-audio-test-audio");
    if (audio) {
        audio.pause();
        audio.removeAttribute("src");
        audio.load();
    }
    if (player) {
        player.hidden = true;
    }
}

function shareSummaryAudioConfigPayload() {
    return {
        enabled: document.getElementById("share-summary-audio-enabled").checked,
        autoGenerate: document.getElementById("share-summary-audio-auto-generate").checked,
        providerType: document.getElementById("share-summary-audio-provider-type").value,
        baseUrl: document.getElementById("share-summary-audio-base-url").value.trim(),
        endpointPath: document.getElementById("share-summary-audio-endpoint-path").value.trim(),
        apiKey: document.getElementById("share-summary-audio-api-key").value.trim(),
        model: document.getElementById("share-summary-audio-model").value.trim(),
        voice: shareSummaryAudioVoiceValue(),
        speed: isMimoTtsAudioProvider() ? 1.2 : Number(document.getElementById("share-summary-audio-speed").value || 1.2),
        pitch: isMimoTtsAudioProvider() ? 0 : Number(document.getElementById("share-summary-audio-pitch").value || 0),
        style: document.getElementById("share-summary-audio-style").value.trim(),
        outputFormat: isMimoTtsAudioProvider() ? "wav" : "mp3",
        requestTimeoutSeconds: Number(document.getElementById("share-summary-audio-timeout").value || 120)
    };
}

function validateShareSummaryAudioConfig(payload, requireProvider = false) {
    if (requireProvider || payload.enabled || payload.autoGenerate) {
        if (!payload.baseUrl) {
            return "Base URL 不能为空。";
        }
        try {
            const url = new URL(payload.baseUrl);
            if (url.protocol !== "http:" && url.protocol !== "https:") {
                return "Base URL 必须使用 http 或 https。";
            }
        } catch (error) {
            return "Base URL 必须是合法 URL。";
        }
        if (!payload.voice) {
            return "Voice 不能为空。";
        }
    }
    if (!payload.endpointPath) {
        return "Endpoint Path 不能为空。";
    }
    if (payload.providerType !== "MIMO_TTS" && (!Number.isFinite(payload.speed) || payload.speed < 0.25 || payload.speed > 4)) {
        return "Speed 必须是 0.25-4 之间的数字。";
    }
    if (payload.providerType !== "MIMO_TTS" && !Number.isInteger(payload.pitch)) {
        return "Pitch 必须是整数。";
    }
    if (!Number.isInteger(payload.requestTimeoutSeconds) || payload.requestTimeoutSeconds < 1 || payload.requestTimeoutSeconds > 1800) {
        return "Timeout 必须是 1-1800 秒之间的整数。";
    }
    return "";
}
