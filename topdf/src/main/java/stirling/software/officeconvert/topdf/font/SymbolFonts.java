package stirling.software.officeconvert.topdf.font;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** What Symbol, Wingdings and Webdings characters look like in Unicode, for drawing them with another font. */
final class SymbolFonts {

    /**
     * How a Unicode face draws one symbol font: per code (0x20 to 0xFF) the look-alike it draws (0 where none), and the
     * symbol font's own advance and ink box in thousandths of an em ({@code NONE} where the table has no box).
     */
    record Remap(int[] lookAlike, short[] metrics) {

        int of(int code) {
            return lookAlike[code];
        }

        /** The symbol font's advance for the code in thousandths of an em, or -1 when unknown. */
        int advance(int code) {
            return metrics == null ? -1 : metrics[code * 5];
        }

        /** The symbol font's ink box {x0, y0, x1, y1} for the code in ems (y up), or null. */
        float[] box(int code) {
            if (metrics == null || metrics[code * 5 + 1] == NONE) {
                return null;
            }
            int i = code * 5 + 1;
            return new float[] {metrics[i] / 1000f, metrics[i + 1] / 1000f, metrics[i + 2] / 1000f,
                    metrics[i + 3] / 1000f};
        }
    }

    static final short NONE = Short.MIN_VALUE;

    private static final Map<String, short[]> METRICS = metrics();

    private static final String SYMBOL = ""
            + "20:20 21:21 22:2200 23:23 24:2203 25:25 26:26 27:220B 28:28 29:29 2A:2217 2B:2B 2C:2C 2D:2212 "
            + "2E:2E 2F:2F 30:30 31:31 32:32 33:33 34:34 35:35 36:36 37:37 38:38 39:39 3A:3A 3B:3B 3C:3C 3D:3D "
            + "3E:3E 3F:3F 40:2245 41:391 42:392 43:3A7 44:394,2206 45:395 46:3A6 47:393 48:397 49:399 4A:3D1 "
            + "4B:39A 4C:39B 4D:39C 4E:39D 4F:39F 50:3A0 51:398 52:3A1 53:3A3 54:3A4 55:3A5 56:3C2 57:3A9,2126 "
            + "58:39E 59:3A8 5A:396 5B:5B 5C:2234 5D:5D 5E:22A5 5F:5F 60:203E 61:3B1 62:3B2 63:3C7 64:3B4 "
            + "65:3B5 66:3C6 67:3B3 68:3B7 69:3B9 6A:3D5 6B:3BA 6C:3BB 6D:3BC,B5 6E:3BD 6F:3BF 70:3C0 71:3B8 "
            + "72:3C1 73:3C3 74:3C4 75:3C5 76:3D6 77:3C9 78:3BE 79:3C8 7A:3B6 7B:7B 7C:7C 7D:7D 7E:223C A0:20AC "
            + "A1:3D2 A2:2032 A3:2264 A4:2044 A5:221E A6:192 A7:2663 A8:2666 A9:2665 AA:2660 AB:2194 AC:2190 "
            + "AD:2191 AE:2192 AF:2193 B0:B0 B1:B1 B2:2033 B3:2265 B4:D7 B5:221D B6:2202 B7:2022 B8:F7 B9:2260 "
            + "BA:2261 BB:2248 BC:2026 BD:23D0,7C BE:23AF,2014 BF:21B5 C0:2135 C1:2111 C2:211C C3:2118 C4:2297 "
            + "C5:2295 C6:2205 C7:2229 C8:222A C9:2283 CA:2287 CB:2284 CC:2282 CD:2286 CE:2208 CF:2209 D0:2220 "
            + "D1:2207 D2:AE D3:A9 D4:2122 D5:220F D6:221A D7:22C5 D8:AC D9:2227 DA:2228 DB:21D4 DC:21D0 "
            + "DD:21D1 DE:21D2 DF:21D3 E0:25CA E1:2329,27E8,3008 E2:AE E3:A9 E4:2122 E5:2211 E6:239B E7:239C "
            + "E8:239D E9:23A1 EA:23A2 EB:23A3 EC:23A7 ED:23A8 EE:23A9 EF:23AA F1:232A,27E9,3009 F2:222B "
            + "F3:2320 F4:23AE F5:2321 F6:239E F7:239F F8:23A0 F9:23A4 FA:23A5 FB:23A6 FC:23AB FD:23AC FE:23AD ";

    private static final String WINGDINGS = ""
            + "20:20 21:1F589,270F 22:2702 23:2701 24:1F453 25:1F56D,1F514 26:1F56E,1F4D6 27:1F56F "
            + "28:1F57F,260E 29:2706 2A:1F582,2709 2B:1F583,2709 2C:1F4EA 2D:1F4EB 2E:1F4EC 2F:1F4ED "
            + "30:1F5C0,1F4C1 31:1F5C1,1F4C2 32:1F5CE,1F4C4 33:1F5CF 34:1F5D0,2587 35:1F5C4 36:231B "
            + "37:1F5AE,2328 38:1F5B0 39:1F5B2 3A:1F5B3 3B:1F5B4 3C:1F5AB 3D:1F5AC 3E:2707 3F:270D 40:1F58E "
            + "41:270C 42:1F44C 43:1F44D 44:1F44E 45:261C 46:261E 47:261D 48:261F 49:1F590 4A:263A 4B:1F610 "
            + "4C:2639,2297 4D:1F4A3 4E:2620 4F:1F3F3,2690 50:1F3F1,2691 51:2708 52:263C 53:1F4A7,265E 54:2744 "
            + "55:1F546,271E 56:271E 57:1F548,2666 58:2720 59:2721 5A:262A 5B:262F 5C:950 5D:2638 5E:2648 "
            + "5F:2649 60:264A 61:264B 62:264C 63:264D 64:264E 65:264F 66:2650 67:2651 68:2652 69:2653 6A:1F670 "
            + "6B:1F675 6C:25CF 6D:1F53E,274D 6E:25A0 6F:25A1,25AB 70:1F790,25FB,2311 71:2751,274F 72:2752,2750 "
            + "73:2B27,2666 74:29EB,2666 75:25C6 76:2756 77:2B25,25C6 78:2327 79:2BB9 7A:2318 7B:1F3F5 "
            + "7C:1F3F6,25CF 7D:1F676,275D 7E:1F677,275E 80:24EA 81:2460,2780 82:2461,2781 83:2462,2782 "
            + "84:2463,2783 85:2464,2784 86:2465,2785 87:2466,2786 88:2467,2787 89:2468,2788 8A:2469,2789 "
            + "8B:24FF,277E 8C:2776,278A 8D:2777 8E:2778 8F:2779,278D 90:277A 91:277B 92:277C 93:277D "
            + "94:277E,2792 95:277F 96:1F662 97:1F660 98:1F661 99:1F663 9A:1F65E 9B:1F65C 9C:1F65D 9D:1F65F "
            + "9E:B7,1F311 9F:2022 A0:25AA,25A0 A1:26AA,25CB A2:1F786,25CB,26AC A3:1F788,25C9,2779 A4:25C9,229B "
            + "A5:25CE,25CD A6:1F53F,274D A7:25AA,25A0 A8:25FB,25A1 A9:1F7C2,25B2 AA:2726 AB:2605 AC:2736 "
            + "AD:2734,2738 AE:2739 AF:2735 B0:2BD0 B1:2316,2725 B2:27E1,2727 B3:2311,A4 B4:2BD1,25C7 B5:272A "
            + "B6:2730 B7:1F550 B8:1F551,25F7 B9:1F552,25F7 BA:1F553 BB:1F554 BC:1F555,2780 BD:1F556 BE:1F557 "
            + "BF:1F558,25F4 C0:1F559,25F4 C1:1F55A C2:1F55B,25F7 C3:2BB0 C4:2BB1 C5:2BB2 C6:2BB3 C7:2BB4 "
            + "C8:2BB5 C9:2BB6 CA:2BB7 CB:1F66A CC:1F66B D5:232B D6:2326 D7:2B98,25C0 D8:2B9A,27A2,27A4,25B6 "
            + "D9:2B99,25B2 DA:2B9B,25BC DB:2B88 DC:2B8A,27B2 DD:2B89 DE:2B8B DF:1F868,2190 E0:1F86A,2192 "
            + "E1:1F869,2191 E2:1F86B,2193 E3:1F86C,2196 E4:1F86D,2197 E5:1F86F,2199 E6:1F86E,2198 "
            + "E7:1F878,2190 E8:1F87A,2794,2192 E9:1F879,2191 EA:1F87B,2193 EB:1F87C,2196 EC:1F87D,2197 "
            + "ED:1F87F,2199 EE:1F87E,2198 EF:21E6 F0:21E8 F1:21E7 F2:21E9 F3:2B04 F4:21F3 F5:2B00 F6:2B01 "
            + "F7:2B03 F8:2B02 F9:1F8AC,25AD FA:1F8AD,25AB FB:1F5F6,2717,2716 FC:2714,2713 FD:1F5F7,2612 "
            + "FE:1F5F9,2611 ";

    private static final String WINGDINGS_2 = ""
            + "20:20 21:1F58A 22:1F58B 23:1F58C 24:1F58D 27:1F57E 29:1F5C5 2A:1F5C6,25FB 2B:1F5C7 2C:1F5C8 "
            + "2D:1F5C9 2F:1F5CB 30:1F5CC 31:1F5CD 33:1F5D1 34:1F5D4 35:1F5B5 36:1F5B6 37:1F5B7 38:1F5B8,2609 "
            + "39:1F5AD 3A:1F5AF 3B:1F5B1 3E:1F598 3F:1F599 40:1F59A 41:1F59B 44:1F59C 45:1F59D 46:1F59E "
            + "47:1F59F 48:1F5A0 49:1F5A1 4C:1F5A2 4D:1F5A3 4E:1F591 4F:1F5F4,2717,2715 50:1F5F8,2713,2714 "
            + "51:1F5F5,2612 52:1F5F9,2611 53:2BBD,2612 54:2BBD,2612,22A0 55:2BBE,2297 56:2BBF,2297 57:1F6C7 "
            + "59:1F671 5A:1F674 5B:1F672 5C:1F673 5D:1F67A,203D 5E:1F679,203D 5F:1F67A,203D 60:1F67B,203D "
            + "61:1F666 62:1F664 63:1F665 64:1F667 65:1F65E 66:1F65C 67:1F65D 68:1F65F 6A:24BE,2780 "
            + "6B:24CF,2781 6C:2782 6D:2463 6E:24C8 6F:24D1,2785 70:24CF,2786 71:24B7,2787 72:2788 73:2469,2789 "
            + "74:24FF,277E 75:278A,2776 76:24FF,2777 77:24FF,2778 78:1F789,2779 79:24FF,277A 7A:24FF,277B "
            + "7B:2790,277C 7C:24FF,277D 7D:24FF,277E 7E:24F4,277F 80:2A00,2299 81:2B55,1F315 82:263D 83:263E "
            + "85:2670,271D 86:1F547,271D 87:1F55C 88:1F55D 89:25F6 8A:1F55F 8B:1F55F 8C:24D8,25F5 8D:1F561 "
            + "8E:1F562 8F:25F5 90:1F564 91:1F565 92:24BE 93:1F668 94:1F669 95:B7 96:1F784 97:2981,26AB,2022 "
            + "98:25CF 99:25CB 9A:1F785,25CB 9B:1F787,2779 9C:1F789,2779 9D:2A00,2299 9E:1F518,25C9,267C "
            + "9F:1F78C A0:1F78D A1:25FE,25AA A2:2B1B,25A0 A3:25A1,2610 A4:1F791 A5:1F792 A6:1F793 "
            + "A7:1F794,2680 A9:1F795 AA:1F796 AB:1F797 AC:1F798 AD:2B29 AE:2B25,25C6 AF:25C7 B0:1F79A,25C7 "
            + "B1:1F79B B2:1F79B,25C8 B3:1F79C,25C6 B4:1F79E B5:1F79E B6:1F79E B7:1F79F,2666 B8:2B28,25CA "
            + "B9:1F7A0 BA:25D6 BB:25D7 BC:2BCA,25B0 BD:2BCB,25B0 BE:2BC0,25AA BF:2BC1,25C6 C0:2B1F "
            + "C1:2BC2,25CF C2:2B23 C3:2B22 C4:2BC3,2688 C5:2BC4,25CF C6:1F7A1,271B C7:1F7A2,271B C8:1F7A3,271B "
            + "C9:1F7A4,271B CA:1F7A5,271A CB:1F7A6,271A CC:1F7A7,271A CD:1F7A8,2A2F CE:1F7A9,2A2F CF:2715 "
            + "D0:1F7AB,2715 D1:1F7AC,2715 D2:1F7AD,2716 D3:1F7AE,2716 D4:1F7AF,272D D5:1F7B0 D6:1F7B1 "
            + "D7:1F7B2,2605 D8:1F7B3,2605 D9:1F7B4,272F DA:1F7B5,2217 DB:1F7B6,2217 DC:1F7B7,2217 "
            + "DD:1F7B8,2731 DE:1F7B9,2731 DF:1F7BA,2731 E0:2733 E1:1F7BC,2733 E2:1F7BD,274B E3:1F7BE,274B "
            + "E4:1F7BF,2738 E5:1F7C1 E6:1F7C2 E7:1F7C4,2726 E8:1F7C6,2726 E9:1F7C9,2605 EA:1F7CA,2605 EB:2736 "
            + "EC:1F7CC,2666 ED:1F7CE,2737 EE:1F7D0,2738 EF:1F7D2,2737 F0:1F7D3,2739 F1:1F7C3 F2:1F7C7,2726 "
            + "F3:272F F4:1F7CD,2736 F5:1F7D4,2745 F6:2BCC,2726 F7:2BCD F8:1F7A9,2A2F ";

    private static final String WINGDINGS_3 = ""
            + "20:20 21:2B60,2190 22:2B62,2192,279E 23:2B61,2191 24:2B63,2193 25:2B66,2196 26:2B67,2197 "
            + "27:2B69,2199 28:2B68,2198 29:2B70 2A:2B72 2B:2B71 2C:2B73 2D:2B76 2E:2B78 2F:2B7B,21DE "
            + "30:2B7D,21DF 31:2B64,27BB 32:2B65,2195 33:2B6A,2190 34:2B6C,279D 35:2B6B,2191 36:1F823 37:2B4D "
            + "38:2BA0 39:2BA1 3A:2BA2 3B:2BA3 3C:2BA4 3D:2BA5 3E:2BA6 3F:2BA7 40:2B90 41:2B91 42:2B92 43:2B93 "
            + "44:2B80 45:21F5 46:2B7E 47:2B7F 48:2B84 49:2B86 4A:2B85 4B:2B87 4C:2B8F 4D:2B8D 4E:2B8E 4F:2B8C "
            + "50:2B6E 51:2B6F 58:2BB8 59:2BB8 5A:1F8A0 5B:1F8A1 5C:1F8A2 5D:1F8A3 5E:1F8A4 5F:1F8A5 60:1F8A6 "
            + "61:1F8A7,27AA 62:1F8A8 63:1F8A9,27AB 64:1F8AA 65:1F8AB 66:1F850,2190 67:1F852,21DD 68:1F851,2191 "
            + "69:1F853,2193 6A:1F854,2196 6B:1F855,2197 6C:1F857,2199 6D:1F856,2198 6E:1F858,2194 "
            + "6F:1F859,2195 70:25B2 71:25BC 72:25B3 73:25BD 74:25C0 75:25B6 76:25C1 77:25B7 78:25E3 79:25E2 "
            + "7A:25E4 7B:25E5 7C:1F780 7D:1F782,25D7 7E:1F781 80:1F783 81:2BC5,25B4,25B2 82:2BC6,25BE,25BC "
            + "83:2BC7,25C2,25C4 84:2BC8,25B8,25B6,25BA 85:2B9C 86:2B9E 87:2B9D 88:2B9F 89:1F800 8A:1F802 "
            + "8B:1F801 8C:1F813 "
            + "8D:1F814,27B8 8E:1F816,2799 8F:1F815 90:1F817 91:1F818 92:1F81A,279F 93:1F819,2628 94:1F81B "
            + "95:1F81C 96:1F81E 97:1F81D,2666 98:1F81F,2666 99:1F800 9A:1F802 9B:1F801 9C:1F803 9D:1F804,21DC "
            + "9E:1F806,21DD 9F:1F805,2191 A0:1F807,2193 A1:1F808 A2:1F80A A3:1F809 A4:1F80B A5:1F820,2190 "
            + "A6:1F822,2192 A7:1F824,2190 A8:1F826,279E A9:1F828,21DC AA:1F82A,279E AB:1F82C,27A6 AC:1F89C "
            + "AD:1F89D AE:1F89E,22C5 AF:1F89F B0:1F82E,27A6 B1:1F830,27A6 B2:1F832,27A6 B3:1F834,25AC "
            + "B4:1F836,27A8,2794 B5:1F838 B6:1F83A B7:1F839 B8:1F83B B9:1F898,2B0C BA:1F89A,2B0C BB:1F899,2B0D "
            + "BC:1F89B,2B0D BD:1F83C BE:1F83E BF:1F83D C0:1F83F,279B C1:1F840,25D6 C2:1F842,25D7 C3:1F841,25B0 "
            + "C4:1F843 C5:1F844,25C6 C6:1F846,25C6 C7:1F845,25C6 C8:1F847,25C6 C9:2BA8,2B05 CA:2BA9,27A1 "
            + "CB:2BAA,2B05 CC:2BAB,27A6 CD:2BAC,2B06 CE:2BAD,2B06 CF:2BAE,2B07 D0:2BAF,2B07 D1:1F860 D2:1F862 "
            + "D3:1F861 D4:1F863 D5:1F864 D6:1F865 D7:1F867 D8:1F866 D9:1F870 DA:1F872 DB:1F871 DC:1F86B "
            + "DD:1F874 DE:1F875 DF:1F877 E0:1F876 E1:1F880 E2:1F882 E3:1F881 E4:1F883 E5:1F884 E6:1F885 "
            + "E7:1F887 E8:1F886 E9:1F890 EA:1F892 EB:1F891 EC:1F893 ED:1F894 EE:1F896 EF:1F895 F0:1F897 ";

    private static final String WEBDINGS = ""
            + "20:20 22:1F578,2B1F 23:1F572 26:1F396 27:1F587 28:1F5E8 29:1F5E9 2A:1F5F0 2B:1F5F1 2C:1F336 "
            + "2D:1F397 2E:1F67E 2F:1F67C 30:1F5D5 31:1F5D6 32:1F5D7 33:23F4 34:23F5 35:23F6 36:23F7 37:23EA "
            + "38:23E9 39:23EE 3A:23ED 3B:23F8 3C:2BC0,25FE 3D:26AB 3E:1F5DA 3F:1F5F3 40:1F6E0 41:1F3D7 "
            + "42:1F3D8 43:1F3D9 44:1F3DA 45:1F3DC 46:1F3DF 47:1F3DB 48:1F3DA 49:1F3D6 4A:1F3DD 4B:1F6E3 "
            + "4D:1F3D4 4E:1F441,26AE 50:1F3DE 51:1F3D5 52:1F6E4 53:1F3DF 54:1F6F3 55:1F56C 56:1F56B 57:1F782 "
            + "58:1F780 59:1F394,2665 5B:1F5EC 5C:1F67D 5D:1F5ED 5E:1F5EA 5F:1F5EB 60:2B94 63:2610,25A1 "
            + "64:2BC4,25CF 65:2617 66:1F6F1 67:1F3FD,25A0 69:1F6C8,2780 6A:1F6E9 6B:1F6F0 6C:1F7C8 6D:1F574 "
            + "6E:1F311,2B24 6F:1F6E5 71:1F5D8 72:1F5D9,2715 74:1F6F2 75:1F3D7 78:1F6C7 79:2296 7B:1F5EE "
            + "7C:23D0 7D:1F5EF 7E:1F5F2 80:1F6CA,226C 81:1F6CA,226C 82:1F6C9 83:1F6CA 85:271F 86:1F3CB "
            + "89:1F3CC 8C:1F3CD 8D:1F3CE 8F:1F5E0 90:1F6E2,25AE 92:1F3F7 94:1F5C2 95:1F5E1 96:1F5E2,27A0 "
            + "97:1F5E3 98:272F 99:1F584 9A:1F585 9C:1F586 9E:1F5BA 9F:1F5BB A0:1F575 A1:1F570 A2:1F5BD "
            + "A3:1F5BE A5:1F5D2 A6:1F5D3 A9:1F5DE AA:1F5DF AB:1F5C3 AC:1F5C2,2584 AD:1F5BC AF:1F39C B0:1F398 "
            + "B1:1F399 B4:1F39E B6:1F39F B7:1F3AC B8:1F4FD BA:1F4FE BC:1F39A BD:1F39B C1:1F5A6 C2:1F5A7 "
            + "C3:1F579 C5:1F57B C6:1F57C C7:1F836,2583 C8:1F581 C9:1F580,2586 CA:1F5A8 CB:1F5A9,220E CC:2584 "
            + "CD:1F5AA CF:1F513 D1:1F5DD D4:1F573,2582 D5:26AC,26AA D7:1F83D,2601 D8:1F327 D9:1F83D,2601 "
            + "DE:1F32C DF:1F32B E1:1F321 E2:1F6CB E3:1F6CF E4:1F37D E6:1F6CE E7:1F6CD E8:24C5,2117 "
            + "EA:1F6C6,267A EB:1F588 ED:1F5E4 EE:1F5E5 EF:1F5E6 F0:1F5E7 F1:1F6EA F2:1F43F F7:1F66C F8:1F66E "
            + "F9:1F66D FA:1F66F FB:1F5FA FD:1F30F ";

    private static final Map<String, int[][]> TABLES = new HashMap<>();

    static {
        TABLES.put("symbol", parse(SYMBOL));
        TABLES.put("wingdings", parse(WINGDINGS));
        TABLES.put("wingdings 2", parse(WINGDINGS_2));
        TABLES.put("wingdings 3", parse(WINGDINGS_3));
        TABLES.put("webdings", parse(WEBDINGS));
    }

    private SymbolFonts() {}

    static int[][] table(String family) {
        return TABLES.get(FontLibrary.normalize(family));
    }

    static boolean known(String family) {
        return family != null && TABLES.containsKey(FontLibrary.normalize(family));
    }

    // The look-alikes this program draws for a symbol font's codes; null for other fonts or a real symbol font
    static Remap remap(String family, FontProgram program) {
        String key = family == null ? null : FontLibrary.normalize(family);
        int[][] table = key == null ? null : TABLES.get(key);
        if (table == null || program.symbol()) {
            return null;
        }
        int[] out = new int[256];
        for (int code = 0x20; code < 256; code++) {
            int[] candidates = table[code];
            if (candidates == null) {
                continue;
            }
            for (int cp : candidates) {
                if (program.glyph(cp) > 0 || cp == ' ') {
                    out[code] = cp;
                    break;
                }
            }
        }
        return new Remap(out, METRICS.get(key));
    }

    private static Map<String, short[]> metrics() {
        Map<String, short[]> m = new HashMap<>();
        try (InputStream in = SymbolFonts.class.getResourceAsStream("symbol-metrics.tsv")) {
            if (in == null) {
                return Map.of();
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                short[] values = new short[256 * 5];
                Arrays.fill(values, NONE);
                for (int c = 0; c < 256; c++) {
                    values[c * 5] = -1;
                }
                for (String entry : line.substring(tab + 1).trim().split(" ")) {
                    int colon = entry.indexOf(':');
                    int code = Integer.parseInt(entry, 0, colon, 16);
                    String[] v = entry.substring(colon + 1).split(",");
                    if (code < 0 || code > 255 || v.length != 1 && v.length != 5) {
                        continue;
                    }
                    for (int i = 0; i < v.length; i++) {
                        values[code * 5 + i] = Short.parseShort(v[i]);
                    }
                }
                m.put(FontLibrary.normalize(line.substring(0, tab)), values);
            }
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        return Map.copyOf(m);
    }

    static int code(int codePoint) {
        if (codePoint >= 0x20 && codePoint <= 0xFF) {
            return codePoint;
        }
        return codePoint >= 0xF020 && codePoint <= 0xF0FF ? codePoint - 0xF000 : -1;
    }

    private static int[][] parse(String data) {
        int[][] table = new int[256][];
        for (String entry : data.trim().split(" ")) {
            int colon = entry.indexOf(':');
            String[] parts = entry.substring(colon + 1).split(",");
            int[] cps = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                cps[i] = Integer.parseInt(parts[i], 16);
            }
            table[Integer.parseInt(entry, 0, colon, 16)] = cps;
        }
        return table;
    }
}
