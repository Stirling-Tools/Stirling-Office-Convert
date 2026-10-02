package stirling.software.officeconvert.extract;

import java.io.IOException;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.ResourceCache;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroupAttributes;

public final class RgbGroup extends PDTransparencyGroup {

    private final PDTransparencyGroupAttributes attributes;

    private RgbGroup(PDTransparencyGroup grey, PDTransparencyGroupAttributes attributes) {
        super(grey.getCOSObject(), cache(grey.getResources()));
        this.attributes = attributes;
    }

    public static PDTransparencyGroup of(PDTransparencyGroup form) {
        PDTransparencyGroupAttributes group = form.getGroup();
        if (group == null || !grey(group, form.getResources())) {
            return form;
        }
        COSDictionary rgb = new COSDictionary(group.getCOSObject());
        rgb.setItem(COSName.CS, COSName.DEVICERGB);
        return new RgbGroup(form, new PDTransparencyGroupAttributes(rgb));
    }

    @Override
    public PDTransparencyGroupAttributes getGroup() {
        return attributes;
    }

    private static boolean grey(PDTransparencyGroupAttributes group, PDResources resources) {
        try {
            PDColorSpace cs = group.getColorSpace(resources);
            return cs instanceof PDDeviceGray || cs instanceof PDICCBased icc && icc.getNumberOfComponents() == 1;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static ResourceCache cache(PDResources resources) {
        return resources == null ? null : resources.getResourceCache();
    }
}
