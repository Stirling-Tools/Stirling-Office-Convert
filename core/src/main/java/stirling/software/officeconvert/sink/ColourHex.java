package stirling.software.officeconvert.sink;

public final class ColourHex {

    private static final char[] UPPER = "0123456789ABCDEF".toCharArray();

    private static final char[] LOWER = "0123456789abcdef".toCharArray();

    private ColourHex() {}

    public static String upper(int rgb) {
        return digits(rgb, UPPER);
    }

    public static String lower(int rgb) {
        return digits(rgb, LOWER);
    }

    private static String digits(int rgb, char[] alphabet) {
        char[] out = new char[6];
        for (int i = 5, v = rgb; i >= 0; i--, v >>>= 4) {
            out[i] = alphabet[v & 0xF];
        }
        return new String(out);
    }
}
