package stirling.software.officeconvert.topdf.rtf;

import java.io.ByteArrayOutputStream;

final class Picture {

    static final int MAX_BYTES = 64 << 20;

    String type;
    int picw;
    int pich;
    int goalw;
    int goalh;
    int scalex = 100;
    int scaley = 100;
    int cropl;
    int cropr;
    int cropt;
    int cropb;
    int mapMode;

    final ByteArrayOutputStream data = new ByteArrayOutputStream();

    private int nibble = -1;

    boolean truncated;

    boolean word(String word, int param) {
        switch (word) {
            case "pngblip" -> type = "png";
            case "jpegblip" -> type = "jpeg";
            case "emfblip" -> type = "emf";
            case "wmetafile" -> {
                type = "wmf";
                mapMode = param;
            }
            case "dibitmap" -> type = "dib";
            case "wbitmap", "macpict", "pmmetafile" -> type = "unsupported";
            case "picw" -> picw = param;
            case "pich" -> pich = param;
            case "picwgoal" -> goalw = param;
            case "pichgoal" -> goalh = param;
            case "picscalex" -> scalex = param;
            case "picscaley" -> scaley = param;
            case "piccropl" -> cropl = param;
            case "piccropr" -> cropr = param;
            case "piccropt" -> cropt = param;
            case "piccropb" -> cropb = param;
            default -> {
                return false;
            }
        }
        return true;
    }

    void hex(int c) {
        int v = Character.digit(c, 16);
        if (v < 0) {
            return;
        }
        if (nibble < 0) {
            nibble = v;
            return;
        }
        if (data.size() < MAX_BYTES) {
            data.write(nibble << 4 | v);
        } else {
            truncated = true;
        }
        nibble = -1;
    }

    long room() {
        return Math.max(0, MAX_BYTES - data.size());
    }
}
