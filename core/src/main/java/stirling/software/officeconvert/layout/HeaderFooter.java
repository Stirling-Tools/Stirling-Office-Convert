package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import stirling.software.officeconvert.extract.PageData;

public final class HeaderFooter {

    private static final float ZONE = 0.13f;
    private static final float MIN_SHARE = 0.4f;
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern ROMAN_ONLY = Pattern.compile("(?i)^[-– ]*[ivxlc]{1,6}[-– ]*$");

    private final Map<String, RunningLine> candidates = new HashMap<>();
    private final Map<String, List<float[]>> anchors = new HashMap<>();
    private final Set<Integer> sampledPages = new HashSet<>();
    private final Map<Integer, List<Seen>> seen = new HashMap<>();

    private record Seen(String signature, float top, float bottom) {}
    private final List<RunningLine> accepted = new ArrayList<>();
    private final Set<String> furniture = new HashSet<>();
    private final Map<Integer, Integer> numberOffsets = new HashMap<>();
    private Integer pageNumberOffset;
    private int pageCount;

    void addPage(PageData page, List<Line> segments) {
        sampledPages.add(page.index());
        float h = page.height();
        List<Seen> onPage = seen.computeIfAbsent(page.index(), k -> new ArrayList<>());
        for (Line seg : segments) {
            boolean top = seg.bottom < h * ZONE;
            boolean bottom = seg.top > h * (1 - ZONE);
            if (!top && !bottom || marksText(seg, segments)) {
                onPage.add(new Seen(null, seg.top, seg.bottom));
                continue;
            }
            String sig = signature(seg, top, page.width());
            onPage.add(new Seen(sig, seg.top, seg.bottom));
            RunningLine r = candidates.computeIfAbsent(sig, k -> new RunningLine(k, top, seg.baseline));
            if (r.occurrences.putIfAbsent(page.index(), seg) == null && beyond(seg, top, segments) >= 3) {
                r.buried++;
            }
            for (int n : PageNumbers.in(seg)) {
                numberOffsets.merge(n - page.index(), 1, Integer::sum);
            }
        }
    }

    private static boolean marksText(Line seg, List<Line> segments) {
        if (seg.words.size() != 1 || !seg.text().strip().matches("[0-9*]{1,3}")) {
            return false;
        }
        for (Line o : segments) {
            if (o != seg && o.size > seg.size * 1.2f && o.x >= seg.right && o.x - seg.right < 1.5f * o.size
                    && o.baseline > seg.baseline && o.baseline - seg.baseline < 0.6f * o.size) {
                return true;
            }
        }
        return false;
    }

    private static int beyond(Line seg, boolean top, List<Line> segments) {
        int n = 0;
        for (Line o : segments) {
            if (o == seg || Math.abs(o.baseline - seg.baseline) < 2f) {
                continue;
            }
            float mid = (o.top + o.bottom) / 2f;
            if (top ? mid < seg.bottom : mid > seg.top) {
                n++;
            }
        }
        return n;
    }

    void finish(int pageCount) {
        decide(pageCount);
        candidates.clear();
        seen.clear();
    }

    private void decide(int pageCount) {
        this.pageCount = pageCount;
        int sampled = sampledPages.size();
        if (sampled < 2) {
            return;
        }
        int needed = Math.max(2, (int) Math.ceil(sampled * MIN_SHARE));
        long evenSampled = sampledPages.stream().filter(p -> p % 2 == 0).count();
        boolean sparse = sampled * 2 < pageCount;
        Map<String, Set<Integer>> sightings = new HashMap<>();
        for (RunningLine r : candidates.values()) {
            sightings.computeIfAbsent(unplaced(r.signature), k -> new HashSet<>()).addAll(r.pages());
        }
        for (RunningLine r : candidates.values()) {
            long even = r.occurrences.keySet().stream().filter(p -> p % 2 == 0).count();
            long odd = r.occurrences.size() - even;
            long ofParity = even == 0 ? sampled - evenSampled : odd == 0 ? evenSampled : sampled;
            int neededHere = Math.max(2, (int) Math.ceil(ofParity * MIN_SHARE));
            boolean buried = r.buried * 2 > r.occurrences.size();
            if (!buried && r.occurrences.size() >= Math.min(needed, neededHere)) {
                r.resolveNumbers(pageCount);
                accepted.add(r);
            } else {
                int seen = sightings.get(unplaced(r.signature)).size();
                if (seen >= 3 || seen == 2 && (sparse || r.signature.contains("#"))) {
                    furniture.add(r.signature);
                }
            }
        }
        dropInconsistent(true);
        dropInconsistent(false);
        dropOverlapped();
        if (sampled >= 4) {
            numberOffsets.entrySet().stream().max(Map.Entry.comparingByValue())
                    .filter(e -> e.getValue() >= Math.max(3, 0.3f * sampled))
                    .ifPresent(e -> pageNumberOffset = e.getKey());
        }
    }

    private void dropInconsistent(boolean top) {
        Set<Integer> covered = new HashSet<>();
        Set<Integer> other = new HashSet<>();
        for (RunningLine r : accepted) {
            if (r.top == top) {
                covered.addAll(r.pages());
            }
        }
        for (RunningLine r : candidates.values()) {
            if (r.top == top && furniture.contains(r.signature)) {
                other.addAll(r.pages());
            }
        }
        other.removeAll(covered);
        if (covered.isEmpty() || other.size() <= 0.15f * covered.size()) {
            return;
        }
        for (RunningLine r : List.copyOf(accepted)) {
            if (r.top == top) {
                accepted.remove(r);
                furniture.add(r.signature);
            }
        }
    }

    public List<Line> furniture(PageData page, List<Line> segments) {
        float h = page.height();
        List<Line> out = new ArrayList<>();
        List<Float> numberRows = new ArrayList<>();
        for (Line seg : segments) {
            boolean top = seg.bottom < h * ZONE;
            if (!top && seg.top <= h * (1 - ZONE) || marksText(seg, segments)) {
                continue;
            }
            if (carriesPageNumber(seg, page.index())) {
                numberRows.add(seg.baseline);
                out.add(seg);
            } else if (furniture.contains(signature(seg, top, page.width()))) {
                out.add(seg);
            }
        }
        for (Line seg : segments) {
            if (!out.contains(seg) && numberRows.stream().anyMatch(b -> Math.abs(b - seg.baseline) < 2f)) {
                out.add(seg);
            }
        }
        return out;
    }

    private boolean carriesPageNumber(Line seg, int pageIndex) {
        return pageNumberOffset != null && PageNumbers.in(seg).contains(pageIndex + pageNumberOffset);
    }

    public List<RunningLine> headers() {
        return accepted.stream().filter(r -> r.top).toList();
    }

    public List<RunningLine> footers() {
        return accepted.stream().filter(r -> !r.top).toList();
    }

    public boolean any() {
        return !accepted.isEmpty();
    }

    private void dropOverlapped() {
        Set<String> running = new HashSet<>();
        accepted.forEach(r -> running.add(r.signature));
        for (RunningLine r : List.copyOf(accepted)) {
            float edge = r.top ? -Float.MAX_VALUE : Float.MAX_VALUE;
            for (Line line : r.occurrences.values()) {
                edge = r.top ? Math.max(edge, line.bottom) : Math.min(edge, line.top);
            }
            boolean overlapped = false;
            for (List<Seen> page : seen.values()) {
                for (Seen s : page) {
                    if (s.signature() == null || !running.contains(s.signature())) {
                        overlapped |= r.top ? s.top() < edge - 1 : s.bottom() > edge + 1;
                    }
                }
            }
            if (overlapped) {
                accepted.remove(r);
                furniture.add(r.signature);
            }
        }
    }

    public boolean firstPageDiffers() {
        if (accepted.isEmpty() || !sampledPages.contains(0)) {
            return false;
        }
        for (RunningLine r : accepted) {
            if (r.onPage(0)) {
                return false;
            }
        }
        return true;
    }

    public RunningLine match(PageData page, Line seg) {
        return match(page.width(), page.height(), seg);
    }

    RunningLine match(float w, float h, Line seg) {
        if (accepted.isEmpty()) {
            return null;
        }
        boolean top = seg.bottom < h * ZONE;
        boolean bottom = seg.top > h * (1 - ZONE);
        if (!top && !bottom) {
            return null;
        }
        String sig = signature(seg, top, w);
        for (RunningLine r : accepted) {
            if (r.signature.equals(sig)) {
                return r;
            }
        }
        return null;
    }

    private static String unplaced(String signature) {
        String[] parts = signature.split("\\|", 4);
        return parts[0] + "|" + parts[1] + "|" + parts[3];
    }

    private String signature(Line seg, boolean top, float pageWidth) {
        String text = seg.text().strip().toLowerCase(Locale.ROOT);
        if (ROMAN_ONLY.matcher(text).matches()) {
            text = text.replaceAll("(?i)[ivxlc]+", "#");
        }
        text = DIGITS.matcher(text).replaceAll("#").replaceAll("\\s+", " ");
        float slotWidth = pageWidth / 12f;
        float[] at = anchor((top ? "H|" : "F|") + text, seg.baseline, seg.centre(), slotWidth);
        int y = Math.round(at[0] / 4f);
        int slot = Math.round(at[1] / slotWidth);
        return (top ? "H|" : "F|") + y + "|" + slot + "|" + text;
    }

    private float[] anchor(String key, float baseline, float centre, float slotWidth) {
        if (!key.chars().anyMatch(Character::isLetter)) {
            return new float[] {baseline, centre};
        }
        List<float[]> places = anchors.computeIfAbsent(key, k -> new ArrayList<>());
        for (float[] a : places) {
            if (Math.abs(a[0] - baseline) <= 2f && Math.abs(a[1] - centre) <= slotWidth / 2f) {
                return a;
            }
        }
        float[] a = {baseline, centre};
        places.add(a);
        return a;
    }
}
