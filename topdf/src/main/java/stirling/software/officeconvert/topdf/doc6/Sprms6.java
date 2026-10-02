package stirling.software.officeconvert.topdf.doc6;

import java.io.ByteArrayOutputStream;

/** Word 6.0/95 property modifiers (one-byte sprm codes) rewritten as their Word 97 equivalents, operands converted
 * where the structure changed (borders, autonumbering, table cell definitions); the few with no equivalent are
 * left out. */
final class Sprms6 {

    private static final int VAR = -1;

    private static final int VAR2 = -2;

    private static final int[] LENGTH = new int[256];

    private static final int[] TARGET = new int[256];

    static {
        java.util.Arrays.fill(LENGTH, 0);
        int[][] table = {
            {2, 2, 0x4600}, {3, VAR, 0}, {4, 1, 0x2602}, {5, 1, 0x2403}, {6, 1, 0x2404}, {7, 1, 0x2405},
            {8, 1, 0x2406}, {9, 1, 0x2407}, {10, 1, 0x2408}, {11, 1, 0x2409}, {12, VAR, 0xC63E}, {13, 1, 0x240D},
            {14, 1, 0x240C}, {15, VAR, 0xC60D}, {16, 2, 0x840E}, {17, 2, 0x840F}, {18, 2, 0x4610}, {19, 2, 0x8411},
            {20, 4, 0x6412}, {21, 2, 0xA413}, {22, 2, 0xA414}, {23, VAR, 0xC615}, {24, 1, 0x2416}, {25, 1, 0x2417},
            {26, 2, 0x8418}, {27, 2, 0x8419}, {28, 2, 0x841A}, {29, 1, 0x261B}, {30, 2, 0}, {31, 2, 0},
            {32, 2, 0}, {33, 2, 0}, {34, 2, 0}, {35, 2, 0}, {36, 2, 0x4622}, {37, 1, 0x2423}, {38, 2, 0x6424},
            {39, 2, 0x6425}, {40, 2, 0x6426}, {41, 2, 0x6427}, {42, 2, 0x6428}, {43, 2, 0x6629}, {44, 1, 0x242A},
            {45, 2, 0x442B}, {46, 2, 0x442C}, {47, 2, 0x442D}, {48, 2, 0x842E}, {49, 2, 0x842F}, {50, 1, 0x2430},
            {51, 1, 0x2431}, {52, 0, 0}, {64, VAR, 0}, {65, 1, 0x0800}, {66, 1, 0x0801}, {67, 1, 0x0802},
            {68, VAR, 0x6A03}, {69, 2, 0x4804}, {70, 4, 0x6805}, {71, 1, 0x0806}, {72, 2, 0}, {73, 3, 0},
            {74, VAR, 0x6A09}, {75, 1, 0x080A}, {77, VAR, 0}, {79, VAR, 0}, {80, 2, 0x4A30}, {81, VAR, 0},
            {82, VAR, 0}, {83, 0, 0}, {85, 1, 0x0835}, {86, 1, 0x0836}, {87, 1, 0x0837}, {88, 1, 0x0838},
            {89, 1, 0x0839}, {90, 1, 0x083A}, {91, 1, 0x083B}, {92, 1, 0x083C}, {93, 2, 0x4A4F}, {94, 1, 0x2A3E},
            {95, 3, 0xEA3F}, {96, 2, 0x8840}, {97, 2, 0x4A41}, {98, 1, 0x2A42}, {99, 2, 0x4A43}, {100, 1, 0x2A44},
            {101, 2, 0x4845}, {102, 1, 0x2A46}, {103, VAR, 0}, {104, 1, 0x2A48}, {105, VAR, 0}, {106, VAR, 0},
            {107, 2, 0x484B}, {108, VAR, 0}, {109, 2, 0x4A4D}, {110, 2, 0}, {111, VAR, 0}, {112, VAR, 0},
            {113, VAR, 0}, {114, VAR, 0}, {115, VAR, 0}, {116, VAR, 0}, {117, 1, 0x0855}, {118, 1, 0x0856},
            {119, 1, 0x2E00}, {120, VAR, 0}, {121, 2, 0}, {122, 2, 0}, {123, 2, 0}, {124, 2, 0},
            {131, 1, 0x3000}, {132, 1, 0x3001}, {133, VAR, 0xD202}, {136, 3, 0xF203}, {137, 3, 0xF204},
            {138, 1, 0x3005}, {139, 1, 0x3006}, {140, 2, 0x5007}, {141, 2, 0x5008}, {142, 1, 0x3009},
            {143, 1, 0x300A}, {144, 2, 0x500B}, {145, 2, 0x900C}, {146, 1, 0x300D}, {147, 1, 0x300E},
            {148, 2, 0xB00F}, {149, 2, 0xB010}, {150, 1, 0x3011}, {151, 1, 0x3012}, {152, 1, 0x3013},
            {153, 1, 0x3014}, {154, 2, 0x5015}, {155, 2, 0x9016}, {156, 2, 0xB017}, {157, 2, 0xB018},
            {158, 1, 0x3019}, {159, 1, 0x301A}, {160, 2, 0x501B}, {161, 2, 0x501C}, {162, 1, 0x301D},
            {163, 0, 0}, {164, 2, 0xB01F}, {165, 2, 0xB020}, {166, 2, 0xB021}, {167, 2, 0xB022},
            {168, 2, 0x9023}, {169, 2, 0x9024}, {170, 2, 0xB025}, {171, 2, 0x5026}, {179, VAR, 0}, {181, VAR, 0},
            {182, 2, 0x5400}, {183, 2, 0x9601}, {184, 2, 0x9602}, {185, 1, 0x3403}, {186, 1, 0x3404},
            {187, 12, 0xD605}, {188, VAR2, 0xD608}, {189, 2, 0x9407}, {190, VAR2, 0xD608}, {191, VAR, 0xD609},
            {192, 4, 0x740A}, {193, 5, 0xD620}, {194, 4, 0x7621}, {195, 2, 0x5622}, {196, 4, 0x7623},
            {197, 2, 0x5624}, {198, 2, 0x5625}, {199, 5, 0}, {200, 4, 0x7627}, {207, VAR, 0}};
        java.util.Arrays.fill(LENGTH, Integer.MIN_VALUE);
        for (int[] row : table) {
            LENGTH[row[0]] = row[1];
            TARGET[row[0]] = row[2];
        }
    }

    private Sprms6() {}

    /** The Word 97 grpprl for a Word 6 one; stops at an unknown sprm, whose length cannot be known. */
    static byte[] translate(byte[] g, int from, int to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, (to - from) * 2));
        int i = from;
        int end = Math.min(to, g.length);
        while (i < end) {
            int op = g[i] & 0xFF;
            int len = LENGTH[op];
            int at = i + 1;
            if (len == Integer.MIN_VALUE) {
                break;
            }
            if (op == 23 && at < end && (g[at] & 0xFF) == 255) {
                int del = at + 1 < end ? g[at + 1] & 0xFF : 0;
                int ins = at + 2 + 4 * del < end ? g[at + 2 + 4 * del] & 0xFF : 0;
                len = 2 + 4 * del + 3 * ins;
                at++;
            } else if (len == VAR) {
                if (at >= end) {
                    break;
                }
                len = (g[at] & 0xFF) + (op == 3 ? 3 : op == 191 ? 1 : op == 120 ? 12 : 0);
                at++;
            } else if (len == VAR2) {
                if (at + 1 >= end) {
                    break;
                }
                len = Math.max(0, ((g[at] & 0xFF) | (g[at + 1] & 0xFF) << 8) - 1);
                at += 2;
            }
            if (at + len > end) {
                break;
            }
            write(out, op, g, at, len);
            i = at + len;
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, int op, byte[] g, int at, int len) {
        int target = TARGET[op];
        if (target == 0) {
            return;
        }
        switch (op) {
            case 12 -> variable(out, target, Structures.anld(g, at, len));
            case 133 -> variable(out, target, Structures.olst(g, at, len));
            case 15 -> {
                op(out, target);
                out.write(len);
                out.write(g, at, len);
            }
            case 23 -> {
                op(out, target);
                out.write(g, at - 1, len + 1);
            }
            case 38, 39, 40, 41, 42, 43 -> {
                op(out, target);
                out.writeBytes(Structures.brc(g, at));
            }
            case 93 -> {
                for (int code : new int[] {0x4A4F, 0x4A50, 0x4A51}) {
                    op(out, code);
                    out.write(g, at, 2);
                }
            }
            case 68 -> {
                if (len >= 4) {
                    op(out, target);
                    out.write(g, at, 4);
                }
            }
            case 74 -> {
                if (len >= 3) {
                    op(out, target);
                    out.write(g[at]);
                    out.write(g[at + 1]);
                    out.write(g[at + 2]);
                    out.write(0);
                }
            }
            case 187 -> {
                op(out, target);
                out.write(24);
                for (int k = 0; k < 6; k++) {
                    out.writeBytes(Structures.brc(g, at + 2 * k));
                }
            }
            case 188, 190 -> {
                byte[] t = Structures.defTable(g, at, len, op == 188);
                op(out, target);
                out.write((t.length + 1) & 0xFF);
                out.write((t.length + 1) >> 8 & 0xFF);
                out.writeBytes(t);
            }
            case 191 -> variable(out, target, java.util.Arrays.copyOfRange(g, at, at + len));
            case 193 -> {
                op(out, target);
                out.write(7);
                out.write(g[at]);
                out.write(g[at + 1]);
                out.write(g[at + 2]);
                out.writeBytes(Structures.brc(g, at + 3));
            }
            default -> {
                op(out, target);
                out.write(g, at, len);
            }
        }
    }

    private static void variable(ByteArrayOutputStream out, int target, byte[] data) {
        if (data == null || data.length > 255) {
            return;
        }
        op(out, target);
        out.write(data.length);
        out.writeBytes(data);
    }

    private static void op(ByteArrayOutputStream out, int target) {
        out.write(target & 0xFF);
        out.write(target >> 8 & 0xFF);
    }
}
