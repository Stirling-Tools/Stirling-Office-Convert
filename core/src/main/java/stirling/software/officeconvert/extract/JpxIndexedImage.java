package stirling.software.officeconvert.extract;

import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.DecodeOptions;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDIndexed;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.jpx.JpxDecoder;
import stirling.software.officeconvert.jpx.JpxImage;
import stirling.software.officeconvert.jpx.JpxOptions;
import stirling.software.officeconvert.jpx.JpxRaster;

record JpxIndexedImage(PDImageXObject image, PDIndexed space) implements PDImage {

    private static final int MAX_INDEX = 255;

    private static final List<String> BEFORE_JPX = List.of(COSName.JPX_DECODE.getName());

    WritableRaster indices() throws IOException {
        byte[] data;
        try (InputStream in = image.createInputStream(BEFORE_JPX)) {
            data = in.readAllBytes();
        }
        JpxImage decoded = JpxDecoder.decode(data, new JpxOptions(0, null, ImageBudget.MAX_JPX_SAMPLES));
        JpxRaster raster = decoded.raster();
        int w = raster.width();
        int h = raster.height();
        WritableRaster out = Raster.createBandedRaster(DataBuffer.TYPE_BYTE, w, h, 1, null);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                row[x] = Math.clamp(raster.imageSample(0, x, y), 0, MAX_INDEX);
            }
            out.setSamples(0, y, w, 1, 0, row);
        }
        return out;
    }

    @Override
    public BufferedImage getImage() throws IOException {
        return space.toRGBImage(indices());
    }

    @Override
    public BufferedImage getImage(Rectangle region, int subsampling) throws IOException {
        BufferedImage full = getImage();
        Rectangle area = region == null ? new Rectangle(0, 0, full.getWidth(), full.getHeight())
                : region.intersection(new Rectangle(0, 0, full.getWidth(), full.getHeight()));
        int step = Math.max(1, subsampling);
        if (area.isEmpty() || area.x == 0 && area.y == 0 && area.width == full.getWidth()
                && area.height == full.getHeight() && step == 1) {
            return full;
        }
        int w = Math.max(1, (area.width + step - 1) / step);
        int h = Math.max(1, (area.height + step - 1) / step);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, full.getRGB(area.x + x * step, area.y + y * step));
            }
        }
        return out;
    }

    @Override
    public WritableRaster getRawRaster() throws IOException {
        return indices();
    }

    @Override
    public BufferedImage getRawImage() throws IOException {
        return space.toRawImage(indices());
    }

    @Override
    public BufferedImage getStencilImage(Paint paint) throws IOException {
        return image.getStencilImage(paint);
    }

    @Override
    public InputStream createInputStream() throws IOException {
        return image.createInputStream();
    }

    @Override
    public InputStream createInputStream(List<String> stopFilters) throws IOException {
        return image.createInputStream(stopFilters);
    }

    @Override
    public InputStream createInputStream(DecodeOptions options) throws IOException {
        return image.createInputStream(options);
    }

    @Override
    public boolean isEmpty() {
        return image.isEmpty();
    }

    @Override
    public boolean isStencil() {
        return false;
    }

    @Override
    public void setStencil(boolean stencil) {
        image.setStencil(stencil);
    }

    @Override
    public int getBitsPerComponent() {
        return 8;
    }

    @Override
    public void setBitsPerComponent(int bits) {
        image.setBitsPerComponent(bits);
    }

    @Override
    public PDColorSpace getColorSpace() {
        return space;
    }

    @Override
    public void setColorSpace(PDColorSpace colorSpace) {
        image.setColorSpace(colorSpace);
    }

    @Override
    public int getHeight() {
        return image.getHeight();
    }

    @Override
    public void setHeight(int height) {
        image.setHeight(height);
    }

    @Override
    public int getWidth() {
        return image.getWidth();
    }

    @Override
    public void setWidth(int width) {
        image.setWidth(width);
    }

    @Override
    public void setDecode(COSArray decode) {
        image.setDecode(decode);
    }

    @Override
    public COSArray getDecode() {
        return null;
    }

    @Override
    public boolean getInterpolate() {
        return image.getInterpolate();
    }

    @Override
    public void setInterpolate(boolean interpolate) {
        image.setInterpolate(interpolate);
    }

    @Override
    public String getSuffix() {
        return image.getSuffix();
    }

    @Override
    public COSStream getCOSObject() {
        return image.getCOSObject();
    }
}
