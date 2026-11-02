package stirling.software.officeconvert.pdfa;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.fontbox.ttf.HorizontalMetricsTable;
import org.apache.fontbox.ttf.PostScriptTable;
import org.apache.fontbox.ttf.TrueTypeFont;

final class Cff2Program extends FontProgram {

    private final Cff2 cff2;

    private final int count;

    private final String name;

    private final PostScriptTable post;

    private final HorizontalMetricsTable hmtx;

    private final int unitsPerEm;

    private final Map<Integer, Integer> own;

    private final Map<String, Integer> gids = new HashMap<>();

    Cff2Program(Cff2 cff2, TrueTypeFont ttf, Map<Integer, Integer> own) throws IOException {
        this.cff2 = cff2;
        this.count = Math.min(cff2.count(), Math.max(1, ttf.getNumberOfGlyphs()));
        this.name = ttf.getName();
        this.post = ttf.getPostScript();
        this.hmtx = ttf.getHorizontalMetrics();
        this.unitsPerEm = ttf.getUnitsPerEm() > 0 ? ttf.getUnitsPerEm() : 1000;
        this.own = own;
        for (int gid = 0; post != null && gid < count; gid++) {
            String n = post.getName(gid);
            if (n != null) {
                gids.putIfAbsent(n, gid);
            }
        }
    }

    @Override
    int count() {
        return count;
    }

    @Override
    String name() {
        return name;
    }

    @Override
    String name(int gid) {
        return post == null ? null : post.getName(gid);
    }

    @Override
    int gid(String glyph) {
        Integer g = glyph == null ? null : gids.get(glyph);
        return g == null ? 0 : g;
    }

    @Override
    GeneralPath rawPath(int gid) throws IOException {
        return cff2.path(gid);
    }

    @Override
    double rawWidth(int gid) {
        return hmtx == null ? 0 : hmtx.getAdvanceWidth(gid);
    }

    @Override
    CharStringExpansion.Result expansion(int gid) throws IOException {
        return CharStringExpansion.type2(cff2.charString(gid), cff2.globalSubrs(), cff2.localSubrs(gid));
    }

    @Override
    List<Number> matrix() {
        double k = 1.0 / unitsPerEm;
        return List.of(k, 0, 0, k, 0, 0);
    }

    @Override
    String weight() {
        return null;
    }

    @Override
    float italicAngle() {
        return post == null ? 0 : post.getItalicAngle();
    }

    @Override
    Map<Integer, Integer> own() {
        return own;
    }
}
