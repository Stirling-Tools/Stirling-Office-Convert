package stirling.software.officeconvert.slides;

import java.util.Set;

import stirling.software.officeconvert.model.Paragraph.Align;
import stirling.software.officeconvert.model.Paragraph;

public record TextPara(
        Paragraph content,
        Align align,
        float marginLeft,
        float indent,
        float marginRight,
        float lineHeight,
        float spaceBefore,
        float firstBaseline,
        float lastBaseline,
        float size,
        Bullet bullet,
        Set<Integer> linkLines) {}
