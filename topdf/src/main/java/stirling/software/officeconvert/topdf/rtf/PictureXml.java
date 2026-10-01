package stirling.software.officeconvert.topdf.rtf;

import java.awt.Dimension;
import java.io.IOException;

import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class PictureXml {

    static final int EMU_PER_TWIP = 635;

    private static final int MAX_TWIPS = 31680 * 4;

    record Image(String target, long cx, long cy, int[] crop) {}

    private PictureXml() {}

    static Image image(Picture p, Media media) {
        if (p.type == null || "unsupported".equals(p.type) || p.data.size() == 0) {
            return null;
        }
        byte[] raw = p.data.toByteArray();
        int[] natural = natural(p, raw);
        byte[] bytes = raw;
        String ext = p.type;
        switch (p.type) {
            case "dib" -> {
                bytes = bmp(raw);
                ext = "bmp";
            }
            case "wmf" -> bytes = placeable(raw, natural[0], natural[1]);
            case "jpeg" -> ext = "jpeg";
            default -> {
            }
        }
        if (bytes == null) {
            return null;
        }
        PictureDecoder.Kind kind = PictureDecoder.sniff(bytes);
        if (kind == PictureDecoder.Kind.UNKNOWN || kind == PictureDecoder.Kind.SVG) {
            return null;
        }
        ext = switch (kind) {
            case PNG -> "png";
            case JPEG -> "jpeg";
            case GIF -> "gif";
            case BMP -> "bmp";
            case TIFF -> "tiff";
            case EMF -> "emf";
            case WMF -> "wmf";
            default -> ext;
        };
        String target = media.add(bytes, ext);
        if (target == null) {
            return null;
        }
        int w = Math.max(1, natural[0]);
        int h = Math.max(1, natural[1]);
        int shownW = clampTwips((long) (w - p.cropl - p.cropr) * scale(p.scalex) / 100);
        int shownH = clampTwips((long) (h - p.cropt - p.cropb) * scale(p.scaley) / 100);
        int[] crop = null;
        if (p.cropl != 0 || p.cropr != 0 || p.cropt != 0 || p.cropb != 0) {
            crop = new int[] {frac(p.cropl, w), frac(p.cropt, h), frac(p.cropr, w), frac(p.cropb, h)};
        }
        return new Image(target, (long) shownW * EMU_PER_TWIP, (long) shownH * EMU_PER_TWIP, crop);
    }

    private static int scale(int s) {
        return s <= 0 ? 100 : Math.min(s, 10_000);
    }

    private static int clampTwips(long v) {
        return (int) Math.max(1, Math.min(MAX_TWIPS, v));
    }

    private static int frac(int crop, int size) {
        return (int) Math.max(-100_000, Math.min(100_000, (long) crop * 100_000 / size));
    }

    private static int[] natural(Picture p, byte[] raw) {
        if (p.goalw > 0 && p.goalh > 0) {
            return new int[] {p.goalw, p.goalh};
        }
        boolean metafile = "emf".equals(p.type) || "wmf".equals(p.type);
        if (metafile && p.picw > 0 && p.pich > 0) {
            return new int[] {(int) ((long) p.picw * 1440 / 2540), (int) ((long) p.pich * 1440 / 2540)};
        }
        if (!metafile && p.picw > 0 && p.pich > 0) {
            return new int[] {p.picw * 15, p.pich * 15};
        }
        if (!metafile) {
            try {
                byte[] probe = "dib".equals(p.type) ? bmp(raw) : raw;
                Dimension d = probe == null ? null : PictureDecoder.pixelSize(probe);
                if (d != null && d.width > 0 && d.height > 0) {
                    return new int[] {d.width * 15, d.height * 15};
                }
            } catch (IOException | RuntimeException e) {
                return new int[] {1440, 1440};
            }
        }
        return new int[] {1440, 1440};
    }

    static byte[] bmp(byte[] dib) {
        if (dib.length < 16) {
            return null;
        }
        int header = le32(dib, 0);
        if (header < 12 || header > dib.length) {
            return null;
        }
        int bits = header == 12 ? le16(dib, 10) : le16(dib, 14);
        int used = header >= 40 ? le32(dib, 32) : 0;
        int compression = header >= 40 ? le32(dib, 16) : 0;
        int entry = header == 12 ? 3 : 4;
        int colors = used > 0 ? used : bits <= 8 ? 1 << bits : 0;
        int masks = header == 40 && (compression == 3 || compression == 6) ? 12 : 0;
        long offset = 14L + header + masks + (long) Math.max(0, Math.min(colors, 256)) * entry;
        if (offset > dib.length + 14L) {
            return null;
        }
        byte[] out = new byte[dib.length + 14];
        out[0] = 'B';
        out[1] = 'M';
        put32(out, 2, out.length);
        put32(out, 10, (int) offset);
        System.arraycopy(dib, 0, out, 14, dib.length);
        return out;
    }

    static byte[] placeable(byte[] wmf, int wTwips, int hTwips) {
        if (wmf.length >= 4 && le32(wmf, 0) == 0x9AC6CDD7) {
            return wmf;
        }
        if (wmf.length < 18) {
            return null;
        }
        int orgX = 0;
        int orgY = 0;
        int extX = 0;
        int extY = 0;
        int at = le16(wmf, 2) * 2;
        int records = 0;
        while (at + 6 <= wmf.length && records++ < 100_000) {
            long size = (le32(wmf, at) & 0xFFFFFFFFL) * 2;
            int fn = le16(wmf, at + 4);
            if (size < 6 || fn == 0) {
                break;
            }
            if (fn == 0x020C && at + 10 <= wmf.length) {
                extY = (short) le16(wmf, at + 6);
                extX = (short) le16(wmf, at + 8);
            } else if (fn == 0x020B && at + 10 <= wmf.length) {
                orgY = (short) le16(wmf, at + 6);
                orgX = (short) le16(wmf, at + 8);
            }
            if (extX != 0 && extY != 0 && fn != 0x020B && fn != 0x020C && fn != 0x0103) {
                break;
            }
            at += (int) Math.min(size, Integer.MAX_VALUE - at);
        }
        int left = 0;
        int top = 0;
        int right = Math.max(1, Math.min(32767, wTwips));
        int bottom = Math.max(1, Math.min(32767, hTwips));
        int inch = 1440;
        if (extX != 0 && extY != 0) {
            left = orgX;
            top = orgY;
            right = orgX + extX;
            bottom = orgY + extY;
            long per = Math.round(Math.abs(extX) * 1440.0 / Math.max(1, wTwips));
            inch = (int) Math.max(1, Math.min(65535, per));
        }
        byte[] out = new byte[22 + wmf.length];
        put32(out, 0, 0x9AC6CDD7);
        put16(out, 6, clamp16(left));
        put16(out, 8, clamp16(top));
        put16(out, 10, clamp16(right));
        put16(out, 12, clamp16(bottom));
        put16(out, 14, inch);
        int sum = 0;
        for (int i = 0; i < 20; i += 2) {
            sum ^= le16(out, i);
        }
        put16(out, 20, sum);
        System.arraycopy(wmf, 0, out, 22, wmf.length);
        return out;
    }

    private static int clamp16(int v) {
        return Math.max(-32768, Math.min(32767, v));
    }

    static String graphic(Image img, String rid, int id) {
        StringBuilder b = new StringBuilder(512);
        b.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                .append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(id).append("\" name=\"Picture ").append(id)
                .append("\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"")
                .append(rid).append("\"/>");
        if (img.crop() != null) {
            int[] c = img.crop();
            b.append("<a:srcRect l=\"").append(c[0]).append("\" t=\"").append(c[1]).append("\" r=\"").append(c[2])
                    .append("\" b=\"").append(c[3]).append("\"/>");
        }
        b.append("<a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>")
                .append("<a:ext cx=\"").append(img.cx()).append("\" cy=\"").append(img.cy())
                .append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>")
                .append("</a:graphicData></a:graphic>");
        return b.toString();
    }

    static String inline(Image img, String rid, int id) {
        return "<w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"" + img.cx()
                + "\" cy=\"" + img.cy() + "\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:docPr id=\""
                + id + "\" name=\"Picture " + id + "\"/>" + graphic(img, rid, id) + "</wp:inline></w:drawing>";
    }

    private static int le16(byte[] d, int i) {
        return d[i] & 0xFF | (d[i + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] d, int i) {
        return le16(d, i) | le16(d, i + 2) << 16;
    }

    private static void put16(byte[] d, int i, int v) {
        d[i] = (byte) v;
        d[i + 1] = (byte) (v >> 8);
    }

    private static void put32(byte[] d, int i, int v) {
        put16(d, i, v);
        put16(d, i + 2, v >> 16);
    }
}
