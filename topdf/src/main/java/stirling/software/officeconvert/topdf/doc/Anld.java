package stirling.software.officeconvert.topdf.doc;

import java.util.List;

record Anld(int level, String format, String text, int start, int font, boolean bold, boolean italic,
        int indent) {

    static final int SIZE = 84;

    private static final String[] FORMATS = {"decimal", "upperRoman", "lowerRoman", "upperLetter", "lowerLetter",
        "ordinal"};

    static Anld of(List<Sprm> sprms) {
        Sprm lvl = Sprm.find(sprms, 0x240D);
        Sprm anld = Sprm.find(sprms, 0xC63E);
        if (lvl == null || anld == null || anld.length() < 1 + SIZE) {
            return null;
        }
        int level = lvl.u8();
        if (level < 1 || level > 11) {
            return null;
        }
        byte[] d = anld.data();
        int at = anld.at() + 1;
        int nfc = d[at] & 0xFF;
        int before = Math.min(32, d[at + 1] & 0xFF);
        int after = Math.max(before, Math.min(32, d[at + 2] & 0xFF));
        int flags = d[at + 4] & 0xFF;
        int start = Math.max(0, Math.min(32767, (short) Sprm.u16(d, at + 10)));
        int indent = Math.max(0, Math.min(31680, (short) Sprm.u16(d, at + 12)));
        StringBuilder chars = new StringBuilder();
        for (int i = 0; i < after; i++) {
            int ch = Sprm.u16(d, at + 20 + 2 * i);
            chars.append(ch >= 0x20 ? (char) ch : ' ');
        }
        boolean bullet = level == 11 || nfc == 23;
        String text;
        if (bullet) {
            text = chars.length() > 0 ? chars.substring(0, 1) : "\u2022";
        } else {
            text = chars.substring(0, before) + "%1" + chars.substring(before);
        }
        String format = bullet ? "bullet" : nfc == 255 ? "none" : nfc < FORMATS.length ? FORMATS[nfc] : "decimal";
        return new Anld(level, format, text, start == 0 ? 1 : start, Sprm.u16(d, at + 6), (flags & 0x08) != 0,
                (flags & 0x10) != 0, indent);
    }
}
