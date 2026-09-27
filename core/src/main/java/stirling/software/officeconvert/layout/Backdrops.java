package stirling.software.officeconvert.layout;

import java.util.List;

final class Backdrops {

    private Backdrops() {}

    static boolean isBackdrop(Box figure, List<Line> segments) {
        int crossing = 0;
        for (Line seg : segments) {
            int inside = 0;
            for (Word w : seg.words) {
                if (figure.contains(Regions.wordX(w), Regions.wordY(seg))) {
                    inside++;
                }
            }
            if (inside > 0 && inside < seg.words.size()) {
                crossing++;
            }
        }
        return crossing >= 2;
    }
}
