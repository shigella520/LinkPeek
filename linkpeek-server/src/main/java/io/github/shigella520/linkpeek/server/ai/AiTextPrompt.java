package io.github.shigella520.linkpeek.server.ai;

public record AiTextPrompt(
        String instructions,
        String prompt,
        String content
) {
    public AiTextPrompt {
        instructions = normalize(instructions);
        prompt = normalize(prompt);
        content = normalize(content);
    }

    public boolean hasInstructions() {
        return !instructions.isBlank();
    }

    public boolean hasContent() {
        return !content.isBlank();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
