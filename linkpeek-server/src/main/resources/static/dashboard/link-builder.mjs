// The support endpoint only classifies URLs; it never fetches content or calls a model.
export function createLinkBuilder({checkSupport, buildPreviewUrl, onChange}) {
    let sequence = 0;
    let pending;
    let current = {kind: "empty", copyUrl: ""};
    const publish = next => { current = next; onChange(next); };
    return {
        get value() { return current; },
        async update(raw, style) {
            const version = ++sequence;
            pending?.abort();
            const source = raw.trim();
            if (!source) { publish({kind: "empty", copyUrl: ""}); return; }
            try {
                if (!["http:", "https:"].includes(new URL(source).protocol)) throw new Error("scheme");
            } catch {
                publish({kind: "invalid", copyUrl: ""}); return;
            }
            pending = new AbortController();
            publish({kind: "checking", copyUrl: ""});
            try {
                const supported = await checkSupport(source, pending.signal);
                if (version !== sequence) return;
                if (typeof supported !== "boolean") throw new Error("Invalid support response");
                publish({kind: supported ? "supported" : "unsupported", copyUrl: supported ? buildPreviewUrl(source, style) : source});
            } catch {
                if (version === sequence) publish({kind: "error", copyUrl: source});
            }
        }
    };
}

export async function checkPreviewSupport(source, signal) {
    const response = await fetch(`/api/preview/support?${new URLSearchParams({url: source})}`, {
        signal, headers: {Accept: "application/json"}
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return (await response.json()).supported;
}
