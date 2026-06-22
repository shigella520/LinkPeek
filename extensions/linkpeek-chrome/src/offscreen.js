chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message?.type !== "copy-to-clipboard") {
        return false;
    }

    copyToClipboard(String(message.text || ""))
        .then(() => sendResponse({ok: true}))
        .catch((error) => sendResponse({ok: false, error: error.message || String(error)}));
    return true;
});

async function copyToClipboard(text) {
    if (copyWithExecCommand(text)) {
        return;
    }
    if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
        return;
    }
    throw new Error("复制到剪贴板失败。");
}

function copyWithExecCommand(text) {
    const textarea = document.createElement("textarea");
    textarea.value = text;
    textarea.setAttribute("readonly", "");
    textarea.style.position = "fixed";
    textarea.style.top = "-1000px";
    textarea.style.left = "-1000px";
    textarea.style.opacity = "0";
    document.body.appendChild(textarea);

    textarea.focus();
    textarea.select();
    const copied = document.execCommand("copy");
    textarea.remove();
    return copied;
}
