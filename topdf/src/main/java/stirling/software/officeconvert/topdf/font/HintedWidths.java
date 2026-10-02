package stirling.software.officeconvert.topdf.font;

import java.util.Arrays;
import java.util.TreeMap;

/** Screen widths where Carlito's hinting rounds differently from Calibri's, so Excel-style layout keeps Calibri's. */
final class HintedWidths {

    private static final String REGULAR = ""
            + "11:61=6,65=6,73=5,e0=6,e1=6,e2=6,e3=6,e4=6,e5=6,e8=6,e9=6,ea=6,eb=6,161=5 12:44=8,48=8,49=4,"
            + "61=7,62=7,64=7,65=7,68=7,69=4,6b=6,6c=4,6e=7,6f=7,70=7,71=7,73=6,75=7,cc=4,cd=4,ce=4,cf=4,e0"
            + "=7,e1=7,e2=7,e3=7,e4=7,e5=7,e8=7,e9=7,ea=7,eb=7,f1=7,f2=7,f3=7,f4=7,f5=7,f6=7,f9=7,fa=7,fb=7"
            + ",fc=7,161=6 13:41=7,49=4,61=7,69=4,6a=4,6c=4,73=6,c0=7,c1=7,c2=7,c3=7,c4=7,c5=7,cc=4,cd=4,ce"
            + "=4,cf=4,e0=7,e1=7,e2=7,e3=7,e4=7,e5=7,161=6 15:24=7,30=7,31=7,32=7,33=7,34=7,35=7,36=7,37=7,"
            + "38=7,39=7,4d=12,55=9,65=8,69=4,6c=4,a3=7,a5=7,d9=9,da=9,db=9,dc=9,e8=8,e9=8,ea=8,eb=8,20ac=7"
            + " 16:4d=13,6d=12,72=5,73=7,161=7 19:49=6,73=8,cc=6,cd=6,ce=6,cf=6,161=8";

    private static final String ITALIC = ""
            + "15:24=7,30=7,31=7,32=7,33=7,34=7,35=7,36=7,37=7,38=7,39=7,a3=7,a5=7,20ac=7";

    private static final String BOLD = ""
            + "15:24=7,30=7,31=7,32=7,33=7,34=7,35=7,36=7,37=7,38=7,39=7,a3=7,a5=7,20ac=7";

    private static final String BOLD_ITALIC = ""
            + "15:24=7,30=7,31=7,32=7,33=7,34=7,35=7,36=7,37=7,38=7,39=7,a3=7,a5=7,20ac=7";

    private static final int[][][] CODES = new int[4][][];

    private static final int[][][] PIXELS = new int[4][][];

    static {
        String[] data = {REGULAR, ITALIC, BOLD, BOLD_ITALIC};
        for (int s = 0; s < 4; s++) {
            TreeMap<Integer, TreeMap<Integer, Integer>> rows = new TreeMap<>();
            for (String row : data[s].split(" ")) {
                int colon = row.indexOf(':');
                TreeMap<Integer, Integer> items = rows.computeIfAbsent(Integer.parseInt(row, 0, colon, 10),
                        k -> new TreeMap<>());
                for (String item : row.substring(colon + 1).split(",")) {
                    int eq = item.indexOf('=');
                    items.put(Integer.parseInt(item, 0, eq, 16), Integer.parseInt(item.substring(eq + 1)));
                }
            }
            int[][] codes = new int[rows.lastKey() + 1][];
            int[][] pixels = new int[rows.lastKey() + 1][];
            rows.forEach((ppem, items) -> {
                codes[ppem] = items.keySet().stream().mapToInt(Integer::intValue).toArray();
                pixels[ppem] = items.values().stream().mapToInt(Integer::intValue).toArray();
            });
            CODES[s] = codes;
            PIXELS[s] = pixels;
        }
    }

    private HintedWidths() {}

    static boolean applies(String requestedFamily, String family) {
        return "calibri".equals(FontLibrary.normalize(requestedFamily)) && "carlito".equals(FontLibrary.normalize(family));
    }

    // Calibri's whole-pixel advance for a Carlito face standing in for it, or -1 when Carlito's own is right
    static int calibri(FontEntry carlito, int codePoint, int ppem) {
        int style = (carlito.bold() ? 2 : 0) + (carlito.italic() ? 1 : 0);
        int[][] codes = CODES[style];
        if (ppem < 0 || ppem >= codes.length || codes[ppem] == null) {
            return -1;
        }
        int at = Arrays.binarySearch(codes[ppem], codePoint);
        return at < 0 ? -1 : PIXELS[style][ppem][at];
    }
}
