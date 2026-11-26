// Shared constants and the single mutable admin state object. Has no imports, so it is always evaluated first.

export const FREESTYLE_STYLE = "FREESTYLE";
export const CONFIRM_TIMEOUT_MS = 5000;
export const NOTIFICATION_FILTER_PERIOD_TYPES = [
    {value: "DAILY", label: "每日"},
    {value: "WEEKLY", label: "每周"},
    {value: "MONTHLY", label: "每月"}
];
export const NOTIFICATION_FILTER_TRIGGER_TYPES = [
    {value: "SCHEDULED", label: "定时"},
    {value: "MANUAL", label: "手动"}
];
export const MIMO_TTS_PRESET_MODEL = "mimo-v2.5-tts";
export const MIMO_TTS_DEFAULT_VOICE = "苏打";
export const MIMO_TTS_DEFAULT_STYLE = "四川话";

export const state = {
    requests: {},
    modalOpeners: new Map(),
    prompts: [],
    aiProviders: [],
    aiProviderDowngradeConfig: {},
    defaultTitleFormatPrompt: "",
    previewEvents: {
        items: [],
        page: 1,
        size: 20,
        total: 0,
        totalPages: 0
    },
    shareSummaryTasks: [],
    shareSummaryRuns: {
        items: [],
        page: 1,
        size: 20,
        total: 0,
        totalPages: 0
    },
    shareSummaryImageConfig: {},
    shareSummaryAudioConfig: {},
    notificationEvents: [],
    notificationChannels: [],
    notificationTasks: [],
    notificationDeliveries: {
        items: [],
        page: 1,
        size: 20,
        total: 0,
        totalPages: 0
    },
    activeShareSummaryRunId: null,
    shareSummaryRefreshTimer: null,
    shareSummarySubmitting: false,
    activeShareSummaryBusy: false,
    shareSummaryDetailSignature: null,
    aiProviderDowngradeSaveTimer: null,
    aiProviderDowngradeSaveVersion: 0,
    logRefreshTimer: null,
    activeDangerButton: null,
    adminReady: false,
    loadedPanels: new Set(),
    loadingPanels: new Map(),
    adminNavHoverOpen: false,
    adminNavHoverCloseTimer: null,
    adminNavLastPointer: null
};
