package stirling.software.officeconvert.extract;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.COSObjectable;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.util.Matrix;

final class StreamRunner {

    interface Host {
        PDGraphicsState state();

        Deque<PDGraphicsState> save();

        void restore(Deque<PDGraphicsState> stack);

        void operator(Operator operator, List<COSBase> operands) throws IOException;
    }

    interface Tokens {
        Object next() throws IOException;
    }

    private static final String NO_PAGE = "No current page, call #processChildStream(PDContentStream, PDPage) instead";

    private final Host host;
    private final ParsedStreams parsed;
    private PDPage page;
    private PDResources resources;
    private Matrix initialMatrix;
    private boolean colors;

    StreamRunner(Host host, ParsedStreams parsed) {
        this.host = host;
        this.parsed = parsed;
    }

    PDPage page() {
        return page;
    }

    PDResources resources() {
        return resources;
    }

    Matrix initialMatrix() {
        return initialMatrix;
    }

    boolean colors() {
        return colors;
    }

    void processPage(PDPage target) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        page = target;
        Deque<PDGraphicsState> stack = new ArrayDeque<>();
        stack.push(new PDGraphicsState(target.getCropBox()));
        host.restore(stack);
        resources = null;
        initialMatrix = target.getMatrix();
        if (target.hasContents()) {
            processStream(target);
        }
    }

    void showForm(PDFormXObject form) throws IOException {
        if (page == null) {
            throw new IllegalStateException(NO_PAGE);
        }
        if (form.getCOSObject().getLength() > 0) {
            processStream(form);
        }
    }

    void processTransparencyGroup(PDTransparencyGroup group) throws IOException {
        if (page == null) {
            throw new IllegalStateException(NO_PAGE);
        }
        PDResources parent = pushResources(group);
        Deque<PDGraphicsState> saved = host.save();
        Matrix parentMatrix = initialMatrix;
        PDGraphicsState gs = host.state();
        initialMatrix = gs.getCurrentTransformationMatrix().clone();
        gs.getCurrentTransformationMatrix().concatenate(group.getMatrix());
        gs.setBlendMode(BlendMode.NORMAL);
        gs.setAlphaConstant(1.0);
        gs.setNonStrokeAlphaConstant(1.0);
        gs.setSoftMask(null);
        clipToRect(group.getBBox());
        try {
            processOperators(group);
        } finally {
            initialMatrix = parentMatrix;
            host.restore(saved);
            resources = parent;
        }
    }

    void processAnnotation(PDAnnotation annotation, PDAppearanceStream appearance) throws IOException {
        PDRectangle bbox = appearance.getBBox();
        PDRectangle rect = annotation.getRectangle();
        if (rect == null || !(rect.getWidth() > 0) || !(rect.getHeight() > 0) || bbox == null
                || !(bbox.getWidth() > 0) || !(bbox.getHeight() > 0)) {
            return;
        }
        Matrix matrix = appearance.getMatrix();
        Rectangle2D transformed = bbox.transform(matrix).getBounds2D();
        if (transformed.isEmpty()) {
            return;
        }
        Matrix a = Matrix.getTranslateInstance(rect.getLowerLeftX(), rect.getLowerLeftY());
        a.scale((float) (rect.getWidth() / transformed.getWidth()), (float) (rect.getHeight() / transformed.getHeight()));
        a.translate((float) -transformed.getX(), (float) -transformed.getY());
        Matrix aa = Matrix.concatenate(a, matrix);
        PDResources parent = pushResources(appearance);
        Deque<PDGraphicsState> saved = host.save();
        host.state().setCurrentTransformationMatrix(aa);
        clipToRect(bbox);
        initialMatrix = aa.clone();
        try {
            processOperators(appearance);
        } finally {
            host.restore(saved);
            resources = parent;
        }
    }

    private void processStream(PDContentStream stream) throws IOException {
        PDResources parent = pushResources(stream);
        Deque<PDGraphicsState> saved = host.save();
        Matrix parentMatrix = initialMatrix;
        PDGraphicsState gs = host.state();
        gs.getCurrentTransformationMatrix().concatenate(stream.getMatrix());
        initialMatrix = gs.getCurrentTransformationMatrix().clone();
        clipToRect(stream.getBBox());
        try {
            processOperators(stream);
        } finally {
            initialMatrix = parentMatrix;
            host.restore(saved);
            resources = parent;
        }
    }

    private PDResources pushResources(PDContentStream stream) {
        PDResources parent = resources;
        PDResources own = stream.getResources();
        if (own != null) {
            resources = own;
        } else if (resources == null) {
            resources = page.getResources();
            if (resources == null) {
                resources = new PDResources();
            }
        }
        return parent;
    }

    private void clipToRect(PDRectangle rect) {
        if (rect != null) {
            PDGraphicsState gs = host.state();
            gs.intersectClippingPath(rect.transform(gs.getCurrentTransformationMatrix()));
        }
    }

    private void processOperators(PDContentStream stream) throws IOException {
        COSBase key = parsed != null && stream instanceof COSObjectable o ? o.getCOSObject() : null;
        Object[] recorded = parsed == null ? null : parsed.take(key);
        Tokens tokens;
        Recording recording = null;
        if (recorded != null) {
            int[] at = {0};
            tokens = () -> at[0] < recorded.length ? recorded[at[0]++] : null;
        } else {
            Tokens parser = ContentTokens.open(stream);
            if (key != null) {
                recording = new Recording(parser);
                tokens = recording;
            } else {
                tokens = parser;
            }
        }
        List<COSBase> arguments = new ArrayList<>();
        Object token = tokens.next();
        boolean first = true;
        boolean parentColors = colors;
        colors = true;
        if (stream instanceof PDTilingPattern tiling && tiling.getPaintType() == PDTilingPattern.PAINT_UNCOLORED) {
            colors = false;
        }
        try {
            while (token != null) {
                if (token instanceof Operator operator) {
                    if (first && stream instanceof PDType3CharProc
                            && OperatorName.TYPE3_D1.equals(operator.getName())) {
                        colors = false;
                    }
                    first = false;
                    host.operator(operator, arguments);
                    arguments.clear();
                } else {
                    arguments.add((COSBase) token);
                }
                token = tokens.next();
            }
            if (recording != null) {
                recording.keep(parsed, key, !(stream instanceof PDPage));
            }
        } finally {
            colors = parentColors;
        }
    }

    private static final class Recording implements Tokens {
        private final Tokens parser;
        private List<Object> tokens = new ArrayList<>();
        private boolean inline;

        Recording(Tokens parser) {
            this.parser = parser;
        }

        @Override
        public Object next() throws IOException {
            Object token = parser.next();
            if (tokens != null && token != null) {
                if (tokens.size() >= ParsedStreams.MAX_TOKENS) {
                    tokens = null;
                } else {
                    tokens.add(token);
                    inline |= token instanceof Operator op && OperatorName.BEGIN_INLINE_IMAGE.equals(op.getName());
                }
            }
            return token;
        }

        void keep(ParsedStreams parsed, COSBase key, boolean reusable) {
            if (tokens != null && !tokens.isEmpty()) {
                parsed.put(key, tokens.toArray(), reusable && !inline);
            }
        }
    }
}
