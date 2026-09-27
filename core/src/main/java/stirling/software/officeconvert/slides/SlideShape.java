package stirling.software.officeconvert.slides;

public sealed interface SlideShape permits TextShape, PictureShape, TableShape, RectShape, LineShape {}
