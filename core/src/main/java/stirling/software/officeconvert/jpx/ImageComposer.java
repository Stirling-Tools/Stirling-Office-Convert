package stirling.software.officeconvert.jpx;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.List;

final class ImageComposer {

    private enum Kind {
        GREY(1), RGB(3), SYCC(3), CMYK(4), YCCK(4), LAB(3), ICC(0);

        final int count;

        Kind(int count) {
            this.count = count;
        }
    }

    private final JpxRaster raster;

    private final Jp2Boxes boxes;

    private final int width;

    private final int height;

    private Kind kind = Kind.GREY;

    private ColorSpace icc;

    private long[] lab;

    private boolean brokenProfile;

    ImageComposer(JpxRaster raster, Jp2Boxes boxes) {
        this.raster = raster;
        this.boxes = boxes;
        this.width = raster.width();
        this.height = raster.height();
    }

    BufferedImage compose() {
        List<Channel> channels = channels();
        int n = colourSpace(channels.size());
        List<Channel> colour = new ArrayList<>();
        Channel alpha = order(channels, n, colour);
        if (colour.size() != n) {
            kind = colour.size() >= 3 ? Kind.RGB : Kind.GREY;
            icc = null;
            n = kind.count;
            while (colour.size() > n) {
                colour.remove(colour.size() - 1);
            }
        }
        int maxDepth = 1;
        for (Channel c : colour) {
            maxDepth = Math.max(maxDepth, c.depth());
        }
        if (alpha == null && n == 1 && maxDepth == 1 && kind == Kind.GREY) {
            return binary(colour.get(0));
        }
        int target = alpha != null || maxDepth <= 8 || kind == Kind.LAB ? 8 : 16;
        return pixels(colour, alpha, target);
    }

    private List<Channel> channels() {
        List<Channel> components = new ArrayList<>();
        for (int c = 0; c < raster.components(); c++) {
            components.add(Channel.component(raster, c));
        }
        Palette palette = boxes.palette;
        if (palette == null) {
            return components;
        }
        int[][] mapping = boxes.mapping;
        if (mapping == null) {
            mapping = new int[palette.depth().length][];
            for (int i = 0; i < mapping.length; i++) {
                mapping[i] = new int[] {0, 1, i};
            }
        }
        List<Channel> out = new ArrayList<>();
        for (int[] m : mapping) {
            if (m[0] >= components.size()) {
                continue;
            }
            Channel base = components.get(m[0]);
            out.add(m[1] == 1 && m[2] < palette.depth().length ? Channel.palette(base, palette, m[2]) : base);
        }
        return out.isEmpty() ? components : out;
    }

    private int colourSpace(int channels) {
        for (ColourSpec spec : boxes.colours) {
            if (spec.method() == 1) {
                Kind k = switch (spec.enumerated()) {
                    case ColourSpec.SRGB, ColourSpec.ESRGB, ColourSpec.ROMM -> Kind.RGB;
                    case ColourSpec.GREY -> Kind.GREY;
                    case ColourSpec.SYCC, ColourSpec.ESYCC -> Kind.SYCC;
                    case ColourSpec.CMYK -> Kind.CMYK;
                    case ColourSpec.YCCK -> Kind.YCCK;
                    case ColourSpec.LAB -> Kind.LAB;
                    default -> null;
                };
                if (k != null) {
                    kind = k;
                    lab = spec.lab();
                    return k.count;
                }
            } else if ((spec.method() == 2 || spec.method() == 3) && spec.icc() != null) {
                ColorSpace cs = Profiles.icc(spec.icc());
                if (cs != null) {
                    kind = Kind.ICC;
                    icc = cs;
                    return cs.getNumComponents();
                }
                brokenProfile = true;
            }
        }
        int colours = channels - (definesAlpha() ? 1 : 0);
        kind = colours == 4 && !brokenProfile ? Kind.CMYK : colours >= 3 ? Kind.RGB : Kind.GREY;
        if (colours == 3 && boxes.palette == null && !raster.subsampled(0) && raster.subsampled(1)) {
            kind = Kind.SYCC;
        }
        return kind.count;
    }

    private boolean definesAlpha() {
        if (boxes.definitions == null) {
            return false;
        }
        for (int[] d : boxes.definitions) {
            if (d[1] == 1 || d[1] == 2) {
                return true;
            }
        }
        return false;
    }

    private Channel order(List<Channel> channels, int n, List<Channel> colour) {
        int[][] defs = boxes.definitions;
        if (defs != null) {
            Channel[] byAssociation = new Channel[n];
            Channel alpha = null;
            boolean valid = true;
            for (int[] d : defs) {
                if (d[0] >= channels.size()) {
                    valid = false;
                    break;
                }
                if (d[1] == 0 && d[2] >= 1 && d[2] <= n && byAssociation[d[2] - 1] == null) {
                    byAssociation[d[2] - 1] = channels.get(d[0]);
                } else if ((d[1] == 1 || d[1] == 2) && alpha == null) {
                    alpha = channels.get(d[0]);
                }
            }
            for (Channel c : byAssociation) {
                valid &= c != null;
            }
            if (valid) {
                colour.addAll(List.of(byAssociation));
                return alpha;
            }
        }
        for (int i = 0; i < Math.min(n, channels.size()); i++) {
            colour.add(channels.get(i));
        }
        return channels.size() == n + 1 ? channels.get(n) : null;
    }

    private BufferedImage binary(Channel grey) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        byte[] bits = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        int stride = (width + 7) / 8;
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            grey.row(y, row);
            for (int x = 0; x < width; x++) {
                if (row[x] != 0) {
                    bits[y * stride + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                }
            }
        }
        return img;
    }

    private BufferedImage pixels(List<Channel> colour, Channel alpha, int target) {
        ColorSpace cs = switch (kind) {
            case GREY -> GreyProfile.space();
            case CMYK, YCCK -> Profiles.cmyk();
            case ICC -> icc;
            default -> ColorSpace.getInstance(ColorSpace.CS_sRGB);
        };
        if (cs == null) {
            cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
            kind = kind == Kind.YCCK ? Kind.SYCC : Kind.RGB;
            colour = colour.subList(0, 3);
        }
        int colours = colour.size();
        int bands = colours + (alpha != null ? 1 : 0);
        int type = target == 8 ? DataBuffer.TYPE_BYTE : DataBuffer.TYPE_USHORT;
        ComponentColorModel cm = new ComponentColorModel(cs, alpha != null, false,
                alpha != null ? Transparency.TRANSLUCENT : Transparency.OPAQUE, type);
        WritableRaster wr = alpha == null ? cm.createCompatibleWritableRaster(width, height) : alphaApart(type, colours);
        DataBuffer db = wr.getDataBuffer();
        int max = (1 << target) - 1;
        int[][] rows = new int[bands][width];
        int[] depths = new int[bands];
        for (int i = 0; i < colour.size(); i++) {
            depths[i] = colour.get(i).depth();
        }
        if (alpha != null) {
            depths[bands - 1] = alpha.depth();
        }
        for (int y = 0; y < height; y++) {
            for (int i = 0; i < colour.size(); i++) {
                colour.get(i).row(y, rows[i]);
            }
            if (alpha != null) {
                alpha.row(y, rows[bands - 1]);
            }
            int[] outDepths = convert(rows, depths);
            for (int b = 0; b < bands; b++) {
                int d = outDepths[b];
                int[] row = rows[b];
                int bank = b < colours ? 0 : 1;
                byte[] bytes = db instanceof DataBufferByte buffer ? buffer.getData(bank) : null;
                short[] shorts = db instanceof DataBufferUShort buffer ? buffer.getData(bank) : null;
                int at = y * width * colours + (b < colours ? b : 0);
                for (int x = 0; x < width; x++, at += colours) {
                    int v = scale(row[x], d, target, max);
                    if (bytes != null) {
                        bytes[at] = (byte) v;
                    } else {
                        shorts[at] = (short) v;
                    }
                }
            }
        }
        return new BufferedImage(cm, wr, false, null);
    }

    private WritableRaster alphaApart(int type, int colours) {
        int[] banks = new int[colours + 1];
        int[] offsets = new int[colours + 1];
        for (int i = 0; i < colours; i++) {
            offsets[i] = i;
        }
        banks[colours] = 1;
        int size = Math.multiplyExact(Math.multiplyExact(width, height), colours);
        DataBuffer db = type == DataBuffer.TYPE_BYTE ? new DataBufferByte(size, 2) : new DataBufferUShort(size, 2);
        ComponentSampleModel sm = new ComponentSampleModel(type, width, height, colours, width * colours, banks, offsets);
        return Raster.createWritableRaster(sm, db, null);
    }

    private int[] convert(int[][] rows, int[] depths) {
        return switch (kind) {
            case SYCC -> Conversions.sycc(rows, depths, width, false);
            case YCCK -> Conversions.sycc(rows, depths, width, true);
            case LAB -> Conversions.lab(rows, depths, width, lab);
            default -> depths;
        };
    }

    private static int scale(int v, int depth, int target, int max) {
        if (depth == target) {
            return v;
        }
        long dmax = (1L << depth) - 1;
        return (int) ((v * (long) max * 2 + dmax) / (2 * dmax));
    }
}
