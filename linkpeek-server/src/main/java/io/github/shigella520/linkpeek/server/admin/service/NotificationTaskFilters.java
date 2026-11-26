package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Notification task filters as stored in {@code filters_json}. Parsed when a task is saved
 * (validation and normalization) and when an event is matched against enabled tasks.
 */
record NotificationTaskFilters(List<Long> shareSummaryTaskIds, List<String> periodTypes, List<String> triggerTypes) {
    static NotificationTaskFilters parse(ObjectMapper objectMapper, String filtersJson) {
        if (!StringUtils.hasText(filtersJson)) {
            return new NotificationTaskFilters(List.of(), List.of(), List.of());
        }
        try {
            return parse(objectMapper.readTree(filtersJson));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Notification filters must be valid JSON.", exception);
        }
    }

    static NotificationTaskFilters parse(JsonNode filters) {
        if (filters == null || filters.isNull()) {
            return new NotificationTaskFilters(List.of(), List.of(), List.of());
        }
        if (!filters.isObject()) {
            throw new IllegalArgumentException("Notification filters must be a JSON object.");
        }
        return new NotificationTaskFilters(
                longList(filters.path("shareSummaryTaskIds"), "shareSummaryTaskIds"),
                stringList(filters.path("periodTypes"), "periodTypes").stream().map(value -> value.toUpperCase(Locale.ROOT)).toList(),
                stringList(filters.path("triggerTypes"), "triggerTypes").stream().map(value -> value.toUpperCase(Locale.ROOT)).toList()
        );
    }

    private static List<Long> longList(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(fieldName + " must be an array.");
        }
        List<Long> values = new ArrayList<>();
        node.forEach(item -> {
            if (!item.canConvertToLong()) {
                throw new IllegalArgumentException(fieldName + " must only contain numbers.");
            }
            values.add(item.asLong());
        });
        return values.stream().distinct().toList();
    }

    private static List<String> stringList(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(fieldName + " must be an array.");
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isTextual() || !StringUtils.hasText(item.asText())) {
                throw new IllegalArgumentException(fieldName + " must only contain strings.");
            }
            values.add(item.asText().strip());
        });
        return values.stream().distinct().toList();
    }
}
