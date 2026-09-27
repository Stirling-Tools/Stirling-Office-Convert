package stirling.software.officeconvert.slides;

import stirling.software.officeconvert.model.Picture;

public record PictureShape(Frame frame, Picture picture) implements SlideShape {}
