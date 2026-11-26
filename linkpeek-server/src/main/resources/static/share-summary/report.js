// Public share-summary report page (/share-summary/reports/{token}).
// Per-page values are read from the server-rendered DOM (data-* attributes), never interpolated here.
// The page loads this file as a module; importing it outside a browser (tests) does not touch the DOM.

export function initImageViewer() {
    const trigger = document.querySelector("[data-report-image-open]");
    const cover = document.querySelector(".report-cover");
    const viewer = document.querySelector("[data-report-image-viewer]");
    const image = document.querySelector("[data-report-image]");
    const title = document.querySelector("[data-report-image-title]");
    const closeButton = document.querySelector("[data-report-image-close]");

    if (!trigger || !cover || !viewer || !image || !closeButton) {
        return;
    }

    function openViewer() {
        image.src = cover.currentSrc || cover.src;
        image.alt = cover.alt || "分享图";
        title.textContent = cover.alt || "分享图";
        viewer.hidden = false;
        document.body.classList.add("report-image-viewer-open");
        updateViewerLayout();
        closeButton.focus({preventScroll: true});
    }

    function closeViewer() {
        viewer.hidden = true;
        image.removeAttribute("src");
        image.alt = "";
        document.body.classList.remove("report-image-viewer-open");
    }

    function updateViewerLayout() {
        if (viewer.hidden || !window.matchMedia("(max-width: 720px) and (orientation: portrait)").matches) {
            return;
        }
        const stage = viewer.querySelector(".report-image-viewer-stage");
        if (!stage) {
            return;
        }
        const stageRect = stage.getBoundingClientRect();
        if (!stageRect.width || !stageRect.height) {
            return;
        }
        const imageWidth = Math.min(stageRect.height, stageRect.width * 1200 / 630);
        stage.style.setProperty("--report-image-viewer-mobile-width", `${Math.floor(imageWidth)}px`);
    }

    trigger.addEventListener("click", openViewer);
    image.addEventListener("load", updateViewerLayout);
    window.addEventListener("resize", updateViewerLayout);
    closeButton.addEventListener("click", closeViewer);
    viewer.addEventListener("click", (event) => {
        if (event.target === viewer) {
            closeViewer();
        }
    });
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && !viewer.hidden) {
            closeViewer();
        }
    });
}

export function initAudioPlayer() {
    const audioRoot = document.querySelector("[data-audio-reader]");
    const systemRoot = document.querySelector("[data-reader]");
    const audio = audioRoot ? audioRoot.querySelector("[data-audio-element]") : null;

    function enableSystemReader() {
        if (systemRoot) {
            systemRoot.dataset.readerFallback = "true";
            window.dispatchEvent(new CustomEvent("linkpeek:share-summary-system-reader"));
        }
    }

    if (audioRoot && audio && audioRoot.dataset.audioSrc) {
        const action = audioRoot.querySelector('[data-audio-action="toggle"]');
        const status = audioRoot.querySelector("[data-audio-status]");
        const progress = audioRoot.querySelector("[data-audio-progress]");
        const time = audioRoot.querySelector("[data-audio-time]");
        const progressKey = "linkpeek.shareSummary.audioProgress:" + audioRoot.dataset.audioSrc;
        let progressRestored = false;
        let lastSavedAt = 0;
        let playRecordedForCurrentPlayback = false;
        audioRoot.hidden = false;

        function setAudioStatus(message, playing) {
            status.textContent = message;
            action.classList.toggle("is-playing", playing);
            action.setAttribute("aria-label", playing ? "暂停" : "播放");
        }

        function audioDuration() {
            return Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration : 0;
        }

        function formatTime(seconds) {
            const total = Math.max(0, Math.floor(seconds));
            return Math.floor(total / 60) + ":" + String(total % 60).padStart(2, "0");
        }

        function updateAudioProgress() {
            const duration = audioDuration();
            const position = Math.min(duration, Math.max(0, audio.currentTime || 0));
            progress.disabled = !duration;
            progress.max = String(duration);
            progress.value = String(position);
            progress.style.setProperty("--audio-progress", `${duration ? position / duration * 100 : 0}%`);
            const label = formatTime(position) + " / " + formatTime(duration);
            time.textContent = label;
            progress.setAttribute("aria-valuetext", label);
        }

        function saveAudioProgress(force = false) {
            if (!progressRestored || !audioDuration()) {
                return;
            }
            const now = Date.now();
            if (!force && now - lastSavedAt < 1000) {
                return;
            }
            lastSavedAt = now;
            try {
                if (audio.ended || audio.currentTime >= audio.duration) {
                    localStorage.removeItem(progressKey);
                } else {
                    localStorage.setItem(progressKey, String(audio.currentTime));
                }
            } catch (error) {
                // Storage may be unavailable; playback must still work.
            }
        }

        function restoreAudioProgress() {
            if (!progressRestored && audioDuration()) {
                try {
                    const saved = Number(localStorage.getItem(progressKey));
                    if (Number.isFinite(saved) && saved > 0 && saved < audio.duration) {
                        audio.currentTime = saved;
                        setAudioStatus("已恢复上次播放进度", false);
                    }
                } catch (error) {
                    // Ignore unavailable storage or an unseekable resource.
                }
                progressRestored = true;
            }
            updateAudioProgress();
        }

        progress.addEventListener("input", () => {
            if (!audioDuration()) {
                return;
            }
            audio.currentTime = Math.max(0, Math.min(audio.duration, Number(progress.value)));
            updateAudioProgress();
            saveAudioProgress(true);
        });

        function recordAudioPlay() {
            const playUrl = audioRoot.dataset.audioPlayUrl;
            if (playRecordedForCurrentPlayback || !playUrl) {
                return;
            }
            playRecordedForCurrentPlayback = true;
            fetch(playUrl, {
                method: "POST",
                credentials: "same-origin",
                keepalive: true
            }).catch(() => {
                // Playback should not depend on analytics delivery.
            });
        }

        action.addEventListener("click", async () => {
            if (audio.paused) {
                try {
                    await audio.play();
                    recordAudioPlay();
                } catch (error) {
                    audioRoot.hidden = true;
                    enableSystemReader();
                }
            } else {
                audio.pause();
            }
        });
        audio.addEventListener("play", () => setAudioStatus("正在播放", true));
        audio.addEventListener("pause", () => {
            setAudioStatus(audio.ended ? "播放完成" : "已暂停", false);
            saveAudioProgress(true);
        });
        audio.addEventListener("ended", () => {
            updateAudioProgress();
            playRecordedForCurrentPlayback = false;
            saveAudioProgress(true);
            setAudioStatus("播放完成", false);
        });
        audio.addEventListener("timeupdate", () => {
            updateAudioProgress();
            saveAudioProgress();
        });
        audio.addEventListener("seeked", () => saveAudioProgress(true));
        audio.addEventListener("loadedmetadata", restoreAudioProgress);
        audio.addEventListener("durationchange", restoreAudioProgress);
        window.addEventListener("pagehide", () => saveAudioProgress(true));
        document.addEventListener("visibilitychange", () => {
            if (document.visibilityState === "hidden") {
                saveAudioProgress(true);
            }
        });
        restoreAudioProgress();
        audio.addEventListener("error", () => {
            audioRoot.hidden = true;
            enableSystemReader();
        });
        // This deferred module can run after the audio has already failed to load.
        if (audio.error) {
            audioRoot.hidden = true;
            enableSystemReader();
        }
    } else {
        enableSystemReader();
    }
}

export function initSystemReader() {
    const root = document.querySelector("[data-reader]");
    const content = document.querySelector("[data-reader-content]");
    if (!root || !content || !("speechSynthesis" in window) || !("SpeechSynthesisUtterance" in window)) {
        return;
    }

    const hasAudioReader = Boolean(document.querySelector("[data-audio-reader]:not([hidden])"));
    const enableSystemReader = () => root.dataset.readerFallback === "true";
    if (hasAudioReader && !enableSystemReader()) {
        window.addEventListener("linkpeek:share-summary-system-reader", () => {
            root.hidden = false;
        }, {once: true});
    }

    const synth = window.speechSynthesis;
    const actions = {
        toggle: root.querySelector('[data-reader-action="toggle"]')
    };
    const status = root.querySelector("[data-reader-status]");
    const progress = root.querySelector("[data-reader-progress]");
    const rateInput = root.querySelector("[data-reader-rate]");
    const rateLabel = root.querySelector("[data-reader-rate-label]");
    const voiceSelect = root.querySelector("[data-reader-voice]");
    const voiceHint = root.querySelector("[data-reader-voice-hint]");
    const storageKey = "linkpeek.shareSummary.readerVoice";
    let availableVoices = [];
    let chunks = [];
    let chunkIndex = 0;
    let stopRequested = false;
    let pausedByUser = false;

    if (!hasAudioReader || enableSystemReader()) {
        root.hidden = false;
    }

    function defaultRate() {
        const viewportWidth = Math.min(
            window.innerWidth || Number.MAX_SAFE_INTEGER,
            (window.screen && window.screen.width) || Number.MAX_SAFE_INTEGER
        );
        return viewportWidth <= 520 ? 1.1 : 1.4;
    }

    function setRate(value) {
        rateInput.value = Number(value).toFixed(1);
        rateLabel.textContent = `${Number(rateInput.value).toFixed(1)}x`;
    }

    function reportText() {
        return [
            document.querySelector("[data-reader-title]"),
            document.querySelector("[data-reader-description]"),
            content
        ]
            .map((node) => node ? node.innerText.trim() : "")
            .filter(Boolean)
            .join("\n");
    }

    function splitText(value) {
        const normalized = value.replace(/\s+/g, " ").trim();
        if (!normalized) {
            return [];
        }
        const sentences = normalized.match(/[^。！？!?；;]+[。！？!?；;]?/g) || [normalized];
        const result = [];
        let current = "";
        sentences.forEach((sentence) => {
            const text = sentence.trim();
            if (!text) {
                return;
            }
            if (text.length > 140) {
                if (current) {
                    result.push(current);
                    current = "";
                }
                for (let i = 0; i < text.length; i += 120) {
                    result.push(text.slice(i, i + 120));
                }
                return;
            }
            const next = current ? current + " " + text : text;
            if (next.length > 160) {
                result.push(current);
                current = text;
            } else {
                current = next;
            }
        });
        if (current) {
            result.push(current);
        }
        return result;
    }

    function isChineseVoice(voice) {
        return /^zh/i.test(voice.lang) || /Chinese|Mandarin|中文|普通话/i.test(voice.name);
    }

    function voiceId(voice) {
        return `${voice.name}||${voice.lang}`;
    }

    function rememberedVoice() {
        try {
            return localStorage.getItem(storageKey) || "";
        } catch (error) {
            return "";
        }
    }

    function saveVoice(value) {
        try {
            localStorage.setItem(storageKey, value);
        } catch (error) {
            // Storage can be unavailable in private or embedded browser contexts.
        }
    }

    function selectedVoice() {
        const selectedId = voiceSelect.value;
        if (selectedId) {
            return availableVoices.find((voice) => voiceId(voice) === selectedId) || null;
        }
        return availableVoices.find(isChineseVoice) || null;
    }

    function populateVoices() {
        const voices = synth.getVoices();
        if (!voices.length) {
            voiceHint.textContent = "系统默认";
            return;
        }
        const displayVoices = voices.filter(isChineseVoice);
        availableVoices = displayVoices.length ? displayVoices : voices;
        const previousValue = voiceSelect.value;
        const targetValue = previousValue || rememberedVoice();
        voiceSelect.innerHTML = '<option value="">系统默认</option>';
        availableVoices.forEach((voice) => {
            const option = document.createElement("option");
            option.value = voiceId(voice);
            option.textContent = `${voice.name} (${voice.lang || "默认"})`;
            voiceSelect.appendChild(option);
        });
        if (targetValue && availableVoices.some((voice) => voiceId(voice) === targetValue)) {
            voiceSelect.value = targetValue;
        }
        voiceHint.textContent = displayVoices.length ? "中文音色" : "本机音色";
    }

    function progressText() {
        if (!chunks.length) {
            return "";
        }
        return `${Math.min(chunkIndex + 1, chunks.length)}/${chunks.length}`;
    }

    function setStatus(message, state) {
        const playing = state === "playing";
        const paused = state === "paused";
        const percent = chunks.length ? Math.min(100, Math.round((chunkIndex / chunks.length) * 100)) : 0;
        status.textContent = message;
        actions.toggle.classList.toggle("is-playing", playing);
        actions.toggle.setAttribute("aria-label", playing ? "暂停" : (paused ? "继续" : "播放"));
        progress.style.width = `${percent}%`;
    }

    function createUtterance(text, index) {
        const utterance = new SpeechSynthesisUtterance(text);
        utterance.lang = "zh-CN";
        utterance.rate = Number(rateInput.value) || defaultRate();
        const voice = selectedVoice();
        if (voice) {
            utterance.voice = voice;
            utterance.lang = voice.lang || "zh-CN";
        }
        utterance.onstart = () => {
            if (stopRequested) {
                return;
            }
            chunkIndex = index;
            setStatus(`播放中 ${progressText()}`, "playing");
        };
        utterance.onend = () => {
            if (stopRequested) {
                return;
            }
            if (index >= chunks.length - 1) {
                chunkIndex = chunks.length;
                setStatus("播放完成", "idle");
                progress.style.width = "100%";
            }
        };
        utterance.onerror = () => {
            if (!stopRequested) {
                setStatus("朗读被浏览器中断，请重试。", "idle");
            }
        };
        return utterance;
    }

    function play() {
        const nextChunks = splitText(reportText());
        if (nextChunks.length === 0) {
            setStatus("没有可朗读正文", "idle");
            return;
        }
        stopRequested = true;
        synth.cancel();
        chunks = nextChunks;
        chunkIndex = 0;
        stopRequested = false;
        pausedByUser = false;
        chunks.forEach((chunk, index) => {
            synth.speak(createUtterance(chunk, index));
        });
        setStatus(`播放中 ${progressText()}`, "playing");
    }

    function pause() {
        if (synth.speaking && !synth.paused) {
            pausedByUser = true;
            synth.pause();
            setStatus("已暂停", "paused");
        }
    }

    function resume() {
        if (synth.paused) {
            pausedByUser = false;
            synth.resume();
            setStatus(`播放中 ${progressText()}`, "playing");
        }
    }

    function stop() {
        stopRequested = true;
        synth.cancel();
        pausedByUser = false;
        setStatus("已停止", "idle");
        progress.style.width = "0%";
    }

    function toggle() {
        if (synth.speaking && !synth.paused) {
            pause();
            return;
        }
        if (synth.paused) {
            resume();
            return;
        }
        play();
    }

    actions.toggle.addEventListener("click", toggle);
    rateInput.addEventListener("change", () => {
        setRate(rateInput.value);
        if (synth.speaking || synth.paused) {
            stop();
            setStatus("语速已切换，请重新播放", "idle");
        }
    });
    voiceSelect.addEventListener("change", () => {
        saveVoice(voiceSelect.value);
        if (synth.speaking || synth.paused) {
            stop();
            setStatus("音色已切换，请重新播放", "idle");
        }
    });
    document.addEventListener("visibilitychange", () => {
        if (!document.hidden && pausedByUser && synth.paused) {
            setStatus("已暂停", "paused");
        }
    });
    window.addEventListener("beforeunload", () => {
        stopRequested = true;
        synth.cancel();
    });
    if ("onvoiceschanged" in synth) {
        synth.onvoiceschanged = populateVoices;
    }
    setRate(defaultRate());
    populateVoices();
    setStatus("准备播放", "idle");
}

// The audio player runs before the system reader: it marks the reader as the fallback when there is no usable audio.
export function initReportPage() {
    initImageViewer();
    initAudioPlayer();
    initSystemReader();
}

if (typeof document !== "undefined") {
    initReportPage();
}
