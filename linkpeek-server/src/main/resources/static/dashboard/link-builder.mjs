// The support endpoint only classifies URLs; it never fetches content or calls a model.
// debounceMs delays the support request while the user is still typing; {immediate: true}
// skips the delay (Enter key). The last answer is reused for the same URL, so changing
// only the style never triggers another request.
export function createLinkBuilder({checkSupport, buildPreviewUrl, onChange, debounceMs = 0, wait = defaultWait}) {
    let sequence = 0;
    let pending;
    let known = null;
    let current = {kind: "empty", copyUrl: ""};
    const publish = next => { current = next; onChange(next); };
    const resolved = (source, style, supported) => ({
        kind: supported ? "supported" : "unsupported",
        copyUrl: supported ? buildPreviewUrl(source, style) : source
    });
    return {
        get value() { return current; },
        async update(raw, style, {immediate = false} = {}) {
            const version = ++sequence;
            pending?.abort();
            const source = raw.trim();
            if (!source) { publish({kind: "empty", copyUrl: ""}); return; }
            try {
                if (!["http:", "https:"].includes(new URL(source).protocol)) throw new Error("scheme");
            } catch {
                publish({kind: "invalid", copyUrl: ""}); return;
            }
            if (known?.source === source) {
                publish(resolved(source, style, known.supported)); return;
            }
            publish({kind: "checking", copyUrl: ""});
            if (!immediate && debounceMs > 0) {
                await wait(debounceMs);
                if (version !== sequence) return;
            }
            pending = new AbortController();
            try {
                const supported = await checkSupport(source, pending.signal);
                if (version !== sequence) return;
                if (typeof supported !== "boolean") throw new Error("Invalid support response");
                known = {source, supported};
                publish(resolved(source, style, supported));
            } catch {
                if (version === sequence) publish({kind: "error", copyUrl: source});
            }
        }
    };
}

function defaultWait(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

export async function checkPreviewSupport(source, signal) {
    const response = await fetch(`/api/preview/support?${new URLSearchParams({url: source})}`, {
        signal, headers: {Accept: "application/json"}
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return (await response.json()).supported;
}
