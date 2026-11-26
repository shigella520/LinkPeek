// Pure formatting and escaping helpers (no DOM access).

export function groupBy(items, keyFn) {
    return items.reduce((groups, item) => {
        const key = keyFn(item);
        groups[key] ||= [];
        groups[key].push(item);
        return groups;
    }, {});
}

export function trimTrailingPunctuation(value) {
    return String(value ?? "").replace(/[。.!！?？,，;；:：]+$/u, "");
}

export function formatTimestamp(value) {
    if (!value) {
        return "n/a";
    }
    return new Date(value).toLocaleString();
}

export function formatShortTimestamp(value) {
    if (!value) {
        return "n/a";
    }
    const date = new Date(value);
    const datePart = date.toLocaleDateString();
    const timePart = date.toLocaleTimeString([], {hour: "2-digit", minute: "2-digit"});
    return `${datePart} ${timePart}`;
}

export function formatBytes(value) {
    const bytes = Number(value || 0);
    if (bytes < 1024) {
        return `${bytes} B`;
    }
    if (bytes < 1024 * 1024) {
        return `${(bytes / 1024).toFixed(1)} KB`;
    }
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

// Token counts are null when the provider did not report usage.
export function formatTokenUsage(inputTokens, outputTokens) {
    const known = value => value !== null && value !== undefined && Number.isFinite(Number(value));
    if (!known(inputTokens) && !known(outputTokens)) return "未返回用量";
    const count = value => known(value) ? Number(value).toLocaleString("zh-CN") : "?";
    return `输入 ${count(inputTokens)} · 输出 ${count(outputTokens)}`;
}

export function formatDuration(value) {
    const duration = Number(value || 0);
    if (!Number.isFinite(duration) || duration <= 0) {
        return "-";
    }
    if (duration < 1000) return `${Math.round(duration)} 毫秒`;
    if (duration < 60000) return `${(duration / 1000).toFixed(1)} 秒`;
    return `${Math.floor(duration / 60000)} 分 ${Math.floor((duration % 60000) / 1000)} 秒`;
}

export function formatWindow(start, end) {
    return `${formatTimestamp(start)} ~ ${formatTimestamp(end)}`;
}

export function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

export function escapeAttribute(value) {
    return escapeHtml(value);
}
