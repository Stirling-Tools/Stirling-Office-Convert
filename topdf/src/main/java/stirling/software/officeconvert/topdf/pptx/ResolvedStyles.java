package stirling.software.officeconvert.topdf.pptx;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

final class ResolvedStyles {

    record Level(StyleChain chain, ParaStyle paragraph, RunStyle run) {}

    private record Shared(ParaProps paragraph, CharProps run) {}

    private record Key(StyleChain chain) {

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && chain.sameLevels(k.chain);
        }

        @Override
        public int hashCode() {
            return chain.levelsHash();
        }
    }

    private final Map<XSLFTextShape, Map<Integer, Level>> levels = new IdentityHashMap<>();

    private final Map<Key, Shared> shared = new HashMap<>();

    Level of(XSLFTextParagraph p) {
        XSLFTextShape shape = p.getParentShape();
        Map<Integer, Level> byLevel = levels.computeIfAbsent(shape, s -> new HashMap<>());
        Integer indent = p.getIndentLevel();
        Level level = byLevel.get(indent);
        if (level == null) {
            StyleChain chain = StyleChain.of(p);
            Shared s = chain.end() == null ? shared.computeIfAbsent(new Key(chain), k -> resolve(chain))
                    : resolve(chain);
            level = new Level(chain, new ParaStyle(shape, chain, s.paragraph()), new RunStyle(shape, chain, s.run()));
            byLevel.put(indent, level);
        }
        return level;
    }

    private static Shared resolve(StyleChain chain) {
        return new Shared(ParaProps.resolve(chain.paragraphs(), chain.end()),
                CharProps.resolve(chain.runs(), chain.end()));
    }
}
