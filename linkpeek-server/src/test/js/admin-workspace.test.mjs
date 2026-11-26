import {test} from 'node:test';
import assert from 'node:assert/strict';
import {fakeElement, installFakeDom, installFakeFetch} from './support/fake-dom.mjs';
import {state} from '../../main/resources/static/admin/js/core/state.js';
import {formatDuration} from '../../main/resources/static/admin/js/core/format.js';
import {isVisibleModalControl} from '../../main/resources/static/admin/js/core/ui.js';
import {renderLogs} from '../../main/resources/static/admin/js/features/logs.js';
import {renderNotificationStatus} from '../../main/resources/static/admin/js/features/notifications/deliveries.js';
import {restoreReportPlayback} from '../../main/resources/static/admin/js/features/share-summary/run-detail.js';
import {loadShareSummaryRuns, renderShareSummaryRunActions} from '../../main/resources/static/admin/js/features/share-summary/runs.js';

test('report actions prioritize viewing and keep destructive operations inside More', () => {
    const html = renderShareSummaryRunActions({id:1,status:'SUCCESS',regenerationStatus:'ASSETS'});
    assert.ok(html.indexOf('>查看</button>') < html.indexOf('<summary>分享</summary>'));
    const more = html.split('<summary>更多</summary>')[1];
    assert.match(more, /data-regenerate-share-summary="1"[^>]*disabled/);
    assert.match(more, /data-delete-share-run="1"[^>]*disabled/);
    assert.match(html, /复制分享链接/);
});

test('delivery labels distinguish event failure from successful delivery and flag old pending', () => {
    assert.match(renderNotificationStatus('SUCCESS'), /已送达/);
    assert.match(renderNotificationStatus('EXPIRED_PENDING'), /过期待处理/);
    assert.equal(formatDuration(39361),'39.4 秒');
    assert.equal(formatDuration(280745),'4 分 40 秒');
});

test('a stale report-list response cannot overwrite a newer filter result', async () => {
    const {element} = installFakeDom({'share-summary-run-size': {value: '20'}});
    const pending = [];
    installFakeFetch(url => url.startsWith('/api/admin/share-summary/runs?')
            ? new Promise(resolve => pending.push(resolve)) : {status: 404, body: {}});
    state.requests = {};
    state.shareSummaryRuns = {page: 1, items: []};
    const older = loadShareSummaryRuns();
    element('share-summary-run-filter-status').value = 'FAILED';
    const newer = loadShareSummaryRuns();
    await new Promise(setImmediate);
    pending[1]({items:[{id:2,status:'FAILED'}],total:1}); await newer;
    pending[0]({items:[{id:1,status:'SUCCESS'}],total:1}); await older;
    assert.equal(state.shareSummaryRuns.items[0].id, 2);
});

test('log refresh preserves the reading position unless follow-latest is enabled', () => {
    const {element} = installFakeDom({'log-output': {textContent:'old',scrollTop:25,scrollHeight:500}, 'log-follow-latest': {checked:false}});
    renderLogs({exists:true,lines:['new']});
    assert.equal(element('log-output').scrollTop,25);
    element('log-follow-latest').checked = true;
    renderLogs({exists:true,lines:['newer']});
    assert.equal(element('log-output').scrollTop,500);
});

test('report progress refresh restores playback only for the same audio resource', () => {
    let onMetadata, played = 0;
    const audio = {getAttribute:()=>'/audio/original.mp3',readyState:0,duration:90,currentTime:0,playbackRate:1,
        addEventListener:(_name,callback)=>{onMetadata=callback;},play:()=>{played++;return Promise.resolve();}};
    restoreReportPlayback(audio,{src:'/audio/original.mp3',time:33,rate:1.5,paused:false});
    assert.equal(audio.currentTime,0); onMetadata();
    assert.equal(audio.currentTime,33); assert.equal(audio.playbackRate,1.5); assert.equal(played,1);
    restoreReportPlayback(audio,{src:'/audio/new-version.mp3',time:80,rate:2,paused:false});
    assert.equal(audio.currentTime,33); assert.equal(played,1);
});

test('modal focus excludes closed-details descendants even when they have layout rectangles', () => {
    const modal={};
    const summary=fakeElement({getClientRects:()=>[{}]});
    summary.contains=node=>node===summary;
    const details={tagName:'DETAILS',open:false,parentElement:modal,querySelector:()=>summary};
    summary.parentElement=details;
    const hiddenLink=fakeElement({getClientRects:()=>[{}],parentElement:details});
    assert.equal(isVisibleModalControl(summary,modal),true);
    assert.equal(isVisibleModalControl(hiddenLink,modal),false);
    details.open=true;
    assert.equal(isVisibleModalControl(hiddenLink,modal),true);
    hiddenLink.disabled=true;
    assert.equal(isVisibleModalControl(hiddenLink,modal),false);
});
