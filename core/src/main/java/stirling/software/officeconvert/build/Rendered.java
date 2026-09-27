package stirling.software.officeconvert.build;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.PageLayout;

final class Rendered {

    private Rendered() {}

    static List<Box> regions(PageLayout layout) {
        List<Box> out = new ArrayList<>();
        for (PageLayout.Band band : layout.bands()) {
            for (PageLayout.Column c : band.columns()) {
                for (PageLayout.Item it : c.items()) {
                    collect(it, out);
                }
            }
        }
        return out;
    }

    private static void collect(PageLayout.Item it, List<Box> out) {
        switch (it) {
            case PageLayout.FigureItem fi when !fi.paper() -> out.add(fi.box());
            case PageLayout.ImageItem im when im.draw().skewed() -> out.add(new Box(im.x(), im.top(), im.right(), im.bottom()));
            case PageLayout.FloatItem fl -> collect(fl.picture(), out);
            case PageLayout.PictureRow row -> row.pictures().forEach(p -> collect(p, out));
            default -> {}
        }
    }
}
