package stirling.software.officeconvert.photosplit;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

final class FalseFragments {

    private static final double MAX_FRAGMENT_DENSITY = 0.25;
    private static final double MIN_SUPPORTED_DENSITY = 0.75;
    private static final double MIN_PHOTO_SHARE = 0.1;
    private static final double CONFLICT = 0.05;

    record Candidate(RotatedRect rect, boolean supported, boolean fragment) {}

    private FalseFragments() {}

    static Candidate candidate(RotatedRect rect, Region part, byte[] photo, int width,
            PhotoSplitter.Options options, boolean edgeSupported) {
        RotatedRect bounds = part.rect();
        int supported = 0;
        for (int y = 0; y < part.height(); y++) {
            PhotoSplitter.checkpoint();
            for (int x = 0; x < part.width(); x++) {
                if (part.mask[y * part.width() + x] != 0) {
                    supported += photo[(part.minY + y) * width + part.minX + x];
                }
            }
        }
        boolean solid = part.count >= MIN_SUPPORTED_DENSITY * bounds.area()
                && supported >= Math.max(options.minContourArea(), MIN_PHOTO_SHARE * bounds.area());
        boolean fragment = !edgeSupported && part.count < MAX_FRAGMENT_DENSITY * bounds.area()
                && supported < options.minContourArea()
                && !PaleFrames.boundary(part, bounds);
        return new Candidate(rect, solid, fragment);
    }

    static List<RotatedRect> reject(List<Candidate> candidates) {
        boolean[] connected = new boolean[candidates.size()];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for (int i = 0; i < candidates.size(); i++) {
            connected[i] = candidates.get(i).supported();
            if (connected[i]) {
                pending.add(i);
            }
        }
        while (!pending.isEmpty()) {
            int j = pending.removeFirst();
            for (int i = 0; i < candidates.size(); i++) {
                PhotoSplitter.checkpoint();
                if (connected[i]) {
                    continue;
                }
                if (OverlapFit.overlap(candidates.get(i).rect(), candidates.get(j).rect()) > CONFLICT) {
                    connected[i] = true;
                    pending.add(i);
                }
            }
        }
        List<RotatedRect> out = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (!connected[i] || !candidate.fragment()) {
                out.add(candidate.rect());
            }
        }
        return out;
    }
}
