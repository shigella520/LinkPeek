import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createLinkBuilder} from '../../main/resources/static/dashboard/link-builder.mjs';

function setup(checkSupport) {
    const changes = [];
    const builder = createLinkBuilder({checkSupport, buildPreviewUrl: (url, style) => `/preview?${new URLSearchParams({url, style})}`, onChange: value => changes.push(value)});
    return {builder, changes};
}

test('invalid input never calls support; supported and unsupported URLs copy the correct destination', async () => {
    const calls = [];
    const {builder} = setup(async url => { calls.push(url); return url.includes('supported.test'); });
    await builder.update('javascript:alert(1)', 'SUM');
    assert.equal(builder.value.kind, 'invalid'); assert.equal(calls.length, 0);
    await builder.update('https://example.com/a', 'SUM');
    assert.deepEqual(builder.value, {kind:'unsupported', copyUrl:'https://example.com/a'});
    await builder.update('https://supported.test/a?b=1', 'SUM');
    assert.equal(builder.value.kind, 'supported');
    assert.equal(new URLSearchParams(builder.value.copyUrl.split('?')[1]).get('style'), 'SUM');
});

test('older responses and failures cannot overwrite the latest input or reenable its old URL', async () => {
    const pending = [];
    const {builder} = setup((url, signal) => new Promise((resolve,reject) => pending.push({resolve,reject,signal})));
    const first = builder.update('https://first.test', 'SUM');
    const second = builder.update('https://second.test', 'SUM');
    assert.equal(pending[0].signal.aborted, true);
    assert.equal(builder.value.copyUrl, '');
    pending[1].resolve(false); await second;
    pending[0].resolve(true); await first;
    assert.equal(builder.value.copyUrl, 'https://second.test');
    const third = builder.update('https://third.test', 'SUM');
    await builder.update('', 'SUM'); pending[2].reject(new Error('old request')); await third;
    assert.equal(builder.value.kind, 'empty');
});

test('network errors and malformed responses never claim preview support', async () => {
    for (const check of [async()=>{throw new Error('network');}, async()=>undefined]) {
        const {builder} = setup(check);
        await builder.update('https://example.com', 'SUM');
        assert.deepEqual(builder.value, {kind:'error', copyUrl:'https://example.com'});
    }
});
