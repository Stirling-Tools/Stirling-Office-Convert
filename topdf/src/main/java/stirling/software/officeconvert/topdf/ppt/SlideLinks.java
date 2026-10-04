package stirling.software.officeconvert.topdf.ppt;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.hslf.record.InteractiveInfo;
import org.apache.poi.hslf.record.InteractiveInfoAtom;
import org.apache.poi.hslf.usermodel.HSLFGroupShape;
import org.apache.poi.hslf.usermodel.HSLFHyperlink;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSimpleShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hslf.usermodel.HSLFTextShape;

import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.SafeLinks;

final class SlideLinks {

    record Target(String uri, int page) {}

    record Area(Rectangle2D box, Target target) {}

    private static final int MAX_DEPTH = 64;

    private static final int MAX_LINKS = 2000;

    private final Map<Integer, Integer> pages = new HashMap<>();

    private final List<Integer> order = new ArrayList<>();

    SlideLinks(List<HSLFSlide> slides) {
        for (HSLFSlide s : slides) {
            if (!s.isHidden()) {
                pages.put(s.getSlideNumber(), order.size());
                order.add(s.getSlideNumber());
            }
        }
    }

    Map<String, Target> textTargets(HSLFSlide slide) {
        Map<String, Target> out = new HashMap<>();
        text(slide, slide.getShapes(), out, 0);
        return out;
    }

    List<Area> shapeAreas(HSLFSlide slide) {
        List<Area> out = new ArrayList<>();
        shapes(slide, slide.getShapes(), out, 0);
        return out;
    }

    private void text(HSLFSlide slide, List<HSLFShape> list, Map<String, Target> out, int depth) {
        if (list == null || depth > MAX_DEPTH) {
            return;
        }
        for (HSLFShape s : list) {
            if (s instanceof HSLFGroupShape g) {
                text(slide, g.getShapes(), out, depth + 1);
            } else if (s instanceof HSLFTextShape t && t.getTextParagraphs() != null) {
                for (HSLFTextParagraph p : t.getTextParagraphs()) {
                    for (HSLFTextRun r : p.getTextRuns()) {
                        HSLFHyperlink h = r.getHyperlink();
                        String address = h == null ? null : h.getAddress();
                        Target target = address == null ? null : target(slide, h);
                        if (target != null && out.size() < MAX_LINKS) {
                            out.putIfAbsent(address, target);
                        }
                    }
                }
            }
        }
    }

    private void shapes(HSLFSlide slide, List<HSLFShape> list, List<Area> out, int depth) {
        if (list == null || depth > MAX_DEPTH) {
            return;
        }
        for (HSLFShape s : list) {
            if (s instanceof HSLFGroupShape g) {
                shapes(slide, g.getShapes(), out, depth + 1);
            } else if (s instanceof HSLFSimpleShape simple && out.size() < MAX_LINKS) {
                HSLFHyperlink h = simple.getHyperlink();
                Target target = h == null ? null : target(slide, h);
                Rectangle2D box = target == null ? null : s.getAnchor();
                if (box != null && box.getWidth() > 0 && box.getHeight() > 0) {
                    out.add(new Area(box, target));
                }
            }
        }
    }

    Target target(HSLFSlide slide, HSLFHyperlink h) {
        try {
            InteractiveInfo info = h.getInfo();
            InteractiveInfoAtom atom = info == null ? null : info.getInteractiveInfoAtom();
            int action = atom == null ? InteractiveInfoAtom.ACTION_HYPERLINK : atom.getAction();
            if (action == InteractiveInfoAtom.ACTION_JUMP) {
                return jump(slide, atom.getJump());
            }
            if (action != InteractiveInfoAtom.ACTION_HYPERLINK) {
                return null;
            }
            int type = atom == null ? InteractiveInfoAtom.LINK_Url : atom.getHyperlinkType();
            String address = h.getAddress();
            return switch (type) {
                case InteractiveInfoAtom.LINK_NextSlide -> jump(slide, InteractiveInfoAtom.JUMP_NEXTSLIDE);
                case InteractiveInfoAtom.LINK_PreviousSlide -> jump(slide, InteractiveInfoAtom.JUMP_PREVIOUSSLIDE);
                case InteractiveInfoAtom.LINK_FirstSlide -> jump(slide, InteractiveInfoAtom.JUMP_FIRSTSLIDE);
                case InteractiveInfoAtom.LINK_LastSlide -> jump(slide, InteractiveInfoAtom.JUMP_LASTSLIDE);
                case InteractiveInfoAtom.LINK_SlideNumber -> slideNumber(address);
                case InteractiveInfoAtom.LINK_Url -> {
                    String safe = SafeLinks.safeUrl(address);
                    yield safe == null ? null : new Target(safe, -1);
                }
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Target jump(HSLFSlide slide, int jump) {
        Integer here = pages.get(slide.getSlideNumber());
        if (here == null || order.isEmpty()) {
            return null;
        }
        int page = switch (jump) {
            case InteractiveInfoAtom.JUMP_NEXTSLIDE -> here + 1;
            case InteractiveInfoAtom.JUMP_PREVIOUSSLIDE -> here - 1;
            case InteractiveInfoAtom.JUMP_FIRSTSLIDE -> 0;
            case InteractiveInfoAtom.JUMP_LASTSLIDE -> order.size() - 1;
            default -> -1;
        };
        return page >= 0 && page < order.size() ? new Target(null, page) : null;
    }

    private Target slideNumber(String address) {
        if (address == null) {
            return null;
        }
        String[] parts = address.split(",", 3);
        if (parts.length < 2) {
            return null;
        }
        try {
            Integer page = pages.get(Integer.parseInt(parts[1].strip()));
            return page == null ? null : new Target(null, page);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static void place(PdfCanvas canvas, Rectangle2D box, Target target) throws IOException {
        float x = (float) box.getX();
        float y = (float) box.getY();
        float w = (float) box.getWidth();
        float h = (float) box.getHeight();
        if (target.uri() != null) {
            canvas.link(x, y, w, h, target.uri());
        } else if (target.page() >= 0) {
            canvas.linkToPage(x, y, w, h, target.page(), 0);
        }
    }
}
