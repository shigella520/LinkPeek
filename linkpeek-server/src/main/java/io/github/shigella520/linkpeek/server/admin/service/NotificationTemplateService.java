package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.NotificationEventType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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
        return List.of(schema(NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS));
    }

    public EventSchema schema(NotificationEventType eventType) {
        return switch (eventType) {
            case SHARE_SUMMARY_IMAGE_SUCCESS -> shareSummaryImageSuccessSchema();
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
        add(placeholders, "event", "event.type", "string", "事件类型", "固定为 SHARE_SUMMARY_IMAGE_SUCCESS。", "SHARE_SUMMARY_IMAGE_SUCCESS", true);
        add(placeholders, "event", "event.occurredAt", "number", "事件时间", "事件发生时间，epoch milliseconds。", 1770000000000L, true);
        add(placeholders, "event", "event.occurredAtIso", "string", "事件 ISO 时间", "事件发生时间，ISO-8601 字符串。", "2026-05-31T10:00:00Z", true);
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
        add(placeholders, "image", "image.id", "number", "图片记录 ID", "分享图记录 ID。", 99, true);
        add(placeholders, "image", "image.runId", "number", "关联执行 ID", "关联的分享总结执行记录 ID。", 12, true);
        add(placeholders, "image", "image.attemptNo", "number", "生成次数", "同一报告的第几次生图。", 1, true);
        add(placeholders, "image", "image.status", "string", "图片状态", "图片状态。", "SUCCESS", true);
        add(placeholders, "image", "image.providerType", "string", "生图 Provider", "生图 Provider 类型快照。", "OPENAI_COMPATIBLE", true);
        add(placeholders, "image", "image.model", "string", "生图模型", "生图模型快照。", "gpt-image-2", true);
        add(placeholders, "image", "image.imageSize", "string", "图片尺寸", "上游生图尺寸配置快照。", "auto", true);
        add(placeholders, "image", "image.outputFormat", "string", "输出格式", "最终输出格式。", "png", true);
        add(placeholders, "image", "image.quality", "string", "图片质量", "图片质量配置快照。", "auto", false);
        add(placeholders, "image", "image.imageUrl", "string", "图片 URL", "后台或公开可访问图片 URL。", "https://example.com/share-summary/og-images/token.png", true);
        add(placeholders, "image", "image.ogImageUrl", "string", "OG 图片 URL", "可用于 og:image 的公开图片 URL。", "https://example.com/share-summary/og-images/token.png", true);
        add(placeholders, "image", "image.ogPageUrl", "string", "OG 分享页 URL", "带完整 Open Graph meta 的公开分享页 URL。", "https://example.com/share-summary/reports/token", true);
        add(placeholders, "image", "image.ogShareUrl", "string", "推荐转发 URL", "推荐转发 URL，第一版等同于 ogPageUrl。", "https://example.com/share-summary/reports/token", true);
        add(placeholders, "image", "image.ogTitle", "string", "OG 标题", "Open Graph 标题。", "LinkPeek - 2026年第22周周报", true);
        add(placeholders, "image", "image.ogDescription", "string", "OG 描述", "Open Graph 描述。", "本报告汇总了 2026-05-25 至 2026-06-01 的链接分享与内容洞察。", true);
        add(placeholders, "image", "image.durationMs", "number", "生图耗时", "生图耗时。", 65000, true);
        add(placeholders, "image", "image.createdAt", "number", "图片创建时间", "图片记录创建时间，epoch milliseconds。", 1770000000000L, true);
        add(placeholders, "image", "image.startedAt", "number", "生图开始时间", "生图开始时间，epoch milliseconds。", 1770000000000L, false);
        add(placeholders, "image", "image.finishedAt", "number", "生图结束时间", "生图结束时间，epoch milliseconds。", 1770000065000L, true);
        add(placeholders, "system", "system.baseUrl", "string", "系统 Base URL", "LinkPeek 对外基础 URL。", "https://example.com", true);
        add(placeholders, "system", "system.appName", "string", "应用名称", "应用名称。", "LinkPeek", true);
        return new EventSchema(
                NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS.name(),
                "分享总结图片生成成功",
                "分享总结 AI 图片与公开分享页生成成功后触发。",
                placeholders
        );
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
