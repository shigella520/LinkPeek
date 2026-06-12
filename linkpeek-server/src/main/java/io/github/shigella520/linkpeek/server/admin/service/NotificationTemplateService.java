package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.NotificationEventType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NotificationTemplateService {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)+)\\s*}}");
    private static final int MAX_TEMPLATE_LENGTH = 20 * 1024;
    private static final int MAX_RENDERED_LENGTH = 256 * 1024;
    private static final int MAX_PLACEHOLDER_COUNT = 200;
    private static final Set<String> CHANNEL_MESSAGE_PLACEHOLDERS = Set.of(
            "message.body",
            "message.bodyJson"
    );

    private final ObjectMapper objectMapper;

    public NotificationTemplateService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<EventSchema> events() {
        return Arrays.stream(NotificationEventType.values())
                .map(this::schema)
                .toList();
    }

    public EventSchema schema(NotificationEventType eventType) {
        return switch (eventType) {
            case SHARE_SUMMARY_IMAGE_SUCCESS -> shareSummaryImageSuccessSchema();
            case SHARE_SUMMARY_IMAGE_FAILED -> shareSummaryImageFailedSchema();
            case SHARE_SUMMARY_AUDIO_FAILED -> shareSummaryAudioFailedSchema();
            case AI_PROVIDER_REQUEST_FAILED -> aiProviderRequestFailedSchema();
            case AI_PROVIDER_AUTO_DOWNGRADED -> aiProviderAutoDowngradedSchema();
            case DATA_CRAWL_REQUEST_FAILED -> dataCrawlRequestFailedSchema();
        };
    }

    public TemplateValidationResult validate(String eventType, String templateJson) {
        NotificationEventType parsedEventType = NotificationEventType.fromValue(eventType);
        try {
            validateTemplate(parsedEventType, templateJson);
            return new TemplateValidationResult(true, extractPlaceholders(templateJson), List.of());
        } catch (TemplateValidationException exception) {
            return new TemplateValidationResult(false, exception.placeholders(), exception.placeholders());
        }
    }

    public String normalizeTemplate(NotificationEventType eventType, String templateJson) {
        validateTemplate(eventType, templateJson);
        return templateJson.strip();
    }

    public String normalizeChannelBodyTemplate(String bodyTemplate) {
        validateChannelBodyTemplate(bodyTemplate);
        return bodyTemplate.strip();
    }

    public void validateTemplate(NotificationEventType eventType, String templateJson) {
        if (!StringUtils.hasText(templateJson)) {
            throw new TemplateValidationException("Notification template is required.", List.of());
        }
        if (templateJson.length() > MAX_TEMPLATE_LENGTH) {
            throw new TemplateValidationException("Notification template must not exceed 20 KB.", List.of());
        }
        List<String> placeholders = extractPlaceholders(templateJson);
        if (placeholders.size() > MAX_PLACEHOLDER_COUNT) {
            throw new TemplateValidationException("Notification template contains too many placeholders.", List.of());
        }
        EventSchema schema = schema(eventType);
        Set<String> allowed = schema.placeholderNames();
        List<String> invalid = placeholders.stream()
                .filter(placeholder -> !allowed.contains(placeholder))
                .distinct()
                .sorted()
                .toList();
        if (!invalid.isEmpty()) {
            throw new TemplateValidationException("Notification template contains unsupported placeholders: " + String.join(", ", invalid), invalid);
        }
    }

    public String render(NotificationEventType eventType, String templateJson, Map<String, Object> values) {
        validateTemplate(eventType, templateJson);
        String rendered = renderWithValues(templateJson, values);
        if (rendered.length() > MAX_RENDERED_LENGTH) {
            throw new TemplateValidationException("Rendered notification body must not exceed 256 KB.", List.of());
        }
        return rendered;
    }

    public void validateChannelBodyTemplate(String bodyTemplate) {
        if (!StringUtils.hasText(bodyTemplate)) {
            throw new TemplateValidationException("Webhook body template is required.", List.of());
        }
        if (bodyTemplate.length() > MAX_TEMPLATE_LENGTH) {
            throw new TemplateValidationException("Webhook body template must not exceed 20 KB.", List.of());
        }
        List<String> placeholders = extractPlaceholders(bodyTemplate);
        if (placeholders.size() > MAX_PLACEHOLDER_COUNT) {
            throw new TemplateValidationException("Webhook body template contains too many placeholders.", List.of());
        }
        List<String> invalid = placeholders.stream()
                .filter(placeholder -> !CHANNEL_MESSAGE_PLACEHOLDERS.contains(placeholder))
                .distinct()
                .sorted()
                .toList();
        if (!invalid.isEmpty()) {
            throw new TemplateValidationException("Webhook body template contains unsupported placeholders: " + String.join(", ", invalid), invalid);
        }
    }

    public String renderChannelBody(String bodyTemplate, String messageBody) {
        validateChannelBodyTemplate(bodyTemplate);
        Map<String, Object> channelValues = new LinkedHashMap<>();
        channelValues.put("message.body", messageBody == null ? "" : messageBody);
        channelValues.put("message.bodyJson", messageBody == null ? "" : messageBody);
        String rendered = renderWithValues(bodyTemplate, channelValues, Set.of("message.bodyJson"));
        if (rendered.length() > MAX_RENDERED_LENGTH) {
            throw new TemplateValidationException("Rendered webhook body must not exceed 256 KB.", List.of());
        }
        return rendered;
    }

    public List<String> extractPlaceholders(String template) {
        List<String> placeholders = new ArrayList<>();
        if (!StringUtils.hasText(template)) {
            return placeholders;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        return placeholders;
    }

    private String renderWithValues(String templateJson, Map<String, Object> values) {
        return renderWithValues(templateJson, values, Set.of());
    }

    private String renderWithValues(String templateJson, Map<String, Object> values, Set<String> rawPlaceholders) {
        String rendered = templateJson;
        for (String placeholder : extractPlaceholders(templateJson).stream().distinct().toList()) {
            String quotedPattern = "\"\\s*\\{\\{\\s*" + Pattern.quote(placeholder) + "\\s*}}\\s*\"";
            Object value = values.get(placeholder);
            rendered = rendered.replaceAll(quotedPattern, Matcher.quoteReplacement(jsonValue(value == null ? "" : String.valueOf(value))));
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(rendered);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            Object value = values.get(placeholder);
            String replacement = rawPlaceholders.contains(placeholder) ? String.valueOf(value == null ? "" : value) : jsonStringFragment(value);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String jsonValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new TemplateValidationException("Notification value could not be rendered.", List.of());
        }
    }

    private String jsonStringFragment(Object value) {
        if (value == null) {
            return "";
        }
        String json = jsonValue(String.valueOf(value));
        return json.substring(1, json.length() - 1);
    }

    private EventSchema shareSummaryImageSuccessSchema() {
        List<Placeholder> placeholders = new ArrayList<>();
        addCommonEventPlaceholders(placeholders, NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS);
        addShareSummaryRunPlaceholders(placeholders);
        addShareSummaryImagePlaceholders(placeholders, false);
        addSystemPlaceholders(placeholders);
        return new EventSchema(
                NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS.name(),
                "分享总结图片生成成功",
                "分享总结 AI 图片与公开分享页生成成功后触发。",
                placeholders
        );
    }

    private EventSchema shareSummaryImageFailedSchema() {
        List<Placeholder> placeholders = new ArrayList<>();
        addCommonEventPlaceholders(placeholders, NotificationEventType.SHARE_SUMMARY_IMAGE_FAILED);
        addShareSummaryRunPlaceholders(placeholders);
        addShareSummaryImagePlaceholders(placeholders, true);
        add(placeholders, "error", "error.message", "string", "错误信息", "图片生成记录进入失败状态时保存的错误信息。", "Image provider returned HTTP 500 body=failed", true);
        addSystemPlaceholders(placeholders);
        return new EventSchema(
                NotificationEventType.SHARE_SUMMARY_IMAGE_FAILED.name(),
                "分享总结图片生成失败",
                "分享总结图片生成记录进入 FAILED 状态后触发，包括队列满、中断、上游请求失败、下载、转码或存储失败。",
                placeholders
        );
    }

    private EventSchema shareSummaryAudioFailedSchema() {
        List<Placeholder> placeholders = new ArrayList<>();
        addCommonEventPlaceholders(placeholders, NotificationEventType.SHARE_SUMMARY_AUDIO_FAILED);
        addShareSummaryRunPlaceholders(placeholders);
        addShareSummaryAudioPlaceholders(placeholders);
        add(placeholders, "error", "error.message", "string", "错误信息", "音频生成记录进入失败状态时保存的错误信息。", "Audio provider returned HTTP 500 body=failed", true);
        addSystemPlaceholders(placeholders);
        return new EventSchema(
                NotificationEventType.SHARE_SUMMARY_AUDIO_FAILED.name(),
                "分享总结音频生成失败",
                "分享总结音频生成记录进入 FAILED 状态后触发，包括队列满、中断、上游请求失败或存储失败。",
                placeholders
        );
    }

    private EventSchema aiProviderRequestFailedSchema() {
        List<Placeholder> placeholders = new ArrayList<>();
        addCommonEventPlaceholders(placeholders, NotificationEventType.AI_PROVIDER_REQUEST_FAILED);
        addAiProviderPlaceholders(placeholders);
        addAiRequestPlaceholders(placeholders);
        addAiErrorPlaceholders(placeholders);
        add(placeholders, "downgrade", "downgrade.enabled", "boolean", "自动降级启用", "AI Provider 失败阈值降级是否启用。", true, true);
        add(placeholders, "downgrade", "downgrade.failureCount", "number", "失败计数", "当前 Provider 连续失败次数；自动降级关闭时为 0。", 2, true);
        add(placeholders, "downgrade", "downgrade.failureThreshold", "number", "失败阈值", "触发失败阈值降级所需的连续失败次数。", 3, true);
        add(placeholders, "downgrade", "downgrade.triggered", "boolean", "触发降级", "本次失败是否触发失败阈值降级。", false, true);
        addSystemPlaceholders(placeholders);
        return new EventSchema(
                NotificationEventType.AI_PROVIDER_REQUEST_FAILED.name(),
                "AI Provider 请求失败",
                "每次 AI Provider 调用失败后触发，包括异常、中断、空标题、空总结。",
                placeholders
        );
    }

    private EventSchema aiProviderAutoDowngradedSchema() {
        List<Placeholder> placeholders = new ArrayList<>();
        addCommonEventPlaceholders(placeholders, NotificationEventType.AI_PROVIDER_AUTO_DOWNGRADED);
        addAiProviderPlaceholders(placeholders);
        addAiRequestPlaceholders(placeholders);
        addAiErrorPlaceholders(placeholders);
        add(placeholders, "downgrade", "downgrade.failureCount", "number", "失败计数", "触发失败阈值降级时的连续失败次数。", 3, true);
        add(placeholders, "downgrade", "downgrade.failureThreshold", "number", "失败阈值", "触发失败阈值降级所需的连续失败次数。", 3, true);
        add(placeholders, "downgrade", "downgrade.oldSortOrder", "number", "原排序值", "降级前的 Provider 排序值。", 100, true);
        add(placeholders, "downgrade", "downgrade.newSortOrder", "number", "新排序值", "降级后的 Provider 排序值。", 300, true);
        add(placeholders, "downgrade", "downgrade.alreadyLowest", "boolean", "触发前已在末位", "触发自动降级前，Provider 是否已经位于列表最后。", false, true);
        add(placeholders, "downgrade", "downgrade.providerCount", "number", "Provider 总数", "本次参与排序的 AI Provider 数量。", 3, true);
        addSystemPlaceholders(placeholders);
        return new EventSchema(
                NotificationEventType.AI_PROVIDER_AUTO_DOWNGRADED.name(),
                "AI Provider 失败阈值降级",
                "AI Provider 连续失败达到阈值并执行自动降级后触发。",
                placeholders
        );
    }

    private EventSchema dataCrawlRequestFailedSchema() {
        List<Placeholder> placeholders = new ArrayList<>();
        addCommonEventPlaceholders(placeholders, NotificationEventType.DATA_CRAWL_REQUEST_FAILED);
        add(placeholders, "preview", "preview.previewKey", "string", "预览 Key", "匹配内容 Provider 后解析得到的预览 Key。", "preview-key", true);
        add(placeholders, "preview", "preview.providerId", "string", "内容 Provider", "处理本次预览的内容 Provider ID。", "bilibili", true);
        add(placeholders, "preview", "preview.sourceUrl", "string", "原始 URL", "用户请求中的源 URL。", "https://www.bilibili.com/video/BV1xx411c7mD", true);
        add(placeholders, "preview", "preview.canonicalUrl", "string", "规范 URL", "内容 Provider 规范化后的 URL。", "https://www.bilibili.com/video/BV1xx411c7mD", true);
        add(placeholders, "request", "request.clientType", "string", "客户端类型", "CRAWLER 或 BROWSER。", "CRAWLER", true);
        add(placeholders, "request", "request.httpStatus", "number", "HTTP 状态", "返回给客户端的 HTTP 状态码，当前为 502。", 502, true);
        add(placeholders, "request", "request.durationMs", "number", "请求耗时", "本次 /preview 请求耗时。", 1200, true);
        add(placeholders, "request", "request.requestedStyle", "string", "请求风格", "请求中的 AI 标题风格；未请求时为空。", "FUN", false);
        add(placeholders, "error", "error.code", "string", "错误码", "统计错误码，当前为 UPSTREAM_ERROR。", "UPSTREAM_ERROR", true);
        add(placeholders, "error", "error.type", "string", "错误类型", "异常类简单名称。", "UpstreamFetchException", true);
        add(placeholders, "error", "error.message", "string", "错误信息", "异常消息。", "Upstream request failed.", true);
        addSystemPlaceholders(placeholders);
        return new EventSchema(
                NotificationEventType.DATA_CRAWL_REQUEST_FAILED.name(),
                "数据爬取请求失败",
                "已匹配内容 Provider 后，上游数据爬取失败并返回 502 时触发。",
                placeholders
        );
    }

    private void addCommonEventPlaceholders(List<Placeholder> placeholders, NotificationEventType eventType) {
        add(placeholders, "event", "event.type", "string", "事件类型", "固定为 " + eventType.name() + "。", eventType.name(), true);
        add(placeholders, "event", "event.key", "string", "事件标识", "事件唯一标识，用于定位对应发送记录。", eventType.name() + ":example", true);
        add(placeholders, "event", "event.occurredAt", "number", "事件时间", "事件发生时间，epoch milliseconds。", 1770000000000L, true);
        add(placeholders, "event", "event.occurredAtIso", "string", "事件 ISO 时间", "事件发生时间，ISO-8601 字符串。", "2026-05-31T10:00:00Z", true);
    }

    private void addSystemPlaceholders(List<Placeholder> placeholders) {
        add(placeholders, "system", "system.baseUrl", "string", "系统 Base URL", "LinkPeek 对外基础 URL。", "https://example.com", true);
        add(placeholders, "system", "system.appName", "string", "应用名称", "应用名称。", "LinkPeek", true);
    }

    private void addShareSummaryRunPlaceholders(List<Placeholder> placeholders) {
        add(placeholders, "run", "run.id", "number", "执行记录 ID", "分享总结执行记录 ID。", 12, true);
        add(placeholders, "run", "run.taskId", "number", "分享总结任务 ID", "分享总结任务 ID。", 3, true);
        add(placeholders, "run", "run.taskName", "string", "任务名称", "执行时任务名称快照。", "每周分享总结", true);
        add(placeholders, "run", "run.triggerType", "string", "触发方式", "SCHEDULED 或 MANUAL。", "SCHEDULED", true);
        add(placeholders, "run", "run.periodType", "string", "周期类型", "DAILY、WEEKLY 或 MONTHLY。", "WEEKLY", true);
        add(placeholders, "run", "run.windowStart", "number", "窗口开始", "总结窗口开始时间，epoch milliseconds。", 1769400000000L, true);
        add(placeholders, "run", "run.windowEnd", "number", "窗口结束", "总结窗口结束时间，epoch milliseconds。", 1770000000000L, true);
        add(placeholders, "run", "run.windowStartLabel", "string", "窗口开始日期", "总结窗口开始日期标签。", "2026-05-25", true);
        add(placeholders, "run", "run.windowEndLabel", "string", "窗口结束日期", "总结窗口结束日期标签。", "2026-06-01", true);
        add(placeholders, "run", "run.status", "string", "总结状态", "分享总结执行状态。", "SUCCESS", true);
        add(placeholders, "run", "run.linkCount", "number", "原始链接数", "原始链接创建记录数。", 42, true);
        add(placeholders, "run", "run.uniqueLinkCount", "number", "去重链接数", "去重后的链接标题数。", 30, true);
        add(placeholders, "run", "run.inputLinkCount", "number", "AI 输入链接数", "实际输入 AI 总结的链接数量。", 30, true);
        add(placeholders, "run", "run.aiProviderNames", "string", "AI Provider", "实际参与总结生成的 AI Provider 名称。", "OpenAI", false);
        add(placeholders, "run", "run.aiDurationMs", "number", "AI 耗时", "分享总结 AI 调用耗时。", 18000, true);
        add(placeholders, "run", "run.report", "string", "总结报告", "分享总结报告正文。", "本周分享内容集中在产品发布和技术实践。", false);
    }

    private void addShareSummaryImagePlaceholders(List<Placeholder> placeholders, boolean includeErrorMessage) {
        add(placeholders, "image", "image.id", "number", "图片记录 ID", "分享图记录 ID。", 99, true);
        add(placeholders, "image", "image.runId", "number", "关联执行 ID", "关联的分享总结执行记录 ID。", 12, true);
        add(placeholders, "image", "image.attemptNo", "number", "生成次数", "同一报告的第几次生图。", 1, true);
        add(placeholders, "image", "image.status", "string", "图片状态", "图片状态。", includeErrorMessage ? "FAILED" : "SUCCESS", true);
        add(placeholders, "image", "image.providerType", "string", "生图 Provider", "生图 Provider 类型快照。", "OPENAI_COMPATIBLE", true);
        add(placeholders, "image", "image.model", "string", "生图模型", "生图模型快照。", "gpt-image-2", true);
        add(placeholders, "image", "image.imageSize", "string", "图片尺寸", "上游生图尺寸配置快照。", "auto", true);
        add(placeholders, "image", "image.outputFormat", "string", "输出格式", "最终输出格式。", "png", true);
        add(placeholders, "image", "image.quality", "string", "图片质量", "图片质量配置快照。", "auto", false);
        add(placeholders, "image", "image.imageUrl", "string", "图片 URL", "后台或公开可访问图片 URL；失败时通常为空。", "https://example.com/share-summary/og-images/token.png", !includeErrorMessage);
        add(placeholders, "image", "image.ogImageUrl", "string", "OG 图片 URL", "可用于 og:image 的公开图片 URL；失败时通常为空。", "https://example.com/share-summary/og-images/token.png", !includeErrorMessage);
        add(placeholders, "image", "image.ogPageUrl", "string", "OG 分享页 URL", "带完整 Open Graph meta 的公开分享页 URL；失败时通常为空。", "https://example.com/share-summary/reports/token", !includeErrorMessage);
        add(placeholders, "image", "image.ogShareUrl", "string", "推荐转发 URL", "推荐转发 URL，第一版等同于 ogPageUrl；失败时通常为空。", "https://example.com/share-summary/reports/token", !includeErrorMessage);
        add(placeholders, "image", "image.ogTitle", "string", "OG 标题", "Open Graph 标题。", "LinkPeek - 2026年第22周周报", true);
        add(placeholders, "image", "image.ogDescription", "string", "OG 描述", "Open Graph 描述。", "本报告汇总了 2026-05-25 至 2026-06-01 的链接分享与内容洞察。", true);
        add(placeholders, "image", "image.durationMs", "number", "生图耗时", "生图耗时。", 65000, true);
        add(placeholders, "image", "image.createdAt", "number", "图片创建时间", "图片记录创建时间，epoch milliseconds。", 1770000000000L, true);
        add(placeholders, "image", "image.startedAt", "number", "生图开始时间", "生图开始时间，epoch milliseconds。", 1770000000000L, false);
        add(placeholders, "image", "image.finishedAt", "number", "生图结束时间", "生图结束时间，epoch milliseconds。", 1770000065000L, true);
        if (includeErrorMessage) {
            add(placeholders, "image", "image.errorMessage", "string", "图片错误信息", "图片生成失败时保存的错误信息。", "Image provider returned HTTP 500 body=failed", true);
        }
    }

    private void addShareSummaryAudioPlaceholders(List<Placeholder> placeholders) {
        add(placeholders, "audio", "audio.id", "number", "音频记录 ID", "音频记录 ID。", 88, true);
        add(placeholders, "audio", "audio.runId", "number", "关联执行 ID", "关联的分享总结执行记录 ID。", 12, true);
        add(placeholders, "audio", "audio.attemptNo", "number", "生成次数", "同一报告的第几次音频生成。", 1, true);
        add(placeholders, "audio", "audio.status", "string", "音频状态", "音频状态。", "FAILED", true);
        add(placeholders, "audio", "audio.providerType", "string", "音频 Provider", "音频 Provider 类型快照。", "OPENAI_COMPATIBLE", true);
        add(placeholders, "audio", "audio.model", "string", "音频模型", "音频模型快照。", "tts-1", false);
        add(placeholders, "audio", "audio.voice", "string", "声音", "音频声音配置快照。", "zh-CN-YunhaoNeural", true);
        add(placeholders, "audio", "audio.speed", "number", "语速", "音频语速配置快照。", 1.2, true);
        add(placeholders, "audio", "audio.pitch", "number", "音调", "音频音调配置快照。", 0, true);
        add(placeholders, "audio", "audio.style", "string", "风格", "音频风格配置快照。", "newscast", false);
        add(placeholders, "audio", "audio.outputFormat", "string", "输出格式", "最终输出格式。", "mp3", true);
        add(placeholders, "audio", "audio.audioUrl", "string", "音频 URL", "后台或公开可访问音频 URL；失败时通常为空。", "https://example.com/share-summary/audios/token.mp3", false);
        add(placeholders, "audio", "audio.durationMs", "number", "音频生成耗时", "音频生成耗时。", 12000, true);
        add(placeholders, "audio", "audio.createdAt", "number", "音频创建时间", "音频记录创建时间，epoch milliseconds。", 1770000000000L, true);
        add(placeholders, "audio", "audio.startedAt", "number", "音频开始时间", "音频开始时间，epoch milliseconds。", 1770000000000L, false);
        add(placeholders, "audio", "audio.finishedAt", "number", "音频结束时间", "音频结束时间，epoch milliseconds。", 1770000012000L, true);
        add(placeholders, "audio", "audio.errorMessage", "string", "音频错误信息", "音频生成失败时保存的错误信息。", "Audio provider returned HTTP 500 body=failed", true);
    }

    private void addAiProviderPlaceholders(List<Placeholder> placeholders) {
        add(placeholders, "provider", "provider.id", "number", "Provider ID", "AI Provider ID。", 1, true);
        add(placeholders, "provider", "provider.name", "string", "Provider 名称", "AI Provider 名称。", "OpenAI", true);
        add(placeholders, "provider", "provider.enabled", "boolean", "启用状态", "AI Provider 是否启用。", true, true);
        add(placeholders, "provider", "provider.sortOrder", "number", "排序值", "AI Provider 当前排序值。", 100, true);
        add(placeholders, "provider", "provider.baseUrl", "string", "Base URL", "AI Provider 请求 Base URL。", "https://api.openai.com/v1", true);
        add(placeholders, "provider", "provider.apiKind", "string", "API 类型", "AI Provider API 类型。", "CHAT_COMPLETIONS", true);
        add(placeholders, "provider", "provider.model", "string", "模型", "AI Provider 模型名称。", "gpt-4.1-mini", true);
        add(placeholders, "provider", "provider.requestTimeoutSeconds", "number", "请求超时", "AI Provider 请求超时时间，单位秒。", 45, true);
    }

    private void addAiRequestPlaceholders(List<Placeholder> placeholders) {
        add(placeholders, "request", "request.operation", "string", "请求操作", "AI 调用场景，AI_TITLE 或 SHARE_SUMMARY。", "AI_TITLE", true);
        add(placeholders, "request", "request.durationMs", "number", "请求耗时", "本次 Provider 调用耗时。", 1500, true);
    }

    private void addAiErrorPlaceholders(List<Placeholder> placeholders) {
        add(placeholders, "error", "error.type", "string", "错误类型", "异常类简单名称。", "IOException", true);
        add(placeholders, "error", "error.message", "string", "错误信息", "异常消息或业务失败原因。", "AI provider returned empty title.", true);
    }

    private void add(
            List<Placeholder> placeholders,
            String group,
            String name,
            String type,
            String label,
            String description,
            Object example,
            boolean required
    ) {
        placeholders.add(new Placeholder(group, name, type, label, description, example, required));
    }

    public static final class TemplateValidationException extends IllegalArgumentException {
        private final List<String> placeholders;

        public TemplateValidationException(String message, List<String> placeholders) {
            super(message);
            this.placeholders = placeholders == null ? List.of() : placeholders;
        }

        public List<String> placeholders() {
            return placeholders;
        }
    }

    public record Placeholder(
            String group,
            String name,
            String type,
            String label,
            String description,
            Object example,
            boolean required
    ) {
    }

    public record EventSchema(
            String eventType,
            String label,
            String description,
            List<Placeholder> placeholders
    ) {
        public Set<String> placeholderNames() {
            return placeholders.stream()
                    .map(Placeholder::name)
                    .collect(java.util.stream.Collectors.toSet());
        }

        public Map<String, List<Placeholder>> groupedPlaceholders() {
            Map<String, List<Placeholder>> groups = new LinkedHashMap<>();
            placeholders.stream()
                    .sorted(Comparator.comparing(Placeholder::group).thenComparing(Placeholder::name))
                    .forEach(placeholder -> groups.computeIfAbsent(placeholder.group(), ignored -> new ArrayList<>()).add(placeholder));
            return groups;
        }
    }

    public record TemplateValidationResult(
            boolean valid,
            List<String> placeholders,
            List<String> invalidPlaceholders
    ) {
    }
}
