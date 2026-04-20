package io.github.shigella520.linkpeek.core.util;

public final class CardTextSanitizer {
    private CardTextSanitizer() {
    }

    public static String displayTitle(String title, String fallbackTitle) {
        String cleaned = sanitize(title);
        if (!cleaned.isBlank()) {
            return cleaned;
        }
        return sanitize(fallbackTitle);
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        StringBuilder sanitized = new StringBuilder(value.length());
        boolean previousWasWhitespace = false;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);

            if (Character.isWhitespace(codePoint)) {
                previousWasWhitespace = appendCollapsedSpace(sanitized, previousWasWhitespace);
                continue;
            }
            if (Character.isISOControl(codePoint) || isUnsupportedSpecialSymbol(codePoint)) {
                continue;
            }

            sanitized.appendCodePoint(codePoint);
            previousWasWhitespace = false;
        }
        return sanitized.toString().strip();
    }

    private static boolean appendCollapsedSpace(StringBuilder target, boolean previousWasWhitespace) {
        if (target.length() == 0 || previousWasWhitespace) {
            return previousWasWhitespace;
        }
        target.append(' ');
        return true;
    }

    private static boolean isUnsupportedSpecialSymbol(int codePoint) {
        return isEmojiCodePoint(codePoint)
                || isEmojiFormattingCodePoint(codePoint)
                || isUnsupportedSymbolBlock(Character.UnicodeBlock.of(codePoint));
    }

    // Visible emoji and pictograph code points. Most can render, but title cards intentionally avoid them
    // to keep typography stable across server fonts and Java2D fallback behavior.
    private static boolean isEmojiCodePoint(int codePoint) {
        return codePoint == 0x00A9
                || codePoint == 0x00AE
                || codePoint == 0x203C
                || codePoint == 0x2049
                || codePoint == 0x2122
                || codePoint == 0x2139
                || (codePoint >= 0x2194 && codePoint <= 0x21AA)
                || (codePoint >= 0x231A && codePoint <= 0x231B)
                || codePoint == 0x2328
                || codePoint == 0x23CF
                || (codePoint >= 0x23E9 && codePoint <= 0x23F3)
                || (codePoint >= 0x23F8 && codePoint <= 0x23FA)
                || codePoint == 0x24C2
                || (codePoint >= 0x25AA && codePoint <= 0x25AB)
                || codePoint == 0x25B6
                || codePoint == 0x25C0
                || (codePoint >= 0x25FB && codePoint <= 0x25FE)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || (codePoint >= 0x2934 && codePoint <= 0x2935)
                || (codePoint >= 0x2B05 && codePoint <= 0x2B55)
                || codePoint == 0x3030
                || codePoint == 0x303D
                || codePoint == 0x3297
                || codePoint == 0x3299
                || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF);
    }

    // Emoji modifiers and joiners do not carry useful text by themselves; remove them so stripping emoji
    // bodies does not leave invisible variation selectors, keycap marks, ZWJ sequences, or tag characters.
    private static boolean isEmojiFormattingCodePoint(int codePoint) {
        return codePoint == 0x200D
                || codePoint == 0x20E3
                || codePoint == 0xFE0E
                || codePoint == 0xFE0F
                || (codePoint >= 0xE0020 && codePoint <= 0xE007F);
    }

    private static boolean isUnsupportedSymbolBlock(Character.UnicodeBlock block) {
        return block == Character.UnicodeBlock.EMOTICONS
                || block == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_PICTOGRAPHS
                || block == Character.UnicodeBlock.SUPPLEMENTAL_SYMBOLS_AND_PICTOGRAPHS
                || block == Character.UnicodeBlock.SYMBOLS_AND_PICTOGRAPHS_EXTENDED_A
                || block == Character.UnicodeBlock.TRANSPORT_AND_MAP_SYMBOLS
                || block == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS
                || block == Character.UnicodeBlock.DINGBATS
                || block == Character.UnicodeBlock.PLAYING_CARDS
                || block == Character.UnicodeBlock.MAHJONG_TILES
                || block == Character.UnicodeBlock.PRIVATE_USE_AREA
                || block == Character.UnicodeBlock.SUPPLEMENTARY_PRIVATE_USE_AREA_A
                || block == Character.UnicodeBlock.SUPPLEMENTARY_PRIVATE_USE_AREA_B;
    }
}
