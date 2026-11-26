import {test} from "node:test";
import assert from "node:assert/strict";
import {installFakeDom, installFakeFetch} from "./support/fake-dom.mjs";
import {state} from "../../main/resources/static/admin/js/core/state.js";
import {
    regenerateShareSummary,
    renderRegenerationButton,
    renderShareSummaryAudioActionButton,
    renderShareSummaryImageActionButton
} from "../../main/resources/static/admin/js/features/share-summary/runs.js";

test("busy summaries block both regeneration and individual asset actions", () => {
    for (const regenerationStatus of ["QUEUED", "TEXT", "ASSETS"]) {
        const run = {id: 1, status: "SUCCESS", regenerationStatus};
        assert.match(renderRegenerationButton(run), /disabled/);
        assert.match(renderShareSummaryImageActionButton(run), /disabled/);
        assert.match(renderShareSummaryAudioActionButton(run), /disabled/);
    }
    for (const regenerationStatus of ["FAILED", "PARTIAL_FAILED", "SUCCESS"]) {
        assert.doesNotMatch(renderRegenerationButton({id: 1, status: "SUCCESS", regenerationStatus}), /disabled/);
    }
});

test("double-click submits one operation, sends an idempotency key, and releases the UI guard", async () => {
    installFakeDom({"share-summary-run-size": {value: "20"}});
    let complete;
    const admission = new Promise(resolve => { complete = resolve; });
    const calls = installFakeFetch((url, options) => {
        if (options.method === "POST") return admission;
        if (url.startsWith("/api/admin/share-summary/runs?")) return {items: [], total: 0, page: 1, totalPages: 0};
        return {status: 404, body: {message: "not found in test"}};
    });
    state.requests = {};
    state.shareSummaryRuns = {page: 1, items: []};
    state.shareSummarySubmitting = false;
    state.activeShareSummaryRunId = null;
    const posts = () => calls.filter(call => call.options.method === "POST");

    const first = regenerateShareSummary(1);
    await regenerateShareSummary(1);
    assert.equal(posts().length, 1);
    const key = posts()[0].options.headers["Idempotency-Key"];
    assert.match(key, /^[0-9a-f-]{36}$/);

    complete({}); await first;
    assert.equal(state.shareSummarySubmitting, false);
    await regenerateShareSummary(1, 2);
    assert.equal(posts()[1].url, "/api/admin/share-summary/runs/1/versions/2/retry");
    assert.notEqual(posts()[1].options.headers["Idempotency-Key"], key);
});
