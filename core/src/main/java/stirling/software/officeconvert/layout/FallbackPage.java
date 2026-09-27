package stirling.software.officeconvert.layout;

import java.util.List;

import stirling.software.officeconvert.extract.PageData;

public final class FallbackPage {

    private FallbackPage() {}

    public static PageLayout of(PageData page) {
        Box sheet = new Box(0, 0, page.width(), page.height());
        PageLayout.Item picture = new PageLayout.FloatItem(new PageLayout.FigureItem(sheet, false), 0f, true);
        PageLayout.Column column = new PageLayout.Column(0, page.width(), List.of(picture));
        PageLayout.Band band = new PageLayout.Band(0, page.height(), List.of(column));
        return new PageLayout(page, List.of(band), false, List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
