// JSON fetch wrapper with unified error messages and login redirect.

export async function fetchJson(url, options = {}, redirectOnUnauthorized = true) {
    const response = await fetch(url, {
        headers: {
            Accept: "application/json",
            "Content-Type": "application/json"
        },
        ...options
    });
    if (response.status === 401 && redirectOnUnauthorized) {
        redirectToLogin();
        throw new Error("登录已失效。");
    }
    if (!response.ok) {
        throw new Error(await errorMessage(response));
    }
    if (response.status === 204) {
        return null;
    }
    return response.json();
}

export async function errorMessage(response) {
    try {
        const payload = await response.json();
        return payload.message || payload.error || `HTTP ${response.status}`;
    } catch (error) {
        return `HTTP ${response.status}`;
    }
}

export function redirectToLogin() {
    const next = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    window.location.replace(`/admin/login?next=${encodeURIComponent(next)}`);
}
