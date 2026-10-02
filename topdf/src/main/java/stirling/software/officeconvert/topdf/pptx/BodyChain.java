package stirling.software.officeconvert.topdf.pptx;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.apache.poi.ooxml.util.POIXMLUnits;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.util.Units;
import org.apache.poi.xslf.model.TextBodyPropertyFetcher;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextBodyProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.STTextWrappingType;

record BodyChain(List<CTTextBodyProperties> levels, RuntimeException end) {

    static BodyChain of(XSLFTextShape shape) {
        if (shape instanceof XSLFTableCell) {
            return null;
        }
        List<CTTextBodyProperties> seen = new ArrayList<>();
        RuntimeException end = null;
        try {
            shape.fetchShapeProperty(new TextBodyPropertyFetcher<Void>() {
                @Override
                public boolean fetch(CTTextBodyProperties props) {
                    seen.add(props);
                    return false;
                }
            });
        } catch (RuntimeException e) {
            end = e;
        }
        return new BodyChain(List.copyOf(seen), end);
    }

    double leftInset() {
        Double v = find((props, val) -> {
            if (props.isSetLIns()) {
                val.accept(Units.toPoints(POIXMLUnits.parseLength(props.xgetLIns())));
            }
        });
        return v == null ? 7.2 : v;
    }

    double topInset() {
        Double v = find((props, val) -> {
            if (props.isSetTIns()) {
                val.accept(Units.toPoints(POIXMLUnits.parseLength(props.xgetTIns())));
            }
        });
        return v == null ? 3.6 : v;
    }

    double rightInset() {
        Double v = find((props, val) -> {
            if (props.isSetRIns()) {
                val.accept(Units.toPoints(POIXMLUnits.parseLength(props.xgetRIns())));
            }
        });
        return v == null ? 7.2 : v;
    }

    double bottomInset() {
        Double v = find((props, val) -> {
            if (props.isSetBIns()) {
                val.accept(Units.toPoints(POIXMLUnits.parseLength(props.xgetBIns())));
            }
        });
        return v == null ? 3.6 : v;
    }

    VerticalAlignment verticalAlignment() {
        VerticalAlignment v = find((props, val) -> {
            if (props.isSetAnchor()) {
                val.accept(VerticalAlignment.values()[props.getAnchor().intValue() - 1]);
            }
        });
        return v == null ? VerticalAlignment.TOP : v;
    }

    boolean horizontalCentered() {
        Boolean v = find((props, val) -> {
            if (props.isSetAnchorCtr()) {
                val.accept(props.getAnchorCtr());
            }
        });
        return v != null && v;
    }

    boolean wordWrap() {
        Boolean v = find((props, val) -> {
            if (props.isSetWrap()) {
                val.accept(props.getWrap() == STTextWrappingType.SQUARE);
            }
        });
        return v == null || v;
    }

    private <T> T find(BiConsumer<CTTextBodyProperties, Consumer<T>> fetcher) {
        Inherited.Found<T> found = new Inherited.Found<>();
        for (CTTextBodyProperties props : levels) {
            fetcher.accept(props, found);
            if (found.set()) {
                return found.value();
            }
        }
        if (end != null) {
            throw end;
        }
        return null;
    }
}
