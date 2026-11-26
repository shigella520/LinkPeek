(function () {
    function init() {
        bindLogin();
        checkSession();
    }

    async function checkSession() {
        const session = await fetchJson("/api/admin/session", {method: "GET"}, false);
        if (!session || !session.enabled) {
            setFeedback("login-feedback", "当前环境未启用管理后台。", "is-error");
            document.getElementById("login-password").disabled = true;
            document.querySelector("#login-form button[type='submit']").disabled = true;
            return;
        }
        if (session.authenticated) {
            window.location.replace(nextUrl());
        }
    }

    function bindLogin() {
        const form = document.getElementById("login-form");
        const password = document.getElementById("login-password");
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            setFeedback("login-feedback", "正在登录...", "");
            try {
                await fetchJson("/api/admin/login", {
                    method: "POST",
                    body: JSON.stringify({password: password.value})
                });
                password.value = "";
                window.location.replace(nextUrl());
            } catch (error) {
                setFeedback("login-feedback", error.message, "is-error");
            }
        });
    }

    function nextUrl() {
        const next = new URLSearchParams(window.location.search).get("next");
        if (next && next.startsWith("/") && !next.startsWith("//")) {
            return next;
        }
        return "/admin";
    }

    async function fetchJson(url, options = {}, requireOk = true) {
        const response = await fetch(url, {
            headers: {
                Accept: "application/json",
                "Content-Type": "application/json"
            },
            ...options
        });
        if (!response.ok) {
            if (!requireOk) {
                return null;
            }
            throw new Error(await errorMessage(response));
        }
        if (response.status === 204) {
            return null;
        }
        return response.json();
    }

    async function errorMessage(response) {
        if (response.status === 403) {
            return "密码不正确。";
        }
        if (response.status === 429) {
            const seconds = Number(response.headers.get("Retry-After")) || 60;
            return `尝试次数过多，请 ${Math.ceil(seconds / 60)} 分钟后再试。`;
        }
        try {
            const payload = await response.json();
            return payload.message || payload.error || `HTTP ${response.status}`;
        } catch (error) {
            return `HTTP ${response.status}`;
        }
    }

    function setFeedback(id, message, className) {
        const node = document.getElementById(id);
        node.textContent = message || "";
        node.className = `feedback ${className || ""}`.trim();
    }

    init();
})();
