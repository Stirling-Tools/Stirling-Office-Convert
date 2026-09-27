package stirling.software.officeconvert.slides;

import java.util.List;

public record Slide(int page, int background, List<SlideShape> shapes) {}
