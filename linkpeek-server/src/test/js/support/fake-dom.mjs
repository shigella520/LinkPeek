// Minimal browser globals for importing the admin ES modules under node:test.
// Elements are created on first lookup, so code under test can render into any id;
// tests seed only the properties they care about.

export function fakeElement(props = {}) {
    const classes = new Set();
    const attributes = new Map();
    return Object.assign({
        value: "", textContent: "", innerHTML: "", className: "", hidden: false, disabled: false, checked: false,
        dataset: {}, style: {}, children: [],
        classList: {
            add: (...names) => names.forEach(name => classes.add(name)),
            remove: (...names) => names.forEach(name => classes.delete(name)),
            toggle: (name, force) => ((force ?? !classes.has(name)) ? classes.add(name) : classes.delete(name), classes.has(name)),
            contains: name => classes.has(name)
        },
        setAttribute: (name, value) => attributes.set(name, String(value)),
        getAttribute: name => attributes.get(name) ?? null,
        removeAttribute: name => attributes.delete(name),
        addEventListener() {}, removeEventListener() {}, focus() {}, scrollIntoView() {},
        querySelector: () => null, querySelectorAll: () => [], closest: () => null, contains: () => false,
        getClientRects: () => [], getBoundingClientRect: () => ({top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0})
    }, props);
}

export function installFakeDom(seed = {}) {
    const elements = new Map(Object.entries(seed).map(([id, props]) => [id, fakeElement(props)]));
    const document = {
        body: fakeElement(),
        activeElement: null,
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, fakeElement());
            return elements.get(id);
        },
        querySelector: () => null,
        querySelectorAll: () => [],
        addEventListener() {}, removeEventListener() {}
    };
    globalThis.document = document;
    globalThis.window = globalThis;
    globalThis.location ??= new URL("https://linkpeek.test/admin");
    globalThis.history ??= {replaceState() {}};
    return {document, element: id => document.getElementById(id)};
}

// Replaces fetch with a router: handler(url, options) returns a value (sent as JSON 200),
// a {status, body} object, or a promise of either.
export function installFakeFetch(handler) {
    const calls = [];
    globalThis.fetch = async (url, options = {}) => {
        calls.push({url: String(url), options});
        const result = await handler(String(url), options);
        const {status = 200, body = result} = result && typeof result === "object" && "status" in result ? result : {};
        return {status, ok: status >= 200 && status < 300, json: async () => body};
    };
    return calls;
}
