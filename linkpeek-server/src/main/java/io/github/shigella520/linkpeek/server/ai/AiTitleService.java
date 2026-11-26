package io.github.shigella520.linkpeek.server.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.core.model.PreviewKey;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.provider.PreviewProvider;
import io.github.shigella520.linkpeek.core.util.CardTextSanitizer;
import io.github.shigella520.linkpeek.server.admin.model.AdminPromptRecord;
import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.AdminPromptMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import io.github.shigella520.linkpeek.server.admin.service.AiTitleConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiTitleService {
    private static final Logger log = LoggerFactory.getLogger(AiTitleService.class);

    public static final String FREESTYLE_STYLE = "FREESTYLE";
    public static final String DEFAULT_TITLE_FORMAT_PROMPT = AiTitleConfigService.DEFAULT_TITLE_FORMAT_PROMPT;
    private static final String OPERATION_AI_TITLE = "AI_TITLE";
    private static final long FREESTYLE_STABLE_WINDOW_MILLIS = 30_000;
    // FreeStyle 标题生成成功前不启动 30 秒窗口；该哨兵值表示风格选择仍在生成中。
    private static final long FREESTYLE_PENDING_EXPIRES_AT_MILLIS = Long.MAX_VALUE;
    // AI 长期失败时的兜底清理时间，只在选择表过大时用于回收旧 pending 记录。
    private static final long FREESTYLE_PENDING_CLEANUP_MILLIS = 3_600_000;
    private static final Pattern STYLE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final int MAX_TITLE_CODE_POINTS = 120;
    public static final Duration DEFAULT_TITLE_BUDGET =
            Duration.ofSeconds(AiProviderDowngradeService.DEFAULT_AI_TITLE_TOTAL_TIMEOUT_SECONDS);
    private static final Pattern REASONING_BLOCK = Pattern.compile(
            "(?is)<(think|thinking|analysis|reasoning)(?:\\s[^>]*)?>.*?</\\1\\s*>");
    private static final Pattern REASONING_OPEN = Pattern.compile("(?i)<(?:think|thinking|analysis|reasoning)(?:\\s[^>]*)?>");
    private static final Pattern REASONING_CLOSE = Pattern.compile("(?i)</(?:think|thinking|analysis|reasoning)\\s*>");
    private static final Pattern TITLE_LABEL = Pattern.compile("^(?:#+\\s*)?(?:\\*\\*)?(?:标题|title)(?:\\*\\*)?\\s*[：:]\\s*", Pattern.CASE_INSENSITIVE);
    // Conversational openers ("好的，以下是标题") and labelled side notes ("解释：…"). Ordinary words such as
    // "这是"/"分析" are deliberately not listed unless followed by a colon, because real titles start with them.
    private static final Pattern PREAMBLE_LINE = Pattern.compile(
            "^(?:(?:好的|当然|以下是|下面是|sure\\b|here(?:'s| is)\\b)"
                    + "|(?:解释|说明|理由|备注|注|分析|思路|note|explanation)\\s*[：:])",
            Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AdminPromptMapper adminPromptMapper;
    private final AiProviderMapper aiProviderMapper;
    private final OpenAiCompatibleTextClient textClient;
    private final AiTitleConfigService aiTitleConfigService;
    private final AiProviderDowngradeService aiProviderDowngradeService;
    private final Clock clock;
    private final ConcurrentHashMap<String, FreestyleSelection> freestyleSelections = new ConcurrentHashMap<>();
    // Total time a styled title may take across all providers; zero or negative disables the budget.
    // Fallback when no downgrade service is wired; the admin setting lives in AiProviderDowngradeService.
    private Duration titleBudget = DEFAULT_TITLE_BUDGET;
    private boolean titleBudgetOverridden;
    private LongSupplier nanoTime = System::nanoTime;

    @Autowired
    public AiTitleService(
            AdminPromptMapper adminPromptMapper,
            AiProviderMapper aiProviderMapper,
            OpenAiCompatibleTextClient textClient,
            AiTitleConfigService aiTitleConfigService,
            AiProviderDowngradeService aiProviderDowngradeService,
            Clock clock
    ) {
        this.adminPromptMapper = adminPromptMapper;
        this.aiProviderMapper = aiProviderMapper;
        this.textClient = textClient;
        this.aiTitleConfigService = aiTitleConfigService;
        this.aiProviderDowngradeService = aiProviderDowngradeService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    void setTitleBudget(Duration titleBudget, LongSupplier nanoTime) {
        this.titleBudget = titleBudget;
        this.titleBudgetOverridden = true;
        this.nanoTime = nanoTime;
    }

    public AiTitleService(
            AdminPromptMapper adminPromptMapper,
            AiProviderMapper aiProviderMapper,
            OpenAiCompatibleTextClient textClient,
            AiTitleConfigService aiTitleConfigService,
            AiProviderDowngradeService aiProviderDowngradeService
    ) {
        this(
                adminPromptMapper,
                aiProviderMapper,
                textClient,
                aiTitleConfigService,
                aiProviderDowngradeService,
                Clock.systemUTC()
        );
    }

    public Optional<StylePrompt> resolveStylePrompt(String style) {
        return resolveStylePrompt(style, null);
    }

    public Optional<StylePrompt> resolveStylePrompt(String style, URI stableFreestyleUri) {
        if (!StringUtils.hasText(style)) {
            return Optional.empty();
        }
        String strippedStyle = style.strip();
        if (!STYLE_PATTERN.matcher(strippedStyle).matches()) {
            return Optional.empty();
        }
        String normalizedStyle = normalizeStyleKey(strippedStyle);
        if (isFreestyleStyle(normalizedStyle)) {
            return resolveFreestylePrompt(stableFreestyleUri);
        }
        AdminPromptRecord prompt = adminPromptMapper.selectPrompt(normalizedStyle);
        return stylePrompt(prompt);
    }

    private Optional<StylePrompt> resolveFreestylePrompt(URI stableFreestyleUri) {
        List<StylePrompt> prompts = freestylePrompts();
        if (prompts.isEmpty()) {
            return Optional.empty();
        }
        StylePrompt prompt = stableFreestyleUri == null
                ? prompts.get(ThreadLocalRandom.current().nextInt(prompts.size()))
                : stableFreestyleSelection(stableFreestyleUri, prompts);
        return Optional.of(prompt);
    }

    private StylePrompt stableFreestyleSelection(URI stableFreestyleUri, List<StylePrompt> prompts) {
        long now = clock.millis();
        if (freestyleSelections.size() > 4_096) {
            freestyleSelections.entrySet().removeIf(entry -> entry.getValue().isRemovable(now));
        }
        FreestyleSelection selection = freestyleSelections.compute(
                stableFreestyleKey(stableFreestyleUri, prompts),
                (ignored, existing) -> existing != null && !existing.isExpired(now)
                        ? existing
                        : new FreestyleSelection(
                                prompts.get(ThreadLocalRandom.current().nextInt(prompts.size())),
                                FREESTYLE_PENDING_EXPIRES_AT_MILLIS,
                                // 首次抽到风格后先保持 pending，避免 iMessage 超时重试时重新随机。
                                now
                        )
        );
        return selection.stylePrompt();
    }

    // AI 标题生成成功或命中缓存后，才把 pending 选择转入 30 秒稳定窗口。
    public boolean markFreestyleSelectionSucceeded(URI stableFreestyleUri, StylePrompt stylePrompt) {
        if (stableFreestyleUri == null || stylePrompt == null) {
            return false;
        }
        List<StylePrompt> prompts = freestylePrompts();
        if (prompts.isEmpty()) {
            return false;
        }
        long expiresAtMillis = clock.millis() + FREESTYLE_STABLE_WINDOW_MILLIS;
        AtomicBoolean marked = new AtomicBoolean(false);
        freestyleSelections.computeIfPresent(stableFreestyleKey(stableFreestyleUri, prompts), (ignored, existing) ->
                {
                    if (existing.stylePrompt().equals(stylePrompt) && existing.isPending()) {
                        marked.set(true);
                        return new FreestyleSelection(existing.stylePrompt(), expiresAtMillis, 0);
                    }
                    return existing;
                }
        );
        return marked.get();
    }

    private List<StylePrompt> freestylePrompts() {
        return adminPromptMapper.selectAllPrompts().stream()
                .filter(prompt -> prompt != null
                        && StringUtils.hasText(prompt.getStyle())
                        && STYLE_PATTERN.matcher(prompt.getStyle().strip()).matches()
                        && !isFreestyleStyle(prompt.getStyle())
                        && StringUtils.hasText(prompt.getPrompt()))
                .sorted(Comparator.comparing(prompt -> normalizeStyleKey(prompt.getStyle())))
                .map(this::stylePrompt)
                .flatMap(Optional::stream)
                .toList();
    }

    private String stableFreestyleKey(URI stableFreestyleUri, List<StylePrompt> prompts) {
        StringBuilder basis = new StringBuilder("linkpeek-freestyle\n")
                .append(stableFreestyleUri)
                .append('\n');
        prompts.forEach(prompt -> basis
                .append(prompt.style())
                .append(':')
                .append(prompt.promptHash())
                .append('\n'));
        return sha256(basis.toString());
    }

    private Optional<StylePrompt> stylePrompt(AdminPromptRecord prompt) {
        if (prompt == null || !StringUtils.hasText(prompt.getStyle()) || !StringUtils.hasText(prompt.getPrompt())) {
            return Optional.empty();
        }
        String normalizedStyle = normalizeStyleKey(prompt.getStyle());
        String promptText = prompt.getPrompt().strip();
        String titleFormatPrompt = titleFormatPrompt();
        return Optional.of(new StylePrompt(
                normalizedStyle,
                promptText,
                titleFormatPrompt,
                sha256(promptText + "\n\n" + titleFormatPrompt)
        ));
    }

    public static boolean isFreestyleStyle(String style) {
        return StringUtils.hasText(style) && FREESTYLE_STYLE.equals(normalizeStyleKey(style));
    }

    public static String normalizeStyleKey(String style) {
        return style.strip().toUpperCase(Locale.ROOT);
    }

    public PreviewKey styledPreviewKey(URI canonicalUrl, StylePrompt stylePrompt) {
        return PreviewKey.fromStableValue(canonicalUrl.toString()
                + "\nlinkpeek-style=" + stylePrompt.style()
                + "\ntitle-format-version=2"
                + "\nprompt-sha256=" + stylePrompt.promptHash());
    }

    public boolean supportsAiTitle(PreviewMetadata metadata) {
        return PreviewProvider.defaultSupportsAiTitle(metadata);
    }

    public Optional<PreviewMetadata> generateStyledMetadata(PreviewMetadata metadata, StylePrompt stylePrompt) {
        return generateStyledMetadataResult(metadata, stylePrompt).metadata();
    }

    public StyledMetadataResult generateStyledMetadataResult(PreviewMetadata metadata, StylePrompt stylePrompt) {
        if (!supportsAiTitle(metadata)) {
            return StyledMetadataResult.empty();
        }
        return generateSupportedStyledMetadataResult(metadata, stylePrompt);
    }

    public StyledMetadataResult generateSupportedStyledMetadataResult(PreviewMetadata metadata, StylePrompt stylePrompt) {
        if (metadata == null || !StringUtils.hasText(metadata.rawContent())) {
            return StyledMetadataResult.empty();
        }
        AiTitlePrompt prompt = buildPrompt(stylePrompt.prompt(), metadata.rawContent(), stylePrompt.titleFormatPrompt());
        List<AiProviderRecord> providers = aiProviderMapper.selectEnabledProviders();
        AiAttemptStats attemptStats = new AiAttemptStats();
        Duration budget = currentTitleBudget();
        boolean budgeted = budget != null && !budget.isZero() && !budget.isNegative();
        long deadline = budgeted ? nanoTime.getAsLong() + budget.toNanos() : Long.MAX_VALUE;
        for (AiProviderRecord provider : providers) {
            long startedAt = System.nanoTime();
            // A request cut short by the shared budget says nothing about the provider's health.
            boolean truncatedByBudget = false;
            try {
                Duration timeout = null;
                if (budgeted) {
                    long remainingMs = (deadline - nanoTime.getAsLong()) / 1_000_000;
                    if (remainingMs <= 0) {
                        log.warn("ai_title_budget_exhausted style={} budgetMs={} attemptedProviders={}",
                                stylePrompt.style(), budget.toMillis(), attemptStats.providerNames.size());
                        break;
                    }
                    if (remainingMs < textClient.requestTimeout(provider).toMillis()) {
                        timeout = Duration.ofMillis(remainingMs);
                        truncatedByBudget = true;
                    }
                }
                OpenAiCompatibleTextClient.TitleResult result = textClient.generateTitleResult(provider, prompt, timeout);
                long durationMs = result.durationMs() > 0 ? result.durationMs() : elapsedMillis(startedAt);
                attemptStats.record(provider, durationMs, result.usage());
                Optional<String> generated = result.title()
                        .map(this::cleanTitle)
                        .filter(StringUtils::hasText);
                if (generated.isPresent()) {
                    recordAiProviderSuccess(provider);
                    return attemptStats.result(Optional.of(withTitle(metadata, generated.get())));
                }
                if (result.title().filter(StringUtils::hasText).isPresent()) {
                    // The provider answered; the content just was not usable as a title. That is an
                    // output-quality problem, not an availability failure, so it must not count
                    // towards automatic downgrade. Only the length is logged to keep model text out of logs.
                    log.warn(
                            "ai_title_invalid_output providerId={} style={} outputChars={}",
                            provider.getId(),
                            stylePrompt.style(),
                            result.title().get().length()
                    );
                    continue;
                }
                recordAiProviderFailure(provider, durationMs, new IllegalStateException("AI provider returned an empty title."));
            } catch (InterruptedException exception) {
                long durationMs = elapsedMillis(startedAt);
                attemptStats.record(provider, durationMs);
                log.warn("ai_title_request_interrupted providerId={} style={}", provider.getId(), stylePrompt.style(), exception);
                recordAiProviderFailure(provider, durationMs, exception);
                Thread.currentThread().interrupt();
                return attemptStats.result(Optional.empty());
            } catch (RuntimeException | java.io.IOException exception) {
                long durationMs = elapsedMillis(startedAt);
                attemptStats.record(provider, durationMs);
                if (truncatedByBudget && exception instanceof java.net.http.HttpTimeoutException) {
                    log.warn("ai_title_budget_timeout providerId={} style={} budgetMs={}",
                            provider.getId(), stylePrompt.style(), budget.toMillis());
                    break;
                }
                log.warn(
                        "ai_title_request_failed providerId={} style={} baseUrl={} message={}",
                        provider.getId(),
                        stylePrompt.style(),
                        provider.getBaseUrl(),
                        exception.getMessage()
                );
                recordAiProviderFailure(provider, durationMs, exception);
            }
        }
        return attemptStats.result(Optional.empty());
    }

    /** Read on every generation so a change in the admin panel applies to the next title without a restart. */
    private Duration currentTitleBudget() {
        if (titleBudgetOverridden || aiProviderDowngradeService == null) {
            return titleBudget;
        }
        try {
            return aiProviderDowngradeService.aiTitleTotalTimeout();
        } catch (RuntimeException exception) {
            log.warn("ai_title_budget_config_unavailable message={}", exception.getMessage());
            return titleBudget;
        }
    }

    public AiTitlePrompt buildPrompt(String stylePrompt, String rawContent) {
        return buildPrompt(stylePrompt, rawContent, titleFormatPrompt());
    }

    public AiTitlePrompt buildPrompt(String stylePrompt, String rawContent, String titleFormatPrompt) {
        return new AiTitlePrompt(titleFormatPrompt, stylePrompt, rawContent);
    }

    public String cleanTitle(String rawTitle) {
        if (rawTitle == null) {
            return "";
        }
        String cleaned = rawTitle
                .replace("```text", "")
                .replace("```markdown", "")
                .replace("```json", "")
                .replace("```", "")
                .replace('\r', '\n')
                .strip();
        cleaned = stripReasoningBlocks(cleaned);
        if (cleaned == null) {
            return "";
        }
        // JSON may span several lines, so try it on the whole answer before picking a line.
        // Titles like "[独家] …" are not JSON and fall through to line selection.
        String title = jsonTitle(cleaned);
        if (title == null) {
            title = firstTitleLine(cleaned);
        }
        title = stripMatchingQuotes(title.strip());
        int codePoints = title.codePointCount(0, title.length());
        // A single line far beyond any card title is almost certainly leaked reasoning or prose.
        if (codePoints > MAX_TITLE_CODE_POINTS * 2) {
            return "";
        }
        return truncate(CardTextSanitizer.sanitize(title), MAX_TITLE_CODE_POINTS);
    }

    /**
     * Removes inline reasoning blocks emitted by some reasoning models behind compatible gateways.
     * Returns null when the whole output is reasoning (an unclosed opening tag).
     */
    private String stripReasoningBlocks(String value) {
        String current = REASONING_BLOCK.matcher(value).replaceAll("\n").strip();
        Matcher dangling = REASONING_CLOSE.matcher(current);
        int lastClose = -1;
        while (dangling.find()) {
            lastClose = dangling.end();
        }
        if (lastClose >= 0) {
            current = current.substring(lastClose).strip();
        }
        if (REASONING_OPEN.matcher(current).find()) {
            return null;
        }
        return current;
    }

    private String firstTitleLine(String value) {
        List<String> lines = value.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
        for (String line : lines) {
            String labeled = stripTitleLabel(line);
            if (!labeled.equals(line) && StringUtils.hasText(labeled)) {
                return labeled;
            }
        }
        String fallback = "";
        for (String line : lines) {
            String candidate = stripTitleLabel(removePrefix(line, "- "));
            if (!StringUtils.hasText(candidate) || endsWithColon(candidate)) {
                continue;
            }
            if (!PREAMBLE_LINE.matcher(candidate).find()) {
                return candidate;
            }
            if (fallback.isEmpty()) {
                fallback = candidate;
            }
        }
        // Every line looked like an opener: a lone "好的设计胜过一切" is still a usable title.
        return fallback;
    }

    private String stripTitleLabel(String line) {
        Matcher matcher = TITLE_LABEL.matcher(line);
        return matcher.find() ? line.substring(matcher.end()).strip() : line;
    }

    private boolean endsWithColon(String line) {
        return line.endsWith("：") || line.endsWith(":");
    }

    /**
     * Returns the title from a JSON answer such as {"title":"…"} or [{"title":"…"}], or null when the
     * value is not JSON. Returns "" when it is JSON without a usable title.
     */
    private String jsonTitle(String value) {
        if (!value.startsWith("{") && !value.startsWith("[")) {
            return null;
        }
        JsonNode node;
        try {
            node = JSON.readTree(value);
        } catch (JsonProcessingException exception) {
            return null;
        }
        if (node.isArray() && !node.isEmpty()) {
            node = node.get(0);
        }
        JsonNode title = node.path("title");
        return title.isTextual() ? title.asText().replace('\n', ' ').strip() : "";
    }

    private String truncate(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end).stripTrailing();
    }

    private PreviewMetadata withTitle(PreviewMetadata metadata, String title) {
        return new PreviewMetadata(
                metadata.sourceUrl(),
                metadata.canonicalUrl(),
                metadata.providerId(),
                title,
                metadata.description(),
                metadata.siteName(),
                metadata.thumbnailUrl(),
                metadata.imageWidth(),
                metadata.imageHeight(),
                metadata.contentType(),
                metadata.rawContent()
        );
    }

    private String removePrefix(String value, String prefix) {
        return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))
                ? value.substring(prefix.length()).stripLeading()
                : value;
    }

    private String stripMatchingQuotes(String value) {
        String current = value;
        while (current.length() >= 2 && isQuote(current.charAt(0)) && matchingQuote(current.charAt(0)) == current.charAt(current.length() - 1)) {
            current = current.substring(1, current.length() - 1).strip();
        }
        return current;
    }

    private boolean isQuote(char value) {
        return value == '"' || value == '\'' || value == '`' || value == '“' || value == '‘';
    }

    private char matchingQuote(char value) {
        return switch (value) {
            case '“' -> '”';
            case '‘' -> '’';
            default -> value;
        };
    }


    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String titleFormatPrompt() {
        return aiTitleConfigService == null ? DEFAULT_TITLE_FORMAT_PROMPT : aiTitleConfigService.titleFormatPrompt();
    }

    private void recordAiProviderSuccess(AiProviderRecord provider) {
        if (aiProviderDowngradeService != null) {
            aiProviderDowngradeService.recordSuccess(provider);
        }
    }

    private void recordAiProviderFailure(AiProviderRecord provider, long durationMs, Throwable exception) {
        if (aiProviderDowngradeService != null) {
            aiProviderDowngradeService.recordFailure(provider, OPERATION_AI_TITLE, durationMs, exception);
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public record StylePrompt(String style, String prompt, String titleFormatPrompt, String promptHash) {
    }

    private record FreestyleSelection(StylePrompt stylePrompt, long expiresAtMillis, long pendingStartedAtMillis) {
        private boolean isPending() {
            return expiresAtMillis == FREESTYLE_PENDING_EXPIRES_AT_MILLIS;
        }

        private boolean isExpired(long now) {
            return !isPending() && expiresAtMillis <= now;
        }

        private boolean isRemovable(long now) {
            return isExpired(now)
                    || (isPending() && pendingStartedAtMillis + FREESTYLE_PENDING_CLEANUP_MILLIS <= now);
        }
    }

    public record StyledMetadataResult(
            Optional<PreviewMetadata> metadata,
            List<String> providerNames,
            long durationMs,
            OpenAiCompatibleTextClient.TokenUsage usage
    ) {
        public StyledMetadataResult(Optional<PreviewMetadata> metadata, List<String> providerNames, long durationMs) {
            this(metadata, providerNames, durationMs, OpenAiCompatibleTextClient.TokenUsage.UNKNOWN);
        }

        private static StyledMetadataResult empty() {
            return new StyledMetadataResult(Optional.empty(), List.of(), 0);
        }
    }

    private static final class AiAttemptStats {
        private final java.util.LinkedHashSet<String> providerNames = new java.util.LinkedHashSet<>();
        private long durationMs;
        private OpenAiCompatibleTextClient.TokenUsage usage = OpenAiCompatibleTextClient.TokenUsage.UNKNOWN;

        private void record(AiProviderRecord provider, long durationMs, OpenAiCompatibleTextClient.TokenUsage attemptUsage) {
            record(provider, durationMs);
            usage = usage.plus(attemptUsage);
        }

        private void record(AiProviderRecord provider, long durationMs) {
            if (provider != null && StringUtils.hasText(provider.getName())) {
                providerNames.add(provider.getName().strip());
            }
            if (durationMs > 0) {
                this.durationMs += durationMs;
            }
        }

        private StyledMetadataResult result(Optional<PreviewMetadata> metadata) {
            return new StyledMetadataResult(metadata, List.copyOf(providerNames), durationMs, usage);
        }
    }
}
