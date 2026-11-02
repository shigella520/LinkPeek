import {readFileSync} from "node:fs";
import {runInNewContext} from "node:vm";
import {test} from "node:test";
import assert from "node:assert/strict";

const source = readFileSync(new URL("../../main/resources/static/admin/app.js", import.meta.url), "utf8");
function implementation(name) {
    const start = source.search(new RegExp(`    (?:async )?function ${name}\\(`));
    assert.ok(start >= 0, name);
    const rest = source.slice(start + 1);
    const end = rest.search(/\n    (?:async )?function /);
    return source.slice(start, start + 1 + end);
}

test("busy summaries block both regeneration and individual asset actions", () => {
    const context = {escapeAttribute: String};
    runInNewContext(["shareSummaryBusy", "renderRegenerationButton", "renderShareSummaryImageActionButton", "renderShareSummaryAudioActionButton"].map(implementation).join("\n"), context);
    for (const regenerationStatus of ["QUEUED", "TEXT", "ASSETS"]) {
        const run = {id: 1, status: "SUCCESS", regenerationStatus};
        assert.match(context.renderRegenerationButton(run), /disabled/);
        assert.match(context.renderShareSummaryImageActionButton(run), /disabled/);
        assert.match(context.renderShareSummaryAudioActionButton(run), /disabled/);
    }
    for (const regenerationStatus of ["FAILED", "PARTIAL_FAILED", "SUCCESS"]) {
        assert.doesNotMatch(context.renderRegenerationButton({id: 1, status: "SUCCESS", regenerationStatus}), /disabled/);
    }
});

test("double-click submits one operation, sends an idempotency key, and releases the UI guard", async () => {
    let complete;
    const pending = new Promise(resolve => { complete = resolve; });
    const requests = [];
    const context = {
        state: {shareSummarySubmitting: false}, document: {querySelectorAll: () => []},
        crypto: {randomUUID: () => "unique-request"}, encodeURIComponent,
        fetchJson: (url, options) => { requests.push({url, options}); return pending; },
        setFeedback() {}, async loadShareSummaryRuns() {}, async openShareSummaryRunDetail() {}
    };
    runInNewContext(implementation("regenerateShareSummary"), context);
    const first = context.regenerateShareSummary(1);
    await context.regenerateShareSummary(1);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].options.headers["Idempotency-Key"], "unique-request");
    complete({}); await first;
    assert.equal(context.state.shareSummarySubmitting, false);
    await context.regenerateShareSummary(1, 2);
    assert.equal(requests[1].url, "/api/admin/share-summary/runs/1/versions/2/retry");
});
