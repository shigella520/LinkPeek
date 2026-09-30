import {readFileSync} from "node:fs";
import {runInNewContext} from "node:vm";
import {test} from "node:test";
import assert from "node:assert/strict";

const source = readFileSync(new URL("../../main/java/io/github/shigella520/linkpeek/server/controller/ShareSummaryPublicController.java", import.meta.url), "utf8");
const start = source.lastIndexOf("(() => {", source.indexOf('const audioRoot ='));
const script = source.slice(start, source.indexOf("})();", start) + 5).replaceAll("%%", "%");
const key = "linkpeek.shareSummary.audioProgress:/audio.mp3";

function setup({saved, duration = 120, blocked = false, src = "/audio.mp3"} = {}) {
    const element = (props = {}) => Object.assign({
        listeners: {}, style: {}, dataset: {}, classList: {toggle() {}},
        addEventListener(name, fn) { (this.listeners[name] ??= []).push(fn); },
        emit(name) { for (const fn of this.listeners[name] || []) fn(); },
        setAttribute(name, value) { this[name] = value; }
    }, props);
    const audio = element({duration, currentTime: 0, paused: true, ended: false});
    const progress = element(), status = element(), time = element(), action = element();
    const nodes = {"[data-audio-element]": audio, "[data-audio-progress]": progress,
        "[data-audio-status]": status, "[data-audio-time]": time, '[data-audio-action="toggle"]': action};
    const root = element({dataset: {audioSrc: src}, querySelector: name => nodes[name]});
    const system = element();
    const document = element({querySelector: name => name === "[data-audio-reader]" ? root : system});
    const window = element({dispatchEvent() {}});
    const storage = new Map(saved === undefined ? [] : [[key, saved]]);
    const localStorage = {
        getItem(k) { if (blocked) throw Error("blocked"); return storage.get(k) ?? null; },
        setItem(k, v) { if (blocked) throw Error("blocked"); storage.set(k, v); },
        removeItem(k) { if (blocked) throw Error("blocked"); storage.delete(k); }
    };
    runInNewContext(script, {document, window, localStorage, CustomEvent: class {}});
    return {audio, progress, status, time, storage, window, document};
}

test("restores only after metadata and lets the user seek and persist", () => {
    const p = setup({saved: "42.5", duration: NaN});
    assert.equal(p.progress.disabled, true);
    p.audio.emit("timeupdate");
    assert.equal(p.storage.get(key), "42.5");
    p.audio.duration = 120;
    p.audio.emit("loadedmetadata");
    assert.equal(p.audio.currentTime, 42.5);
    assert.equal(p.audio.paused, true);
    assert.equal(p.time.textContent, "0:42 / 2:00");
    p.progress.value = "75";
    p.progress.emit("input");
    assert.equal(p.audio.currentTime, 75);
    assert.equal(p.storage.get(key), "75");
    p.audio.emit("durationchange");
    assert.equal(p.audio.currentTime, 75);
});

test("saves on pause, backgrounding and page exit; clears on completion", () => {
    const p = setup();
    for (const event of ["pause", "timeupdate"]) {
        p.audio.currentTime = 23;
        p.audio.emit(event);
        assert.equal(p.storage.get(key), "23");
    }
    p.audio.currentTime = 51;
    p.document.visibilityState = "hidden";
    p.document.emit("visibilitychange");
    assert.equal(p.storage.get(key), "51");
    p.audio.currentTime = 64;
    p.window.emit("pagehide");
    assert.equal(p.storage.get(key), "64");
    p.audio.currentTime = 120;
    p.audio.ended = true;
    p.audio.emit("ended");
    assert.equal(p.storage.has(key), false);
});

test("ignores invalid or completed positions and isolates audio files", () => {
    for (const saved of ["NaN", "-1", "120", "999", "garbage"]) {
        assert.equal(setup({saved}).audio.currentTime, 0);
    }
    assert.equal(setup({saved: "42", src: "/other.mp3"}).audio.currentTime, 0);
});

test("storage failures do not disable seeking", () => {
    const p = setup({blocked: true});
    p.progress.value = "30";
    p.progress.emit("input");
    assert.equal(p.audio.currentTime, 30);
    p.audio.emit("pause");
});
