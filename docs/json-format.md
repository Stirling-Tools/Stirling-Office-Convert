# JSON document format

PDF to JSON (`PdfToJson`, or an output named `.json`) writes the document model the converter builds from a PDF:
the same paragraphs, runs, lists, tables, pictures, footnotes, headers and footers that go into a DOCX. Converting
the JSON to PDF (`OfficeToPdf`, or an input named `.json`) lays it out again, so a program can read a PDF's
structure, change it and turn it back into a PDF.

Lengths are in points (1/72 inch), colours are `"#RRGGBB"`, and page numbers count from 1. A field that is missing
takes its default (shown below), so hand-written JSON only needs what it changes. Unknown fields are ignored.
Numbers are written exactly, so a document written by PDF to JSON and read back lays out the same as the DOCX
written straight from the PDF.

## Document

```json
{
  "format": "stirling-office-convert",
  "version": 1,
  "title": "Quarterly report",
  "author": "Finance",
  "page": { "width": 612, "height": 792, "marginTop": 72, "marginBottom": 72, "marginLeft": 72, "marginRight": 72 },
  "styles": { "normal": { "font": "Calibri", "size": 11 }, "styles": [ ... ] },
  "header": [ paragraphs ], "footer": [ paragraphs ], "evenHeader": [ ... ], "evenFooter": [ ... ],
  "titlePage": false,
  "lists": [ ... ],
  "body": [ paragraphs and tables ],
  "footnotes": [ { "id": 1, "paragraphs": [ ... ] } ],
  "media": [ { "id": "image1.png", "contentType": "image/png", "pixelWidth": 300, "pixelHeight": 200, "data": "base64" } ]
}
```

| Field | Default | Meaning |
|---|---|---|
| `format` | `stirling-office-convert` | Anything else is refused |
| `version` | `1` | A newer version than the converter reads is refused |
| `page` | A4, 72 pt margins | The last section's page (see Section) |
| `styles.normal` | Calibri 11 | The body text's look (see Run style) |
| `styles.languages` | from the text | `latin`, `eastAsian`, `complex` (language tags such as `en-US`, `ja-JP`, `ar-SA`), `latinScript` and `complexScript` (Unicode script names such as `LATIN`, `ARABIC`) and `rightToLeft`: the languages runs are marked with. Left out, they are worked out from the document's text |
| `styles.styles` | none | Named styles: `id` (letters and digits; headings are `Heading1` to `Heading9`, plus `Title`), `name`, `run`, `outlineLevel`, `keepNext` |
| `media` | none | Pictures the body refers to by `id`: PNG or JPEG (GIF and BMP are redrawn as PNG), at most 50 million pixels |

## Section

`width`, `height`, `marginTop`, `marginBottom`, `marginLeft`, `marginRight`, `headerDistance`, `footerDistance`,
`continuous` (no page break before it), `titlePage` (a different first page header), `pageNumberStart` (1), and
`columns`: a list of `{ "width", "gap" }`. A paragraph's `endsSection` holds the section that ends with it.

## Paragraph

```json
{ "type": "paragraph", "style": "Heading1", "align": "center", "content": [ ... ] }
```

| Field | Default | Meaning |
|---|---|---|
| `style` | `Normal` | A style id |
| `align` | `left` | `left`, `center`, `right` or `justify` |
| `indentLeft`, `indentRight`, `indentFirst` | 0 | Indents; a negative `indentFirst` hangs the first line |
| `spaceBefore`, `spaceAfter` | 0 | Space around the paragraph |
| `lineHeight`, `lineRule` | single | `lineRule` is `exact` or `atLeast` with `lineHeight` |
| `list` | none | `{ "id", "level" }`: an item of a list in `lists`, level 0 to 8 |
| `pageBreakBefore`, `keepNext`, `keepLines`, `rightToLeft`, `noHangingPunctuation` | false | |
| `shading` | none | Background colour |
| `borderBottom`, `borderBottomColor` | none | A rule under the paragraph |
| `bookmark` | none | A bookmark name |
| `tabs` | none | `{ "pos", "kind" (left, center, right, decimal), "leader" }` |
| `markStyle` | none | The look of the paragraph mark |
| `content` | empty | The paragraph's content, in order |

## Content

Each item has a `type`; text is the default.

| Type | Fields |
|---|---|
| `text` | `text`, `style` (Run style), `link` (http, https, ftp or mailto), `linkPage` (a page of this document) |
| `tab` | `style` |
| `break`, `columnBreak`, `pageBreak` | |
| `pageNumber` | `style`, `total` (the page count instead) |
| `image` | `media` (an id), `width`, `height`, `wrap` (`inline`, `square`, `topBottom`, `none`), `x`, `y` (page position when not inline), `behind`, `z` and `paintOrder` (stacking), `cropLeft`, `cropTop`, `cropRight`, `cropBottom` (fractions), `rotation` (degrees), `flipHorizontal`, `description` |
| `textBox` | `x`, `y`, `width`, `height`, `fill`, `line`, `lineWidth`, `rounded`, `paragraphs` |
| `shape` | `x`, `y`, `width`, `height`, `fill`, `line`, `lineWidth`, `rounded`, `ellipse` |
| `footnoteRef` | `id` (of a footnote), `marker` |
| `footnoteMark` | `marker`, the mark at the start of a footnote's text |

## Run style

`font` and `size` (from the paragraph's style when missing; `"font": null` takes the paragraph's font as it is laid out), `bold`, `italic`, `underline`, `strike`, `color` (black),
`highlight`, `vertAlign` (`super` or `sub`), `smallCaps`, `spacing` (points between letters), `scale` (100 percent
width), `border` (a box around the text in this colour). Anything else missing is off. On content, a missing
`style` is the paragraph style's look and `"style": null` is no look of its own.

## List

```json
{ "id": 1, "levels": [ { "format": "decimal", "text": "%1.", "indentLeft": 36, "hanging": 18, "start": 1 }, null ] }
```

Up to nine levels; `null` uses the default. `format` is `decimal`, `lowerLetter`, `upperLetter`, `lowerRoman`,
`upperRoman`, `bullet` (then `text` is the bullet) or `none`; in `text`, `%1` to `%9` stand for the numbers of
levels 1 to 9.

## Table

```json
{ "type": "table", "columns": [120, 240],
  "rows": [ { "header": true, "cells": [ { "paragraphs": [ ... ] }, { "span": 1, "shading": "#EEEEEE", "paragraphs": [ ... ] } ] } ] }
```

Table: `columns` (widths), `indent`, `cellMarginLeft` and `cellMarginRight` (2), `pageBreakBefore`, `rightToLeft`.
Row: `height` (at least), `exactHeight`, `header` (repeats on each page), `cells`. Cell: `span` (columns, 1),
`verticalMerge` (`restart` starts a cell merged down, `continue` continues it), `top`, `bottom`, `left` and `right`
borders as `{ "width", "color" }`, `shading`, `verticalAlign` (`top`, `center`, `bottom`), `paragraphs`, or
`blocks` in its place for a cell that holds tables: paragraphs and tables in order, as in `body`. Tables nest at most
8 deep, counting the outermost; a table deeper than that is read as its paragraphs.
