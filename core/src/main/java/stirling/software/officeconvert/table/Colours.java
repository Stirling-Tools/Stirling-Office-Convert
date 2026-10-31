package stirling.software.officeconvert.table;

final class Colours {

    private Colours() {}

    static boolean isNearWhite(int rgb) {
        return red(rgb) >= 0xF5 && green(rgb) >= 0xF5 && blue(rgb) >= 0xF5;
    }

    static boolean isNearBlack(int rgb) {
        return red(rgb) < 0x30 && green(rgb) < 0x30 && blue(rgb) < 0x30;
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }
}
