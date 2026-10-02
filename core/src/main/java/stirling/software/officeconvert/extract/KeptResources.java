package stirling.software.officeconvert.extract;

import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.DefaultResourceCache;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

final class KeptResources extends DefaultResourceCache {

    @Override
    public PDFont removeFont(COSObject indirect) {
        return null;
    }

    @Override
    public PDColorSpace removeColorSpace(COSObject indirect) {
        return null;
    }

    @Override
    public PDExtendedGraphicsState removeExtState(COSObject indirect) {
        return null;
    }

    @Override
    public PDShading removeShading(COSObject indirect) {
        return null;
    }

    @Override
    public PDAbstractPattern removePattern(COSObject indirect) {
        return null;
    }

    @Override
    public PDPropertyList removeProperties(COSObject indirect) {
        return null;
    }

    @Override
    public PDXObject removeXObject(COSObject indirect) {
        return null;
    }
}
