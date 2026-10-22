package stirling.software.officeconvert.extract;

import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorProcessor;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.util.Matrix;

final class ClipOperators {

    private static final String[] ENDS = {"n", "S", "s", "f", "F", "f*", "B", "B*", "b", "b*"};

    private ClipOperators() {}

    static List<OperatorProcessor> all(PDFStreamEngine engine, ClipPath clip) {
        List<OperatorProcessor> out = new ArrayList<>();
        out.add(new Step(engine, "m", clip, (c, p) -> c.moveTo(p[0], p[1]), 2));
        out.add(new Step(engine, "l", clip, (c, p) -> c.lineTo(p[0], p[1]), 2));
        out.add(new Step(engine, "c", clip, (c, p) -> c.curveTo(p[0], p[1], p[2], p[3], p[4], p[5]), 6));
        out.add(new Step(engine, "v", clip, (c, p) -> c.curveFromCurrent(p[0], p[1], p[2], p[3]), 4));
        out.add(new Step(engine, "y", clip, (c, p) -> c.curveTo(p[0], p[1], p[2], p[3], p[2], p[3]), 4));
        out.add(new Rectangle(engine, clip));
        out.add(new Step(engine, "h", clip, (c, p) -> c.close(), 0));
        out.add(new Step(engine, "W", clip, (c, p) -> c.clipOnEnd(Path2D.WIND_NON_ZERO), 0));
        out.add(new Step(engine, "W*", clip, (c, p) -> c.clipOnEnd(Path2D.WIND_EVEN_ODD), 0));
        for (String end : ENDS) {
            out.add(new End(engine, end, clip));
        }
        return out;
    }

    private interface Action {
        void apply(ClipPath clip, float[] points);
    }

    private static float[] points(PDFStreamEngine engine, List<COSBase> operands, int count) {
        if (operands.size() < count) {
            return null;
        }
        Matrix ctm = engine.getGraphicsState().getCurrentTransformationMatrix();
        float[] out = new float[count];
        for (int i = 0; i + 1 < count; i += 2) {
            if (!(operands.get(i) instanceof COSNumber x) || !(operands.get(i + 1) instanceof COSNumber y)) {
                return null;
            }
            Point2D.Float p = ctm.transformPoint(x.floatValue(), y.floatValue());
            out[i] = p.x;
            out[i + 1] = p.y;
        }
        return out;
    }

    private static final class Step extends OperatorProcessor {
        private final String name;
        private final ClipPath clip;
        private final Action action;
        private final int count;

        Step(PDFStreamEngine engine, String name, ClipPath clip, Action action, int count) {
            super(engine);
            this.name = name;
            this.clip = clip;
            this.action = action;
            this.count = count;
        }

        @Override
        public void process(Operator operator, List<COSBase> operands) {
            float[] p = points(getContext(), operands, count);
            if (p != null) {
                action.apply(clip, p);
            }
        }

        @Override
        public String getName() {
            return name;
        }
    }

    private static final class Rectangle extends OperatorProcessor {
        private final ClipPath clip;

        Rectangle(PDFStreamEngine engine, ClipPath clip) {
            super(engine);
            this.clip = clip;
        }

        @Override
        public void process(Operator operator, List<COSBase> operands) {
            if (operands.size() < 4 || !(operands.get(0) instanceof COSNumber x) || !(operands.get(1) instanceof COSNumber y)
                    || !(operands.get(2) instanceof COSNumber w) || !(operands.get(3) instanceof COSNumber h)) {
                return;
            }
            Matrix ctm = getContext().getGraphicsState().getCurrentTransformationMatrix();
            float x0 = x.floatValue();
            float y0 = y.floatValue();
            float x1 = x0 + w.floatValue();
            float y1 = y0 + h.floatValue();
            Point2D.Float a = ctm.transformPoint(x0, y0);
            Point2D.Float b = ctm.transformPoint(x1, y0);
            Point2D.Float c = ctm.transformPoint(x1, y1);
            Point2D.Float d = ctm.transformPoint(x0, y1);
            clip.rectangle(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y);
        }

        @Override
        public String getName() {
            return "re";
        }
    }

    private static final class End extends OperatorProcessor {
        private final String name;
        private final ClipPath clip;

        End(PDFStreamEngine engine, String name, ClipPath clip) {
            super(engine);
            this.name = name;
            this.clip = clip;
        }

        @Override
        public void process(Operator operator, List<COSBase> operands) {
            clip.end(getContext().getGraphicsState());
        }

        @Override
        public String getName() {
            return name;
        }
    }
}
