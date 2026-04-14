(function () {
    const RANGE_VALUES = ["7d", "30d", "90d", "180d"];
    const REFRESH_INTERVAL_MS = 30_000;
    const WEEKDAY_LABELS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    const CHART_TEXT = "#6f6b66";
    const CHART_LINE = "rgba(24, 24, 24, 0.08)";
    const CHART_SPLIT = "rgba(24, 24, 24, 0.05)";
    const TOOLTIP_BACKGROUND = "rgba(255,255,255,0.94)";
    const TOOLTIP_BORDER = "rgba(20,20,20,0.08)";
    const COLORS = {
        ink: "#171717",
        blue: "#0a84ff",
        blueSoft: "#b8d7ff",
        orange: "#f97316",
        orangeSoft: "#ffd0a8",
        greenSoft: "#c7f2df",
        redSoft: "#fecaca"
    };

    const numberFormatter = new Intl.NumberFormat("zh-CN");
    const percentFormatter = new Intl.NumberFormat("zh-CN", {
        minimumFractionDigits: 1,
        maximumFractionDigits: 1
    });
    const dateTimeFormatter = new Intl.DateTimeFormat("zh-CN", {
        hour12: false,
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    });

    const state = {
        range: new URLSearchParams(window.location.search).get("range") || "30d",
        charts: {}
    };

    if (!RANGE_VALUES.includes(state.range)) {
        state.range = "30d";
    }

    function init() {
        bindRangeSwitch();
        bindParallax();
        initRevealObserver();
        initCharts();
        loadDashboard();
        window.addEventListener("resize", resizeCharts);
        window.setInterval(loadDashboard, REFRESH_INTERVAL_MS);
    }

    function bindRangeSwitch() {
        document.querySelectorAll("#range-switch button").forEach((button) => {
            button.classList.toggle("is-active", button.dataset.range === state.range);
            button.addEventListener("click", () => {
                state.range = button.dataset.range;
                document.querySelectorAll("#range-switch button").forEach((item) => {
                    item.classList.toggle("is-active", item.dataset.range === state.range);
                });
                const url = new URL(window.location.href);
                url.searchParams.set("range", state.range);
                window.history.replaceState({}, "", url);
                loadDashboard();
            });
        });
    }

    function bindParallax() {
        const update = () => {
            document.documentElement.style.setProperty("--scroll-depth", `${window.scrollY}px`);
        };
        update();
        window.addEventListener("scroll", update, {passive: true});
    }

    function initRevealObserver() {
        const observer = new IntersectionObserver((entries) => {
            entries.forEach((entry) => {
                if (entry.isIntersecting) {
                    entry.target.classList.add("is-visible");
                }
            });
        }, {
            threshold: 0.16,
            rootMargin: "0px 0px -12% 0px"
        });

        document.querySelectorAll(".reveal").forEach((node) => observer.observe(node));
    }

    function initCharts() {
        state.charts.growth = createChart("growth-chart");
        state.charts.funnel = createChart("funnel-chart");
        state.charts.failure = createChart("failure-chart");
        state.charts.newReturning = createChart("new-returning-chart");
        state.charts.heatmap = createChart("heatmap-chart");
    }

    function createChart(id) {
        return echarts.init(document.getElementById(id), null, {renderer: "svg"});
    }

    async function loadDashboard() {
        try {
            const response = await fetch(`/api/stats/dashboard?range=${encodeURIComponent(state.range)}`, {
                headers: {
                    Accept: "application/json"
                }
            });
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            const payload = await response.json();
            render(payload);
        } catch (error) {
            console.error("Failed to load dashboard", error);
        }
    }

    function render(payload) {
        setText("generated-at", `Updated ${dateTimeFormatter.format(new Date(payload.generatedAt))}`);
        setText("timezone", payload.timezone);

        renderMetric(payload.overview.createCount, [
            {valueId: "create-count", trendId: "create-count-trend"},
            {valueId: "create-count-inline", trendId: "create-count-inline-trend"}
        ]);
        renderMetric(payload.overview.openCount, [
            {valueId: "open-count", trendId: "open-count-trend"},
            {valueId: "open-count-inline", trendId: "open-count-inline-trend"}
        ]);
        renderMetric(payload.overview.uniqueLinkCount, [
            {valueId: "unique-link-count", trendId: "unique-link-count-trend"},
            {valueId: "unique-link-count-inline", trendId: "unique-link-count-inline-trend"}
        ]);
        renderMetric(payload.overview.newLinkCount, [
            {valueId: "new-link-count", trendId: "new-link-count-trend"},
            {valueId: "new-link-count-inline", trendId: "new-link-count-inline-trend"}
        ]);

        setText("preview-success-rate", formatRatio(payload.funnel.previewSuccessRate));
        setText("preview-success-rate-inline", formatRatio(payload.funnel.previewSuccessRate));
        setText("create-rate", formatRatio(payload.funnel.createRate));
        setText("create-rate-inline", formatRatio(payload.funnel.createRate));
        setText("open-create-ratio", formatRatio(payload.funnel.openCreateRatio));
        setText("open-create-ratio-inline", formatRatio(payload.funnel.openCreateRatio));

        renderGrowthChart(payload.growthTrend);
        renderFunnelChart(payload.funnel);
        renderFailureChart(payload.failureBreakdown);
        renderNewReturningChart(payload.newVsReturningTrend);
        renderHeatmapChart(payload.activityHeatmap);
        renderTopLinks(payload.topLinks);
    }

    function renderMetric(metric, targets) {
        targets.forEach(({valueId, trendId}) => {
            animateValue(valueId, metric.value);
            renderTrend(trendId, metric.changeRate);
        });
    }

    function renderTrend(trendId, changeRate) {
        const node = document.getElementById(trendId);
        if (!node) {
            return;
        }
        node.classList.remove("is-positive", "is-negative");
        if (changeRate === null) {
            node.textContent = "Fresh growth";
            node.classList.add("is-positive");
            return;
        }

        const formatted = `${changeRate >= 0 ? "+" : ""}${percentFormatter.format(changeRate)}%`;
        node.textContent = `vs last window ${formatted}`;
        if (changeRate > 0) {
            node.classList.add("is-positive");
        } else if (changeRate < 0) {
            node.classList.add("is-negative");
        }
    }

    function renderGrowthChart(points) {
        state.charts.growth.setOption({
            animationDuration: 900,
            animationDurationUpdate: 700,
            animationEasing: "cubicOut",
            backgroundColor: "transparent",
            tooltip: {
                trigger: "axis",
                backgroundColor: TOOLTIP_BACKGROUND,
                borderColor: TOOLTIP_BORDER,
                textStyle: {color: COLORS.ink},
                axisPointer: {
                    type: "line",
                    lineStyle: {
                        color: "rgba(10, 132, 255, 0.18)",
                        width: 1.5,
                        type: "dashed"
                    }
                }
            },
            legend: {show: false},
            grid: {left: 10, right: 10, bottom: 12, top: 18, containLabel: true},
            xAxis: {
                type: "category",
                boundaryGap: false,
                data: points.map((point) => point.date.slice(5)),
                axisLine: {lineStyle: {color: CHART_LINE}},
                axisTick: {show: false},
                axisLabel: {color: CHART_TEXT}
            },
            yAxis: {
                type: "value",
                splitLine: {lineStyle: {color: CHART_SPLIT}},
                axisLabel: {color: CHART_TEXT},
                axisLine: {show: false}
            },
            series: [
                {
                    name: "Created",
                    type: "line",
                    smooth: 0.5,
                    showSymbol: false,
                    symbol: "circle",
                    data: points.map((point) => point.createdCount),
                    lineStyle: {
                        width: 2.25,
                        color: COLORS.blue
                    },
                    itemStyle: {
                        color: COLORS.blue
                    },
                    areaStyle: {
                        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                            {offset: 0, color: "rgba(10,132,255,0.12)"},
                            {offset: 1, color: "rgba(10,132,255,0.02)"}
                        ])
                    }
                },
                {
                    name: "Opened",
                    type: "line",
                    smooth: 0.5,
                    showSymbol: false,
                    symbol: "circle",
                    data: points.map((point) => point.openedCount),
                    lineStyle: {
                        width: 2.25,
                        color: COLORS.orange
                    },
                    itemStyle: {
                        color: COLORS.orange
                    },
                    areaStyle: {
                        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                            {offset: 0, color: "rgba(249,115,22,0.10)"},
                            {offset: 1, color: "rgba(249,115,22,0.02)"}
                        ])
                    }
                }
            ]
        });
    }

    function renderFunnelChart(funnel) {
        state.charts.funnel.setOption({
            animationDuration: 900,
            animationDurationUpdate: 700,
            backgroundColor: "transparent",
            tooltip: {
                trigger: "item",
                backgroundColor: TOOLTIP_BACKGROUND,
                borderColor: TOOLTIP_BORDER,
                textStyle: {color: COLORS.ink}
            },
            grid: {left: 6, right: 6, top: 6, bottom: 6, containLabel: true},
            xAxis: {
                type: "value",
                show: false
            },
            yAxis: {
                type: "category",
                data: ["All", "Created", "Opened", "Failed"],
                axisLine: {show: false},
                axisTick: {show: false},
                axisLabel: {color: CHART_TEXT, fontWeight: 600}
            },
            series: [
                {
                    type: "bar",
                    universalTransition: true,
                    showBackground: true,
                    backgroundStyle: {
                        color: "rgba(24,24,24,0.035)",
                        borderRadius: 999
                    },
                    data: [
                        funnel.allPreviewRequests,
                        funnel.createCount,
                        funnel.openCount,
                        funnel.failedCount
                    ],
                    itemStyle: {
                        borderRadius: 999,
                        color: (params) => [COLORS.ink, COLORS.blue, COLORS.orange, "#d97706"][params.dataIndex],
                        shadowBlur: 8,
                        shadowColor: "rgba(23, 23, 23, 0.05)"
                    },
                    label: {
                        show: true,
                        position: "right",
                        color: COLORS.ink,
                        formatter: ({value}) => numberFormatter.format(value)
                    }
                }
            ]
        });
    }

    function renderFailureChart(failureBreakdown) {
        const data = [
            {name: "Invalid", value: failureBreakdown.invalid},
            {name: "Unsupported", value: failureBreakdown.unsupported},
            {name: "Upstream", value: failureBreakdown.upstream},
            {name: "Other", value: failureBreakdown.other}
        ];
        const total = failureBreakdown.total || data.reduce((sum, item) => sum + item.value, 0);

        state.charts.failure.setOption({
            animationDuration: 1000,
            animationDurationUpdate: 700,
            tooltip: {
                trigger: "item",
                backgroundColor: TOOLTIP_BACKGROUND,
                borderColor: TOOLTIP_BORDER,
                textStyle: {color: COLORS.ink}
            },
            legend: {
                bottom: 0,
                textStyle: {color: CHART_TEXT},
                icon: "circle"
            },
            series: [
                {
                    type: "pie",
                    radius: ["60%", "76%"],
                    center: ["50%", "42%"],
                    label: {show: false},
                    itemStyle: {
                        borderColor: "#f4f2ec",
                        borderWidth: 6
                    },
                    data,
                    color: [COLORS.blueSoft, COLORS.greenSoft, COLORS.orangeSoft, COLORS.redSoft]
                }
            ],
            graphic: [
                {
                    type: "text",
                    left: "center",
                    top: "31%",
                    style: {
                        text: numberFormatter.format(total),
                        font: '600 34px "Avenir Next", "SF Pro Display", sans-serif',
                        fill: COLORS.ink,
                        textAlign: "center"
                    }
                },
                {
                    type: "text",
                    left: "center",
                    top: "46%",
                    style: {
                        text: "Failures",
                        font: '500 12px "Avenir Next", "SF Pro Display", sans-serif',
                        fill: CHART_TEXT,
                        textAlign: "center"
                    }
                }
            ]
        });
    }

    function renderNewReturningChart(points) {
        state.charts.newReturning.setOption({
            animationDuration: 850,
            animationDurationUpdate: 650,
            tooltip: {
                trigger: "axis",
                backgroundColor: TOOLTIP_BACKGROUND,
                borderColor: TOOLTIP_BORDER,
                textStyle: {color: COLORS.ink}
            },
            legend: {show: false},
            grid: {left: 10, right: 10, bottom: 12, top: 18, containLabel: true},
            xAxis: {
                type: "category",
                data: points.map((point) => point.date.slice(5)),
                axisLine: {lineStyle: {color: CHART_LINE}},
                axisTick: {show: false},
                axisLabel: {color: CHART_TEXT}
            },
            yAxis: {
                type: "value",
                splitLine: {lineStyle: {color: CHART_SPLIT}},
                axisLabel: {color: CHART_TEXT},
                axisLine: {show: false}
            },
            series: [
                {
                    name: "New",
                    type: "line",
                    smooth: 0.55,
                    showSymbol: false,
                    data: points.map((point) => point.newLinkCount),
                    lineStyle: {
                        width: 2.2,
                        color: COLORS.blue
                    },
                    itemStyle: {
                        color: COLORS.blue
                    },
                    areaStyle: {
                        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                            {offset: 0, color: "rgba(10,132,255,0.12)"},
                            {offset: 1, color: "rgba(10,132,255,0.02)"}
                        ])
                    }
                },
                {
                    name: "Returning",
                    type: "line",
                    smooth: 0.55,
                    showSymbol: false,
                    data: points.map((point) => point.returningLinkCount),
                    lineStyle: {
                        width: 2.2,
                        color: COLORS.orange
                    },
                    itemStyle: {
                        color: COLORS.orange
                    },
                    areaStyle: {
                        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                            {offset: 0, color: "rgba(249,115,22,0.09)"},
                            {offset: 1, color: "rgba(249,115,22,0.02)"}
                        ])
                    }
                }
            ]
        });
    }

    function renderHeatmapChart(cells) {
        const values = cells.map((cell) => [cell.hour, cell.weekday, cell.count]);
        const maxValue = Math.max(1, ...cells.map((cell) => cell.count));

        state.charts.heatmap.setOption({
            animationDuration: 700,
            tooltip: {
                position: "top",
                backgroundColor: TOOLTIP_BACKGROUND,
                borderColor: TOOLTIP_BORDER,
                textStyle: {color: COLORS.ink},
                formatter: (params) => `${WEEKDAY_LABELS[params.value[1]]} ${params.value[0]}:00<br/>${numberFormatter.format(params.value[2])}`
            },
            grid: {left: 32, right: 10, top: 10, bottom: 30, containLabel: true},
            xAxis: {
                type: "category",
                data: Array.from({length: 24}, (_, hour) => `${hour}`),
                splitArea: {show: true},
                axisLabel: {color: CHART_TEXT},
                axisLine: {lineStyle: {color: CHART_LINE}}
            },
            yAxis: {
                type: "category",
                data: WEEKDAY_LABELS,
                splitArea: {show: true},
                axisLabel: {color: CHART_TEXT},
                axisLine: {lineStyle: {color: CHART_LINE}}
            },
            visualMap: {
                min: 0,
                max: maxValue,
                calculable: false,
                orient: "horizontal",
                left: "center",
                bottom: 0,
                textStyle: {color: CHART_TEXT},
                inRange: {
                    color: ["#f8f7f4", "#e6f0fb", "#c3dcfb", "#86bbff", "#3d7fff"]
                }
            },
            series: [
                {
                    type: "heatmap",
                    data: values,
                    progressive: 0,
                    itemStyle: {
                        borderColor: "#f4f2ec",
                        borderWidth: 4
                    },
                    emphasis: {
                        itemStyle: {
                            shadowBlur: 18,
                            shadowColor: "rgba(10,132,255,0.18)"
                        }
                    }
                }
            ]
        });
    }

    function renderTopLinks(topLinks) {
        const tbody = document.getElementById("top-links-body");
        if (!topLinks.length) {
            tbody.innerHTML = '<tr><td colspan="6" class="empty-row">No link activity in this range</td></tr>';
            return;
        }

        tbody.innerHTML = topLinks.map((link) => {
            const safeTitle = escapeHtml(link.title);
            const safeUrl = escapeHtml(link.canonicalUrl);
            const safeProvider = escapeHtml(link.providerId || "n/a");
            return `
                <tr>
                    <td>
                        <div class="title-cell">
                            <a href="${safeUrl}" target="_blank" rel="noreferrer">${safeTitle}</a>
                            <span class="link-url">${safeUrl}</span>
                        </div>
                    </td>
                    <td>${safeProvider}</td>
                    <td>${numberFormatter.format(link.createdCount)}</td>
                    <td>${numberFormatter.format(link.openedCount)}</td>
                    <td>${dateTimeFormatter.format(new Date(link.firstSeenAt))}</td>
                    <td>${dateTimeFormatter.format(new Date(link.lastSeenAt))}</td>
                </tr>
            `;
        }).join("");
    }

    function animateValue(id, value) {
        const node = document.getElementById(id);
        if (!node) {
            return;
        }

        const target = Number(value) || 0;
        const previous = Number(node.dataset.value || 0);
        const duration = 550;
        const startedAt = performance.now();
        node.dataset.value = String(target);

        function step(now) {
            const progress = Math.min(1, (now - startedAt) / duration);
            const eased = 1 - Math.pow(1 - progress, 3);
            const current = Math.round(previous + (target - previous) * eased);
            node.textContent = numberFormatter.format(current);
            if (progress < 1) {
                window.requestAnimationFrame(step);
            }
        }

        window.requestAnimationFrame(step);
    }

    function formatRatio(value) {
        if (value === null || Number.isNaN(value)) {
            return "Fresh";
        }
        return `${percentFormatter.format(value * 100)}%`;
    }

    function resizeCharts() {
        Object.values(state.charts).forEach((chart) => chart.resize());
    }

    function setText(id, value) {
        const node = document.getElementById(id);
        if (node) {
            node.textContent = value;
        }
    }

    function escapeHtml(value) {
        return String(value)
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#39;");
    }

    init();
})();
