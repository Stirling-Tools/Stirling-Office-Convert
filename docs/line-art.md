# Line art: bilevel images

Stirling-PDF's compress tool has a "line art" option that turns every picture in a PDF into a 1-bit black and white
image. Today it shells out to ImageMagick. This page records exactly what that step does, so the pure Java
replacement in the `lineart` module can match it.

## What Stirling-PDF does today

Sources: `CompressController.applyLineArtConversion`, `OptimizePdfRequest` and
`ImageMagickLineArtConversionService` (Stirling-PDF 3.2).

Request fields: `lineArt` (default false), `lineArtThreshold` (0 to 100, default 55) and `lineArtEdgeLevel`
(1 to 3, default 1). The step runs before Ghostscript and the JPEG pass, and needs the ImageMagick group enabled.

### Which images

- Every image XObject named in a page's `/Resources /XObject`, and every image one level down inside a form XObject
  named there. Deeper forms, patterns, annotation appearances, Type 3 glyphs and inline images are not touched.
- No filter on size, colour space, bit depth or image masks: an `/ImageMask` stencil or an image that is already
  1-bit is converted like any other.
- Images are grouped by a content hash, so each distinct image is converted once.

### The command

Each image is decoded with PDFBox `PDImageXObject.getImage()` (masks applied, so a masked image has alpha), written
to PNG and run through

```
magick in.png -colorspace Gray -edge <edgeLevel> -negate -normalize -type Bilevel -threshold <t>% -compress Group4 out.tiff
```

Measured on ImageMagick 7.1.2-32 Q16-HDRI, the binary in the `stirling-pdf:3.2.0` image:

| Step | Effect |
|---|---|
| `-colorspace Gray` | `0.212656 R + 0.715158 G + 0.072186 B` on the gamma-encoded sRGB values, kept as floats |
| `-edge r` | Convolution with a `(2r+1)` square kernel: `-1` everywhere, `(2r+1)^2 - 1` at the centre. Edge pixels repeat; HDRI keeps negative and over-range results |
| `-negate` | `65535 - v` |
| `-normalize` | Contrast stretch on a 65536-bin histogram: the darkest 2% go to black, the lightest 1% to white, linear between |
| `-type Bilevel` | Thresholds at 50% straight away |
| `-threshold t%` | No effect: the image is already bilevel, so `lineArtThreshold` changes nothing |
| `-compress Group4` | 1-bit TIFF, CCITT Group 4, min-is-black |

The result is an outline drawing, not a binarised copy: a solid black area becomes a white area with a black edge,
and strokes thicker than the kernel turn hollow. `lineArtEdgeLevel` 2 and 3 widen the kernel to 5 and 7 pixels.

### How it goes back into the PDF

`PDImageXObject.createFromByteArray` reads the TIFF into a new `CCITTFaxDecode` image (1 bit, `DeviceGray`), and the
resource name on the page (or in the form) is pointed at the new image. Consequences:

- Placement is kept, because the content stream and resource name are unchanged.
- `/SMask`, `/Mask`, `/Decode`, `/Interpolate`, `/Intent` and `/OC` of the original are dropped, so transparency is
  lost and a masked picture becomes an opaque rectangle. Stencil masks are converted as ordinary images and stop
  painting in their fill colour.
- Ink colour is lost: every image becomes black on white.
- A reference the search does not reach (a deeper form, a pattern, an annotation) still draws the original.
