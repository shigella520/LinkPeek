import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import {test} from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../../main/resources/static/admin/app.js', import.meta.url), 'utf8');
function functions(names, context) {
    const code = names.map(name => {
        const start = source.search(new RegExp(`    (?:async )?function ${name}\\(`));
        assert.ok(start >= 0, name);
        const next = source.slice(start + 1).search(/\n    (?:async )?function /);
        return source.slice(start, start + 1 + next);
    }).join('\n');
    runInNewContext(code, context);
}

test('report actions prioritize viewing and keep destructive operations inside More', () => {
    const context = {escapeAttribute: String};
    functions(['renderShareSummaryRunActions','shareSummaryOgShareUrl','shareSummaryBusy','renderRegenerationButton','renderShareSummaryImageActionButton','renderShareSummaryAudioActionButton'],context);
    const html = context.renderShareSummaryRunActions({id:1,status:'SUCCESS',regenerationStatus:'ASSETS'});
    assert.ok(html.indexOf('>查看</button>') < html.indexOf('<summary>分享</summary>'));
    const more = html.split('<summary>更多</summary>')[1];
    assert.match(more, /data-regenerate-share-summary="1"[^>]*disabled/);
    assert.match(more, /data-delete-share-run="1"[^>]*disabled/);
    assert.match(html, /复制分享链接/);
});

test('delivery labels distinguish event failure from successful delivery and flag old pending', () => {
    const context = {escapeAttribute:String,escapeHtml:String};
    functions(['renderNotificationStatus','formatDuration'],context);
    assert.match(context.renderNotificationStatus('SUCCESS'), /已送达/);
    assert.match(context.renderNotificationStatus('EXPIRED_PENDING'), /过期待处理/);
    assert.equal(context.formatDuration(39361),'39.4 秒');
    assert.equal(context.formatDuration(280745),'4 分 40 秒');
});

test('a stale report-list response cannot overwrite a newer filter result', async () => {
    const requests = [];
    const values = {'share-summary-run-size':'20','share-summary-run-filter-task':'','share-summary-run-filter-status':'','share-summary-run-filter-trigger':''};
    const context = {URLSearchParams, state:{requests:{},shareSummaryRuns:{page:1}}, document:{getElementById:id=>({value:values[id]})},
        fetchJson:()=>new Promise(resolve=>requests.push(resolve)),setFeedback(){},scheduleShareSummaryRefresh(){},renderShareSummaryRuns(){}};
    functions(['loadShareSummaryRuns'],context);
    const older=context.loadShareSummaryRuns();
    values['share-summary-run-filter-status']='FAILED';
    const newer=context.loadShareSummaryRuns();
    requests[1]({items:[{id:2,status:'FAILED'}],total:1});await newer;
    requests[0]({items:[{id:1,status:'SUCCESS'}],total:1});await older;
    assert.equal(context.state.shareSummaryRuns.items[0].id,2);
});

test('log refresh preserves the reading position unless follow-latest is enabled', () => {
    const output={textContent:'old',scrollTop:25,scrollHeight:500};
    const nodes={'log-output':output,'log-meta':{},'log-follow-latest':{checked:false}};
    const context={document:{getElementById:id=>nodes[id]},formatBytes:String,formatTimestamp:String,setFeedback(){}};
    functions(['renderLogs'],context);
    context.renderLogs({exists:true,lines:['new']});
    assert.equal(output.scrollTop,25);
    nodes['log-follow-latest'].checked=true;
    context.renderLogs({exists:true,lines:['newer']});
    assert.equal(output.scrollTop,500);
});

test('report progress refresh restores playback only for the same audio resource', () => {
    let onMetadata, played = 0;
    const audio = {getAttribute:()=>'/audio/original.mp3',readyState:0,duration:90,currentTime:0,playbackRate:1,
        addEventListener:(_name,callback)=>{onMetadata=callback;},play:()=>{played++;return Promise.resolve();}};
    const context={}; functions(['restoreReportPlayback'],context);
    context.restoreReportPlayback(audio,{src:'/audio/original.mp3',time:33,rate:1.5,paused:false});
    assert.equal(audio.currentTime,0); onMetadata();
    assert.equal(audio.currentTime,33); assert.equal(audio.playbackRate,1.5); assert.equal(played,1);
    context.restoreReportPlayback(audio,{src:'/audio/new-version.mp3',time:80,rate:2,paused:false});
    assert.equal(audio.currentTime,33); assert.equal(played,1);
});

test('modal focus excludes closed-details descendants even when they have layout rectangles', () => {
    const context={}; functions(['isVisibleModalControl'],context);
    const modal={};
    const summary={disabled:false,getClientRects:()=>[{}],contains:node=>node===summary};
    const details={tagName:'DETAILS',open:false,parentElement:modal,querySelector:()=>summary};
    summary.parentElement=details;
    const hiddenLink={disabled:false,getClientRects:()=>[{}],parentElement:details};
    assert.equal(context.isVisibleModalControl(summary,modal),true);
    assert.equal(context.isVisibleModalControl(hiddenLink,modal),false);
    details.open=true;
    assert.equal(context.isVisibleModalControl(hiddenLink,modal),true);
    hiddenLink.disabled=true;
    assert.equal(context.isVisibleModalControl(hiddenLink,modal),false);
});
