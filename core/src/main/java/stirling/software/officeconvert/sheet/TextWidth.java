package stirling.software.officeconvert.sheet;

final class TextWidth {

    private static final short[] REGULAR = {
        226, 326, 401, 498, 507, 715, 682, 221, 303, 303, 498, 498, 250, 306, 252, 386,
        507, 507, 507, 507, 507, 507, 507, 507, 507, 507, 268, 268, 498, 498, 498, 463,
        894, 579, 544, 533, 615, 488, 459, 631, 623, 252, 319, 520, 420, 855, 646, 662,
        517, 673, 543, 459, 487, 642, 567, 890, 519, 487, 468, 307, 386, 307, 498, 498,
        291, 479, 525, 423, 525, 498, 305, 471, 525, 229, 239, 455, 229, 799, 525, 527,
        525, 525, 349, 391, 335, 525, 452, 715, 433, 453, 395, 314, 460, 314, 498
    };

    private static final short[] BOLD = {
        226, 326, 438, 498, 507, 729, 705, 233, 312, 312, 498, 498, 258, 306, 267, 430,
        507, 507, 507, 507, 507, 507, 507, 507, 507, 507, 276, 276, 498, 498, 498, 463,
        898, 606, 561, 529, 630, 488, 459, 637, 631, 267, 331, 547, 423, 874, 659, 676,
        532, 686, 563, 473, 495, 653, 591, 906, 551, 520, 478, 325, 430, 325, 498, 498,
        300, 494, 537, 418, 537, 503, 316, 474, 537, 246, 255, 480, 246, 813, 537, 538,
        537, 537, 355, 399, 347, 537, 473, 745, 459, 474, 397, 344, 475, 344, 498
    };

    private static final float MARGIN = 1.03f;

    private static final float SMALLEST = 8f;

    private TextWidth() {}

    static float of(String text, float size, boolean bold) {
        int ppem = Math.round(Math.max(size, SMALLEST) * 4f / 3f);
        int widest = 0;
        int line = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                widest = Math.max(widest, line);
                line = 0;
            } else {
                line += Math.max(1, Math.round(units(c, bold) * ppem / 1000f));
            }
        }
        widest = Math.max(widest, line);
        return widest * 0.75f * MARGIN;
    }

    static float ofValue(String text, float size, boolean bold) {
        int ppem = Math.round(size * 4f / 3f);
        int measured = ppem <= 9 ? (bold ? 13 : 11) : ppem == 10 ? (bold ? 15 : 13) : ppem;
        int px = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            px += Math.max(1, Math.round(units(c, bold) * measured / 1000f));
            letters += Character.isLetter(c) ? 1 : 0;
        }
        return (px * MARGIN + letters) * 0.75f + Math.max(6.75f, 0.4f * size);
    }

    static int lines(String text, float size, boolean bold, float width) {
        int total = 0;
        for (String part : text.split("\n", -1)) {
            total += Math.max(1, (int) Math.ceil(of(part, size, bold) / Math.max(1f, width)));
        }
        return total;
    }

    static float lineHeight(float size) {
        double px = Math.ceil(size * 96 / 72 * 1.2207) + 2;
        return (float) (px * 0.75);
    }

    private static int units(char c, boolean bold) {
        return c >= 32 && c <= 126 ? (bold ? BOLD : REGULAR)[c - 32] : advance(c);
    }

    private static int advance(char c) {
        if (c == '\t') {
            return 904;
        }
        if (c >= 0x1100 && (c <= 0x115F || c >= 0x2E80 && c <= 0xA4CF || c >= 0xAC00 && c <= 0xD7A3
                || c >= 0xF900 && c <= 0xFAFF || c >= 0xFF00 && c <= 0xFF60)) {
            return 1000;
        }
        return Character.isUpperCase(c) ? 620 : 520;
    }
}
