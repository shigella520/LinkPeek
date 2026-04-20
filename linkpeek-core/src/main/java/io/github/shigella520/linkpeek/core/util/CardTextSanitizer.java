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
        return isEmojiFormattingCodePoint(codePoint) || isUnsupportedSymbolBlock(Character.UnicodeBlock.of(codePoint));
    }

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
