package stirling.software.officeconvert.topdf.font;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final List<Map<Long, Integer>> STYLES = styles();

    private HintedWidths() {}

    static boolean applies(String requestedFamily, String family) {
        return "calibri".equals(FontLibrary.normalize(requestedFamily)) && "carlito".equals(FontLibrary.normalize(family));
    }

    // Calibri's whole-pixel advance for a Carlito face standing in for it, or -1 when Carlito's own is right
    static int calibri(FontEntry carlito, int codePoint, int ppem) {
        Integer px = STYLES.get((carlito.bold() ? 2 : 0) + (carlito.italic() ? 1 : 0)).get((long) ppem << 32 | codePoint);
        return px == null ? -1 : px;
    }

    private static List<Map<Long, Integer>> styles() {
        List<Map<Long, Integer>> out = new ArrayList<>();
        for (String data : new String[] {REGULAR, ITALIC, BOLD, BOLD_ITALIC}) {
            Map<Long, Integer> style = new HashMap<>();
            out.add(style);
            for (String row : data.split(" ")) {
                int colon = row.indexOf(':');
                long ppem = Long.parseLong(row, 0, colon, 10);
                for (String item : row.substring(colon + 1).split(",")) {
                    int eq = item.indexOf('=');
                    style.put(ppem << 32 | Integer.parseInt(item, 0, eq, 16), Integer.parseInt(item.substring(eq + 1)));
                }
            }
        }
        return out;
    }
}
