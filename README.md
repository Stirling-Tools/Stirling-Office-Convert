# Stirling Office Convert

Office document conversion in plain Java on PDFBox, built to replace LibreOffice in Stirling-PDF. Today it converts
PDF to Word (DOCX), OpenDocument (ODT, ODP, ODS), RTF, plain text, PowerPoint (PPTX, PPT) and Excel (XLSX); Office to
PDF (DOCX, PPTX, XLSX) is in progress in the `topdf` module (`stirling-office-convert-topdf`), which never fetches
anything a document links to and never runs macros, fields or formulas. There are no native dependencies and no
external processes. The output is editable content: real paragraphs, headings, lists, tables, footnotes, headers and
footers, and section columns, not a page of positioned text boxes. Only page furniture and text the flow cannot hold
(a footer some pages share, fragments set side by side) is placed.

## Build and run

```
./gradlew build                      # core + CLI, runs the unit tests
java -jar cli/build/libs/stirling-office-convert-cli.jar in.pdf -o out.docx
java -jar cli/build/libs/stirling-office-convert-cli.jar folder/ -o outdir/
java -jar cli/build/libs/stirling-office-convert-cli.jar report.docx slides.pptx book.xlsx -o outdir/
```

CLI options for PDF input: `--pages a-b`, `--no-tables`, `--dpi n` (vector figures), `--password p`,
`--picture-fallback`, `--pictures compact|lossless` (see Pictures below), `-q`.
For Word, PowerPoint and Excel input (`.docx .docm .dotx .dotm .pptx .pptm .ppsx .ppsm .potx .potm .xlsx .xlsm
.xltx .xltm .xlsb`, Word 6.0 to 2003 `.doc .dot`, Excel 2.0 to 2003 `.xls .xlt`, PowerPoint 97-2003 `.ppt .pps .pot`,
`.rtf`, Word 2003 XML, XML Spreadsheet 2003 and Flat OPC `.xml`, Visio 2013 and later `.vsdx .vsdm .vssx .vstx`,
OpenDocument `.odt .ott .fodt .odm .ods .ots .fods .odp .otp .fodp .odg .otg .fodg`, OpenOffice.org 1.x `.sxw .stw .sxc
.stc .sxi .sti .sxd .std`, SYLK, DIF and dBASE `.slk .dif .dbf`, Lotus 1-2-3 `.wk1 .wks .wk3 .wk4 .123`, Apple iWork `.pages .numbers .key` (from the preview they hold), plain text `.txt .text .log .asc` and tables
`.csv .tsv .tab`), which converts to PDF: `--password p` (a password protected document), `--max-pages n` (default 10000, 0 = all), `--timeout s` (default 300, 0 =
none), `--fonts dir` (repeatable; an extra folder of fonts), `--font-map Family=Installed` (repeatable; draw a
family with an installed one, such as `Aptos=Inter`), `--font-width Family=scale` (repeatable; scale a substituted
family's widths, 0.5 to 2), `--no-system-fonts` (only the given fonts and the bundled Liberation Sans, for
reproducible output), `-q`, and `--format pdf` to take only the Office files out
of a folder. A folder converts both its PDFs and its Office files; its text and CSV files only with `--format pdf`. Inputs that would write the same output name (such
as `report.docx` and `report.xlsx`) keep their own extension in it (`report.docx.pdf`, `report.xlsx.pdf`), and Office
owner files (`~$name`) are skipped. Warnings (substituted fonts, skipped active content, pictures that could not be
drawn) print to stderr as `warning: <file>: <message>` unless `-q`.
The output file's extension picks the format: `.docx`, `.odt`, `.fodt`, `.xml` (flat ODT), `.rtf`, `.doc`, `.txt`,
`.pptx`, `.odp`, `.ppt`, `.xlsx` or `.ods` (see Other formats). For a folder of PDFs, `--format ext` names it; `--sheets
page|table|single` sets a spreadsheet's layout. Each file prints its time and the heap in use when it finished.
`--pdfa 1a|1b|2a|2b|2u|3a|3b|3u` makes an archival PDF/A copy of each PDF instead (see PDF to PDF/A): `in.pdfa.pdf` beside the
input, or the `-o` file or folder; it takes `--password`, `--timeout`, `--fonts`, `--font-map` and
`--no-system-fonts`, and prints what it changed as
`note: <file>: <message>`.

## Local test app

`app` is a small page for trying the converter by hand, in both directions: drop PDFs on it to get the formats
you picked (Word, OpenDocument text, RTF, plain text, PowerPoint, OpenDocument presentation, Excel or OpenDocument
spreadsheet, or flat OpenDocument XML), or drop Word, PowerPoint and Excel files (`.docx .docm .dotx .dotm .pptx
.pptm .ppsx .ppsm .potx .potm .xlsx .xlsm .xltx .xltm .xlsb`, binary `.doc .dot .xls .xlt .ppt .pps .pot`, `.rtf`,
OpenDocument `.odt .ods .odp .odg` and their templates and flat forms, and the other formats listed above) to get PDFs. The direction comes from the file itself: a PDF goes to Office, an
Office package goes to PDF, whatever its name. View shows the result beside the original, a PDF in the browser's
own viewer and an Office file as a quick look drawn in the page. By default it listens on this machine only. It is
not part of the Maven release.

```bash
./gradlew :app:jar
java -jar app/build/libs/stirling-office-convert-app.jar     # then open http://localhost:8177
```

Options on the page, for PDFs: page range, password, table detection, the picture fallback and lossless pictures.
Scripts can POST a PDF to `/convert?format=docx|odt|rtf|txt|xml|pptx|odp|xlsx|ods` with the same options in the query
(`pictures=lossless` for lossless pictures), or an Office file to `/convert` (`format=pdf` or none). An Office answer
carries `X-Input` (the kind found in the package, such as `docm`), `X-Pages`, and `X-Warnings` (URL-encoded, one
per line: substituted fonts, skipped macros and other active content). Macros, fields, formulas and links in a
document are never run or fetched. A password given under Options also opens a protected Office file. Excel and PowerPoint 97-2003 files are found by their content and convert as described below.

### Demo image

The same page as a Docker image for public demos, listening on port 80 as a non-root user, on the Java
25 base image Stirling-PDF ships:

```bash
./gradlew :app:jar
docker build -t stirling-office-convert -f app/Dockerfile app/build/libs
docker run --rm -p 8080:80 stirling-office-convert          # then open http://localhost:8080
```

A published build is `frooodle/test:stirling-office-convert` (amd64 and arm64). Settings, as
environment variables (defaults in the image):

| Variable | Image default | Meaning |
|---|---|---|
| `MAX_UPLOAD_MB` | 100 | Largest file accepted |
| `MAX_PAGES` | 300 | Pages converted per request; longer files get the first 300 and a note |
| `CONVERT_TIMEOUT_SECONDS` | 180 | A conversion taking longer is stopped and the user told |
| `MAX_CONCURRENT` | half the CPUs, at least 2 | Conversions running at once |
| `MAX_QUEUED` | 8 | Requests waiting for a free converter; beyond that the user is asked to retry |
| `QUEUE_WAIT_SECONDS` | 120 | How long a request waits for a free converter |
| `MAX_PER_CLIENT` | 4 | Requests one address may have in flight, conversions that ran over their time included; 0 for no limit |
| `CLIENT_IP_HEADER` | unset | Header a trusted reverse proxy puts the client's address in, such as `X-Forwarded-For` |
| `REQUEST_TIMEOUT_SECONDS` | 300 | Time allowed for a request's headers and upload |
| `MAX_CONNECTIONS` | 1000 | Open connections the server accepts |
| `EXIT_WHEN_STUCK` | true | Exit, for a restart, once every converter is held by a conversion that ignored its timeout |
| `HOST`, `PORT` | 0.0.0.0, 80 | Where the server listens |

Uploaded files live in a temporary folder only for the length of the conversion, and a busy server refuses an
upload before reading it. Passwords travel in a request header, never the URL. `/health` answers `ok`, or 503 once
every converter is stuck. In public, run the image behind a reverse proxy that buffers uploads and limits
connections per client, with a restart policy or liveness probe on `/health`.

### Tester image (both directions, with LibreOffice)

The same page with LibreOffice (Writer, Calc and Impress) installed beside the converter, the way Stirling-PDF
runs it (Fresh PPA; `writer_pdf_import` and `impress_pdf_import` for PDFs, the Writer, Impress and Calc PDF
export for Office files), to see both engines' files side by side in both directions. Under Engine, pick
Stirling Office Convert, LibreOffice or both; the viewer then switches between them or shows them together, with
each file's time and size (and page count for PDFs). PDFs made from Office files show in the browser's own PDF
viewer, next to a quick look at the original. LibreOffice makes no spreadsheets from a PDF, and its text export
comes out empty (its import puts every line in a frame), so those show as not available. The image carries
Liberation (with Sans Narrow), Carlito, Caladea, DejaVu, Noto (core scripts, mono and colour emoji), WenQuanYi
Zen Hei (a TrueType CJK font), FreeFont, Open Sans and Lato, plus free stand-ins for the other common Office,
Windows and Mac fonts, built in the image's first stage: URW base35 and TeX Gyre (converted to TrueType outlines,
which PDFBox can embed), Gelasio (Georgia's metrics), Selawik (Segoe UI's), Comic Neue, EB Garamond, Inter,
Roboto and Roboto Condensed (Google's current release, with the line metrics of Office's cloud Roboto), Source
Sans 3, Source Serif 4, Source Code Pro, Fira Sans, Fira Mono, Inconsolata and Noto Emoji, each download pinned
by SHA-256 and kept with its licence (OFL, GPL or AGPL with the font exception, GUST). A fontconfig file maps the
Office names to them for LibreOffice. The image is 1.32 GB on disk (364 MB compressed).

```bash
./gradlew :app:jar
docker build -t stirling-office-convert-tester -f app/Dockerfile.compare app/build/libs
docker run --rm -p 8080:80 stirling-office-convert-tester   # your own files only; then open http://localhost:8080
```

For files that are not your own, keep the container off the network. Our engine never opens a URL or runs
anything a document carries, and LibreOffice here is locked down as far as its settings go: every job gets a
fresh profile copied from a hardened template (macro security very high, macros disabled, active content such as
OLE and DDE off, Writer and Calc link updates set to never, untrusted referer links blocked, crash reports and
update checks off, every proxy pointed at a closed local port) and its own temporary folder, runs headless with
`--norestore --nolockcheck --nodefault`, and is killed with every process it started when the request times out.
The network boundary is still what makes it safe. With `--network=none` Docker publishes no port, so scripts talk
to it from a second container that shares its network namespace (the image has `curl`):

```bash
docker run -d --name office-tester --network=none stirling-office-convert-tester
docker run --rm --network container:office-tester -v "$PWD:/files" --entrypoint curl stirling-office-convert-tester \
    -s --data-binary @/files/report.docx -o /files/report.pdf "http://127.0.0.1/convert?engine=libreoffice"
```

To use the page in a browser with the same isolation, put the tester on an internal network (no route and no DNS
out) and publish it through a relay container that can reach only the tester (the image has `socat`):

```bash
docker network create --internal office-sandbox
docker run -d --name office-tester --network office-sandbox stirling-office-convert-tester
docker run -d --name office-door -p 8080:8080 --entrypoint socat stirling-office-convert-tester \
    TCP-LISTEN:8080,fork,reuseaddr TCP:office-tester:80
docker network connect office-sandbox office-door    # then open http://localhost:8080
```

Scripts pick the engine with `engine=ours|libreoffice` in the query; the answer names it in `X-Engine`. The
local app finds an installed LibreOffice by itself. Settings on top of the table above:

| Variable | Image default | Meaning |
|---|---|---|
| `LIBREOFFICE` | /usr/bin/soffice | Path to soffice; `auto` finds it, `off` turns the engine off |
| `LIBREOFFICE_CONCURRENT` | 2 | LibreOffice conversions at once, each with a fresh profile of its own |
| `LIBREOFFICE_TEMPLATE` | /opt/lo-template | An initialised profile each one is copied from, hardened at startup |

## Library use

Add the dependency (Java 25 or later):

```kotlin
implementation("com.stirling:stirling-office-convert:<version>")            // Gradle, Kotlin DSL
```

```groovy
implementation 'com.stirling:stirling-office-convert:<version>'             // Gradle, Groovy DSL
```

```xml
<dependency>                                                                <!-- Maven -->
  <groupId>com.stirling</groupId>
  <artifactId>stirling-office-convert</artifactId>
  <version>VERSION</version>
</dependency>
```

Then one class does it all, `stirling.software.officeconvert.OfficeConvert`. The output file's extension picks the
format: `.docx`, `.odt`, `.fodt`, `.xml` (flat ODT, what LibreOffice writes for PDF to XML), `.rtf`, `.doc` (RTF
content), `.txt`, `.pptx`, `.odp`, `.xlsx` or `.ods`.

```java
import java.nio.file.Path;
import java.time.Duration;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import stirling.software.officeconvert.OfficeConvert;
import stirling.software.officeconvert.OfficeConvert.Settings;
import stirling.software.officeconvert.PdfToXlsx;
import stirling.software.officeconvert.Pictures;

OfficeConvert.convert(Path.of("in.pdf"), Path.of("out.docx"));             // every page, default settings
OfficeConvert.convert(Path.of("in.pdf"), Path.of("out.xlsx"));             // same call, a spreadsheet

OfficeConvert.convert(Path.of("in.pdf"), Path.of("out.pptx"), Settings.defaults()
        .pages(2, 5)                  // 1-based, inclusive; 0 means first or last
        .password("secret")           // for protected PDFs
        .tables(false)                // skip table detection
        .pictureFallback(true)        // keep a page that cannot be converted as a picture instead of failing
        .figureDpi(200)               // resolution of drawings turned into pictures
        .sheets(PdfToXlsx.Sheets.TABLE)   // spreadsheets: a sheet per table instead of per page
        .pictures(Pictures.LOSSLESS)  // every pixel, no JPEG compression (see Pictures)
        .timeout(Duration.ofMinutes(2)));  // interrupt and fail with OfficeConvert.TimedOut past this

String text = OfficeConvert.text(Path.of("in.pdf"));                      // text in reading order

// A document you already opened, into your own stream; you keep both, and the stream stays open.
try (PDDocument doc = Loader.loadPDF(bytes)) {
    OfficeConvert.convert(doc, response.getOutputStream(), OfficeConvert.Format.DOCX, Settings.defaults());
}
```

For files from people you do not trust, always set a timeout. A server might do this:

```java
try {
    OfficeConvert.convert(upload, target, Settings.defaults().timeout(Duration.ofMinutes(3)));
} catch (OfficeConvert.TimedOut e) {
    // too slow: stopped mid-page, and no partial file is left behind
} catch (IOException e) {
    // damaged, password protected (InvalidPasswordException) or not convertible; the message says why
}
```

Conversions of different documents run safely in parallel; a `PDDocument` must not be converted on two threads at
once. The per-format classes (`PdfToDocx`, `PdfToOdt`, `PdfToRtf`, `PdfToText`, `PdfToPptx`, `PdfToOdp`, `PdfToXlsx`)
stay available for options the facade leaves out, such as untyped spreadsheet cells; see Other formats. PowerPoint
97-2003 (`.ppt`) comes from `com.stirling:stirling-office-convert-legacy` (`PdfToPpt`), which adds Apache POI.

### Pictures

`Pictures.COMPACT`, the default, keeps pictures at 220 ppi or more at their printed size (Word's own default)
and stores a large picture without transparency as a JPEG of quality 90. Files come out several times smaller,
and the eye cannot tell those JPEGs from the originals, but flat artwork with lettering can show faint fuzz on
sharp edges when zoomed right in. `Pictures.LOSSLESS` keeps every pixel the PDF holds and compresses nothing
lossily: pictures, page backgrounds and slide art are PNG, and where a format needs a cropped or turned JPEG
redrawn (ODT, ODP, RTF) it is redrawn as PNG. Either way, JPEGs in the PDF are copied as they are. Only a picture past the decoding budget of about 32 million pixels
is thinned.

The `Path` overload writes beside the target and moves the file into place, so a failed conversion leaves nothing
behind. Every failure is an `IOException`; a page that cannot be converted fails the conversion naming the page.
An interrupted thread stops within moments, mid-page, with an `InterruptedIOException`, which is how the timeout
works. Warnings go through Commons Logging, like PDFBox's own, which reaches SLF4J or Log4j when either is present.

Damaged input is read the way viewers read it: a broken operator is skipped, and a content stream that breaks off
keeps what it drew before the break. Limits that keep one document from exhausting a shared server, each far above
what 74,000 sampled real pages needed:

- a page with more than 200,000 characters fails (or becomes a picture with fallback on);
- a page runs at most 5,000,000 operators and shows at most 50,000 forms per pass, and keeps 10,000 image draws;
  the rest of such a page is left out, with a warning;
- a stream that inflates past 256 MB (a compression bomb) is emptied before PDFBox decodes it whole;
- an image whose decoded pixels would take more than 256 MB is left out, with a warning, judged by the size a JPEG,
  JPEG 2000 or fax stream declares for itself as well as the size the PDF claims;
- a page larger than Word's 22 inches is shrunk whole, text and all, to fit;
- a PDF encrypted with a certificate needs BouncyCastle (`bcpkix`) on the classpath.

What reaches the output is inert: links keep only web, mail and FTP addresses, escaped, and mail links only their
addresses, subject and body; spreadsheet cells never hold formulas; JPEGs copied as they are lose comments,
metadata and anything after their end; bidi override characters are dropped from text and document properties.

JPEG 2000 (JPX) pictures are decoded by the library's own plain-Java decoder, `stirling.software.officeconvert.jpx`,
so they need no native code and no `jai-imageio`. It reads JP2 and JPX files and raw codestreams (Part 1: every
progression order and progression change, tiles and tile-parts, precincts, all code-block styles, both wavelets,
both colour transforms, region of interest, packed packet headers, subsampled components, 1 to 16 bit samples)
with palettes, channel definitions, alpha and enumerated or ICC colour. A PDF image keeps its alpha channel as a soft
mask only when its dictionary sets `SMaskInData`; otherwise PDFBox is handed the colour bands alone, as the PDF
specification says.

PDFBox finds its JPEG 2000 decoder through ImageIO, so the reader is registered as an ImageIO `jpeg2000` reader, and
having this library on the classpath makes it the JVM's preferred JPEG 2000 reader: the effect is global, and every
`ImageIO` and PDFBox caller in the process gets it ahead of any other installed reader (such as `jai-imageio`). It is
preferred on purpose: it is bounded, interruptible and faster. Every conversion entry point (the `Path` and
`PDDocument` methods of `OfficeConvert`, the `PdfTo*` classes, `OfficeToPdf` and `PdfToPdfA`) puts it first again, so
a host that calls `ImageIO.scanForPlugins()` later cannot reorder it for the next conversion. To keep another reader
first, start the JVM with `-Dstirling.officeconvert.jpxReader=imageio`; the conversions then leave that reader in
front of this one. A picture drawn small is decoded at a lower resolution level. One image is held to 134 million
samples by default (`JpxOptions`, which refuses a negative reduction, a sample limit below one or an empty region), a
tile to 2 million code-blocks, and an interrupted thread stops decoding. PDF to Office leaves out a JPEG 2000 picture
of more than 67 million samples, judged from its codestream header before anything decodes it. `JpxDecoder` also
serves callers directly.

The library depends on `org.apache.pdfbox:pdfbox:3.0.8` and `commons-logging`, with `org.apache.pdfbox:jbig2-imageio`
at runtime for JBIG2 pictures, and runs on Java 25 or later.

### Office to PDF

`com.stirling:stirling-office-convert-topdf` converts Word, PowerPoint and Excel documents to PDF with one class,
`stirling.software.officeconvert.topdf.OfficeToPdf`. PowerPoint 97-2003 files (`.ppt .pps .pot`) convert too: Apache
POI draws their slides through its common drawing interfaces, with the same picture guards and fonts, and their text
is written as real text. WordArt is drawn bent along the same arch, circle, wave, inflate, deflate and slant warps as
in PPTX, its text written hidden for search. The file's content decides, so a `.ppt` that is really a `.pptx` converts
either way.

```java
import stirling.software.officeconvert.topdf.OfficeToPdf;

OfficeToPdf.Result r = OfficeToPdf.convert(Path.of("in.docx"), Path.of("out.pdf"));    // 5 minute timeout
OfficeToPdf.convert(Path.of("in.xlsx"), Path.of("out.pdf"), OfficeToPdf.Options.defaults()
        .timeout(Duration.ofSeconds(60))       // Duration.ZERO = no limit
        .maxPages(500)                         // 0 = every page; r.pageLimitReached(): cut at this limit
        .maxScratchBytes(1L << 30)             // past it OfficeToPdf.OutputTooLarge; 0 = no limit
        .fonts(fonts)                          // see Fonts below
        .displayName("Sales Q3.xlsx"));        // the name a CSV header and FILENAME fields show
OfficeToPdf.convert(inputStream, OfficeToPdf.Format.PPTX, outputStream, OfficeToPdf.Options.defaults());
OfficeToPdf.convert(inputStream, OfficeToPdf.Format.of(Path.of(uploadName)), outputStream,
        OfficeToPdf.Options.defaults().displayName(uploadName));   // TEXT, CSV and TSV say what a stream holds
r.pages();                                     // pages written
r.truncated();                                 // something is missing: the page limit, or content left out
r.warnings();                                  // substituted fonts, skipped active content, pictures left out
```

#### Fonts

A `FontSet` (package `stirling.software.officeconvert.topdf.font`) says which fonts a conversion may use. Build one
when the host starts, or whenever its font folders change, and pass the same instance to every conversion:
`OfficeToPdf.Options.fonts(set)` and `PdfToPdfA.Options.fonts(set)` take it.

```java
FontSet fonts = FontSet.builder()
        .directory(Path.of("/srv/fonts/customer-a"))  // .ttf .ttc .otf .otc, to a depth of 8; links not followed
        .font(bytes)                                  // a font held in memory
        .substitute("Aptos", "Inter")                 // draw a family with an installed one
        .widthScale("Aptos", 0.97f)                   // a substituted family's widths, 0.5 to 2
        .systemFonts(false)                           // only these fonts and the bundled Liberation Sans
        .build();
fonts.problems();                                     // files that were skipped, and why, for the host's log
```

- Scanning and caching. A set reads its folders once, on first use, and keeps that snapshot; build a new set to
  pick up fonts added later. Each file's scan is cached by path, size and modification time, so a new set only
  parses the files that changed, and sets with equal contents share one font library and its caches (shaping,
  widths, fallbacks). A set is immutable and safe to share between threads and conversions.
  `FontSet.system()` is the platform's font folders (the default); `fontDirs` on the options still works and adds
  its folders to the set.
- Order. The set's own fonts come before the system fonts, so a family in both is drawn from the set's file.
- Substitutions. `substitute` wins over the converter's built-in table, without a warning. If the target is not
  installed, the built-in table is used and the usual warning is given. The stand-in keeps the requested Office
  font's widths and line metrics when the converter has a table for it (Aptos, Calibri, Verdana and about 140
  others), so pages break where Word breaks them; `widthScale` replaces that with one fixed scale for the family. A
  family that is installed is never scaled.
- Untrusted fonts. Fonts from a set are treated as untrusted input: at most 64 MB per file, 10,000 files and 1,000
  fonts given as bytes; a symbolic link, an unreadable, truncated or malformed file, or a font with PostScript (CFF)
  outlines, which cannot be embedded, is skipped with a problem line and never fails a conversion. A face that fails
  later, while being drawn or subset, is replaced by a stand-in with a warning. No font is ever downloaded.
- Licences (OS/2 `fsType`). A font marked restricted licence embedding (`0x0002`, unless a less restrictive bit is
  also set) or bitmap embedding only (`0x0200`) is never used: its text is drawn with a stand-in and the warning
  says `X is installed but its licence does not permit embedding; using Y`. Installable, editable and preview and
  print fonts are embedded as subsets; a font that forbids subsetting (`0x0100`) is embedded whole when it is not
  in a collection, and replaced by a stand-in otherwise.

`truncated()` is also set when content could not be read or was past a bound (a damaged slide, sheet or footnotes
part, unreadable relationships, tables nested too deeply); the warnings say what. A host that must not serve an
incomplete PDF checks it, and `pageLimitReached()` tells the page limit apart.

Unlike `OfficeConvert`, whose default is no time limit, `OfficeToPdf` stops after 5 minutes by default and throws
`OfficeToPdf.TimedOut` (an `IOException`). Bad input gives an `IOException` with a plain reason, legacy and unknown
file extensions included. Nothing a document links to is ever fetched, and no macro, field, formula or script is run:
fields and formulas show their cached results, charts their cached values, embedded objects their stored preview.
A Word field saved without a result (DATE, TIME, CREATEDATE, SAVEDATE, PRINTDATE, AUTHOR, TITLE, SUBJECT, KEYWORDS,
COMMENTS, LASTSAVEDBY, REVNUM, TEMPLATE, NUMWORDS, NUMCHARS, EDITTIME, FILENAME, DOCPROPERTY) shows the document's
stored properties with its `\@` date picture and `\*` case switches: dates come from the saved metadata in UTC, never
the clock, and FILENAME is `Options.displayName`.

Excel 97-2003 workbooks (`.xls`, `.xlt`, found by their content whatever the extension) are read with Apache POI
HSSF and rewritten as a SpreadsheetML package that the XLSX renderer draws: cells with their cached values (formulas
are never evaluated), styles and the workbook's colour palette, merged cells, row and column sizes, hidden rows and
columns, print areas and titles, page setup, headers and footers, page breaks, pictures, text boxes and simple
shapes. Charts in `.xls` files are not drawn yet (a warning says so); macros, OLE objects and links are never
opened. A compressed picture that would inflate past 32 MB leaves the drawings out, and a sheet past 480 MB of cells
is cut short. Excel 5.0/95 workbooks (BIFF5, in an OLE2 file or bare) are read record by record into the same
SpreadsheetML: cached values, number formats, fonts, fills, borders, column widths, row heights, print areas and
titles, page setup, headers and footers and page breaks; their charts, pictures and drawing objects are left out with
a warning. Excel 2.x, 3.0 and 4.0 worksheets (BIFF2 to BIFF4, one sheet per file) are first rewritten as BIFF5 (fonts,
number formats, cell formats, cells, rows and column widths) and read the same way; Excel 4.0 workbooks that bundle
several sheets in one file, and chart and macro sheets, are refused with a plain reason.

Password protected documents open with `Options.password(...)` (`--password` on the command line): Office Open XML
packages encrypted with Agile or Standard encryption, and Word, Excel and PowerPoint 97-2003 files encrypted with RC4,
CryptoAPI or XOR obfuscation (Excel 5.0/95 XOR included). The file is decrypted in the conversion's own scratch
space and never written beside the input; a wrong password, or none, fails with a plain reason, and nothing is ever
guessed. Files Office protects only with its built-in read-only password open without one.

Excel binary workbooks (`.xlsb`, found by their content types whatever the extension) have their binary workbook,
sheets, styles, shared strings and tables rewritten as SpreadsheetML for the XLSX renderer; their drawings, charts,
pictures and themes are already XML and are kept. Conditional formats that compare with constants, text, blanks,
ranks or averages are kept; rules built on formulas are left out with a warning. Pivot table styles, comments, macros,
external links and query definitions are left out.

XML Spreadsheet 2003 files (the SpreadsheetML many systems export, often named `.xls` or `.xml`) are read as a
stream into SpreadsheetML: styles with their parents, cached values, dates, rich text, merges, column widths, row
heights, print areas, page setup, headers and footers. Flat OPC documents (Word, Excel or PowerPoint 2007 and later
saved as a single `.xml`) are unpacked into their package. Word 2003 XML documents (WordprocessingML 2003, often
named `.xml`, found by their root element) are rewritten as a DOCX package as a stream: fonts, styles, lists, settings,
sections, headers and footers, footnotes and endnotes, comments, revisions and bookmarks (from their `aml:annotation`
markup), hyperlinks, VML shapes and text boxes, and the pictures the file holds in `w:binData` (compressed EMF and WMF
included); OLE data, templates, mail merge sources and pictures linked outside the file are left out. SYLK, DIF and
dBASE tables are laid out as a one-sheet workbook the way Excel opens them. Lotus 1-2-3 worksheets (Release 2 `.wk1`
and `.wks`, Release 3 and 4 `.wk3 .wk4`, 1-2-3 97 and Millennium `.123`, found by their content) become one sheet per
Lotus sheet with their cached values (formulas are never evaluated) and, for Release 2, the Lotus cell formats,
label alignment, column widths and hidden columns; the separate formatting of later releases is not read.

Visio 2013 and later drawings (`.vsdx .vsdm .vssx .vstx`, found by their content types) are drawn one page per Visio
page by rewriting each page as a slide for the PPTX renderer: every shape's geometry sections (lines, arcs, elliptical
arcs, Bezier curves, NURBS, polylines and ellipses) in its group's and page's coordinates, its line and fill (patterns,
gradients and Visio theme styles and colours resolved from the drawing's theme), arrowheads, text with its character
and paragraph formatting in its text block, and embedded pictures; masters, master shapes and style sheets are
inherited cell by cell, background pages are drawn beneath their pages, and shapes on layers that do not print are
left out. Only cached cell values are used: no ShapeSheet formula is evaluated and no macro is run. Pages larger than
the first are scaled to fit its size. Visio 2003 to 2010 drawings (`.vsd`, `.vdx`) are refused with a plain reason.

Word 97-2003 documents (`.doc`, `.dot`, found by their content whatever the extension) are read with Apache POI
HWPF and rewritten as a WordprocessingML package that the DOCX renderer draws: text with its character and paragraph
formatting, styles, lists, tables (merged cells, borders, shading, nested tables), sections with their page setup,
columns, headers and footers, footnotes and endnotes, inline and floating pictures, text boxes (shapes anchored inside
them included, nested up to four deep) and simple shapes, bookmarks, and hyperlinks (http, https and mailto only).
Fields show their cached results, except page numbers, which are counted, and EQ fields, which are laid out as
equations; macros, OLE objects (beyond
their stored preview picture) and links are never opened. A compressed picture that would inflate past 32 MB is left
out. Word 6.0 and Word 95 documents are first rewritten as Word 97 files (Unicode text in the document's code page,
Word 97 formatted disk pages, style sheet, fonts, sections, headers and footers, footnotes, fields and pictures) and
then read the same way; the drawing objects of their main text (lines, rectangles, ellipses, arcs, polylines and
text boxes with their text) are placed as anchored shapes, while drawing objects in headers and footers are left
out with a warning. Word 2.0 and older are refused with a plain reason.

RTF documents (`.rtf`, and a `.doc` or `.dot` that is really RTF, found by their `{\rtf` header) are read by a small
streaming tokenizer and rewritten as a WordprocessingML package that the DOCX renderer draws: fonts and code pages,
colours, styles, character and paragraph formatting, lists, tables (merged and nested cells, borders, shading),
sections and page setup, headers and footers, footnotes and endnotes, pictures (PNG, JPEG, EMF, WMF, DIB), floating
shapes and text boxes, and Office math (`\mmath`) laid out as equations by the DOCX math layout. Fields show their
saved result (page numbers stay live, EQ fields without a result are laid out as equations); hyperlinks keep only
`http`, `https`
and `mailto` targets; embedded objects show only their saved picture. Group nesting, pictures and output size are
bounded.

OpenDocument text documents, spreadsheets and presentations (`.odt .ott .fodt .ods .ots .fods .odp .otp .fodp`,
packaged or flat, found by their `mimetype` entry or root element whatever the extension) are rewritten in memory as
the matching WordprocessingML, SpreadsheetML or PresentationML package for the DOCX, XLSX and PPTX renderers: styles,
lists, tables, sections and columns, page styles with headers and footers, footnotes, frames, pictures and shapes;
cells with their cached values (formulas are never evaluated), number formats, merges, hidden rows and columns,
print ranges and page setup; master pages, outlines and shrink-to-fit text on slides. Only pictures inside the
package are drawn: linked files are never fetched, and macros and scripts are never run. Embedded charts are not
drawn yet. Password protected files are refused with a plain reason. OpenDocument drawings (`.odg .otg .fodg`) are
drawn like presentations, one page per drawing page, leaving out shapes on layers that are not printed.
OpenOffice.org 1.x documents (`.sxw .stw .sxc .stc .sxi .sti .sxd .std`) are reshaped as OpenDocument (namespaces,
renamed elements, property sets split by style family) and converted as such; their embedded objects are left out.

Apple Pages, Numbers and Keynote files are not read in their own format: the PDF preview iWork stores in the file is
drawn when there is one, else its preview picture of the first page, and a warning says which. Formats that are not
converted (WordPerfect, Works, Publisher, Visio 2003 to 2010, MHTML web archives, Quattro Pro, StarOffice 5, Windows
Write and others) fail with a reason naming the format, found by extension or by content.

Plain text (`.txt .text .log .asc`) prints the way LibreOffice Writer prints it: A4 with 2 cm margins, Liberation Mono
10 pt at 64 lines a page, tab stops every 1.25 cm, long lines wrapped, no widow control, and a form feed starts a new
page. The encoding comes from a byte order mark (UTF-8, UTF-16 or UTF-32), else the file is UTF-8 when it decodes
strictly and Windows-1252 otherwise; CR, LF and CRLF all end a line, and control characters are dropped. The text is
rewritten as a WordprocessingML package that the DOCX renderer draws; nothing past the page limit is written.

Comma separated (`.csv`) and tab separated (`.tsv .tab`) tables print the way LibreOffice Calc prints a CSV: A4, the
file name centred above and "Page n" below, no grid, Liberation Sans 10 pt, each column as wide as LibreOffice makes
it (from its longest shown value), pages down then across. Fields follow RFC 4180 quoting, with LibreOffice's
leniency for a stray or unclosed quote. Plain numbers (with thousands separators and exponents) are right aligned and
shown in LibreOffice's general format, valid ISO 8601 dates are right aligned as written, and everything else is text:
formulas are never evaluated. Text wider than the page, or on several lines, wraps. A cell holds at most 32767
characters, a row 16384 columns and a sheet 1048576 rows, and rows past the page limit are left out. The table is
rewritten as a SpreadsheetML package that the XLSX renderer draws. Both kinds are recognised by their extension, or
on a stream by `Format.TEXT`, `Format.CSV` or `Format.TSV` (what `Format.of` gives for those names), and a file with
one of these names that is really an Office package or RTF converts as what it is. The CSV header shows
`Options.displayName` without its extension when one is given (the Path overload gives the file's own name).

Memory is shared out across the JVM, by both directions (`stirling.software.officeconvert.memory.Admission` in the
core module, which the topdf module now depends on). Before a document is laid out, an estimate of the heap it needs
is taken from its structure alone: for Office files the markup that becomes runs, cells and shapes and the largest
picture to decode (`OfficeToPdf.memoryEstimate(path)`); for a loaded PDF the pages to read, the largest picture and
the largest figure to render (`OfficeConvert.memoryEstimate(pdf, format, settings)`). Conversions start only while
their estimates fit in 60 % of the heap (`-Dstirling.officeconvert.memoryBudgetPercent`), at most two per processor
run at once, and none starts while the heap after its last collection is over 75 % full or, in a Linux container, while
the memory that cannot be reclaimed (tmpfs included, page cache not) plus the heap the JVM may still commit is over
85 % of the limit. A document larger than the whole budget runs alone. The wait counts towards the timeout. If memory
still runs short (the heap over 90 % after a full collection, or the container over 90 %), the largest of the running
conversions stops at its next page or checkpoint with "The document needs more memory to convert than is available"
and the others go on; a conversion running alone stops only at 97 % of the heap or 95 % of the container, and with
nothing running a new one is turned away at once with the same message if the container is still over 95 % after a
collection. An `OutOfMemoryError` inside a conversion is reported the same way. Once a container is 80 % full the gate
also asks the C library, at most once a second, to hand back memory native code has freed (as
`jcmd <pid> System.trim_native_heap` does). Spreadsheet cells are kept packed, pages are let go once drawn, and PDFBox
keeps at most 1/32 of the heap (4 to 64 MB) of each output in memory and the rest in a temporary file. A cancelled or
timed-out conversion never moves its output into place.

The gate governs the heap and watches the container; the rest of the JVM (class metadata, compiled code, compiler
arenas, thread stacks and the C allocator) takes 100 to 130 MB once all three Office formats and PDF have been
converted, and files written to a tmpfs count against a container too. Measured in a 256 MB container with two CPUs,
four workers each converting a 1,000-page DOCX, a 200-slide PPTX, a 500,000-cell XLSX and a 100-page PDF twice: at
`-XX:MaxRAMPercentage=50`, and at 40 with the full JIT compiler, one conversion is stopped for memory where the
container used to be killed; with `-XX:MaxRAMPercentage=40 -XX:TieredStopAtLevel=1` all eight passed in five of seven
runs (41 to 46 s) and one was stopped in the other two, with the container within 1 % of its limit. So in 256 MB also
stop at C1, and keep `java.io.tmpdir` on disk. At 512 MB the defaults hold eight large conversions at once. G1 and
generational Shenandoah, measured with `-XX:+ExplicitGCInvokesConcurrent`, need more of their own (G1's bookkeeping
alone took 45 MB beside a 282 MB heap), so at 55 % of 512 MB the gate stops one of several large conversions rather
than let the container be killed; at 60 % of 1 GB eight large conversions at once passed with both.

On Java 24 and later the JDK's default XML limits are much lower (`jdk.xml.maxElementDepth` 100, entity sizes
100 000). The converter sets its own limits on every parser it makes, but POI parses slides, relationship parts and
`[Content_Types].xml` with the JVM's defaults, so a slide nested more than about 90 groups deep or holding more than
100 000 escaped characters fails with an `IOException` that names the limit. To convert those, call
`PoiXml.raiseProcessLimits()` (package `stirling.software.officeconvert.topdf.io`) once at startup, or start the JVM
with the same `-D` flags: `-Djdk.xml.maxElementDepth=1000 -Djdk.xml.totalEntitySizeLimit=0
-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.elementAttributeLimit=10000`. Both are JVM-wide: they apply to every
XML parser in the process that does not set its own limits, which restores roughly the limits of Java 23 and earlier.
The call sets only properties the host has not set and returns their names. The command-line tool makes this call.

It depends on the core module, `org.apache.pdfbox:pdfbox:3.0.8`, `org.apache.poi:poi-ooxml` and `poi-scratchpad`
5.5.1 (with `poi`, `poi-ooxml-lite`, `xmlbeans`, `commons-io`, `commons-codec`, `commons-compress`, `commons-lang3`,
`commons-collections4`, `commons-math3`, `SparseBitSet`, `curvesapi` and `log4j-api`) and
`de.rototor.pdfbox:graphics2d:3.0.5`, all Apache-2.0 apart from
`curvesapi` (BSD-3-Clause). Automatic hyphenation uses the English Hyphen patterns bundled under
`stirling/software/officeconvert/topdf/docx/hyph/` (BSD-style licence beside them).

## Other formats

The same document model also goes out as OpenDocument, RTF and plain text, each through its own writer
(`odt`, `rtf` and `text` packages), with the options, streaming and failure contract of `PdfToDocx`:

```java
PdfToOdt.convert(pdf, Path.of("out.odt"), options);        // OpenDocument Text
PdfToOdt.convertFlat(pdf, Path.of("out.fodt"), options);   // flat XML OpenDocument, one file, pictures inline
PdfToRtf.convert(pdf, Path.of("out.rtf"), options);        // Rich Text Format
PdfToRtf.convert(pdf, Path.of("out.doc"), options);        // the same RTF under a Word 97-2003 name
PdfToText.convert(pdf, Path.of("out.txt"), options);       // UTF-8 text in reading order
String text = PdfToText.text(pdDocument, options);
```

- ODT and flat ODT carry what the DOCX carries: styles, lists, tables with merged cells, pictures (cropped and turned
  in their pixels), text boxes and turned text boxes as frames, shapes, sections with columns, page setup per section,
  headers and footers with page fields, footnotes, links and bookmarks. Word and LibreOffice both open them.
- RTF likewise, as Word writes it; `.doc` is that RTF, which Word and LibreOffice open by its content without a prompt.
  Word lays RTF out in its 2007 compatibility mode, so it is a little further from the source than the DOCX.
- Text reads columns one after the other, tables row by row with tabs between cells, running headers and footers once,
  footnotes after the page that refers to them, list items with their numbers; pictures are left out and never decoded.


### Slides (PPTX, ODP and PPT)

Each page becomes a slide of its size, its text in text boxes placed where the page set it, with pictures, shapes,
tables and links, stacked in the page's paint order:

```java
PdfToPptx.convert(pdf, Path.of("out.pptx"), PdfToPptx.Options.defaults());
PdfToOdp.convert(pdf, Path.of("out.odp"), PdfToPptx.Options.defaults());   // OpenDocument Presentation
PdfToPpt.convert(pdf, Path.of("out.ppt"), PdfToPptx.Options.defaults());   // PowerPoint 97-2003, legacy module
```


### Spreadsheets (XLSX and ODS)

`PdfToXlsx` converts to an Excel workbook or an OpenDocument spreadsheet, with the same contract as `PdfToDocx`:
Path and stream overloads, the file moved into place only when complete, failures as `IOException` naming the page,
interrupts honoured mid-page, pages streamed so memory stays flat (a 1,000-page PDF converts in a 48 MB heap).

```java
PdfToXlsx.convert(Path.of("in.pdf"), Path.of("out.xlsx"), PdfToXlsx.Options.defaults()); // .ods writes ODS
PdfToXlsx.convert(pdDocument, outputStream,
        PdfToXlsx.Options.defaults().withFormat(PdfToXlsx.Format.ODS).withSheets(PdfToXlsx.Sheets.TABLE));
```

- One sheet per page by default (`Page 1`, `Page 2`...), a page with several large tables giving each its own sheet.
  `Sheets.TABLE` gives one sheet per table, joining a table carried over page breaks; `Sheets.SINGLE` uses one sheet.
- Each table is a real grid from column A: merged cells, bold header rows, the PDF's fills, borders, alignment and
  column widths, a named range per table, a frozen and repeated header on long tables.
- Numbers and dates become typed values formatted as the PDF showed them (grouping, brackets, currency, percentages);
  ambiguous forms read the way their column proves, and identifiers (leading zeros, phone and account numbers) stay text.
- Text outside tables fills rows above, between and below them in reading order, a paragraph to a row; running
  headers and footers become the sheet's print header and footer.

Options: page range, password, table detection on or off, `typedValues` off to keep every cell as text,
`splitLargeTables`, and `textFallback` to keep an unanalysable page as plain lines instead of failing.

## PDF to PDF/A

The `pdfa` module (`stirling-office-convert-pdfa`) rewrites a PDF as PDF/A-1a, 1b, 2a, 2b, 2u, 3a, 3b or 3u in plain Java, without
Ghostscript or LibreOffice. It changes only what the level forbids, so pages keep their content streams, text stays
text and fonts keep their glyphs:

```java
PdfToPdfA.Result r = PdfToPdfA.convert(Path.of("in.pdf"), Path.of("out.pdf"),
        PdfToPdfA.Options.defaults().level(PdfALevel.A2B).password(null).timeout(Duration.ofMinutes(5)));
r.warnings();          // what was removed or replaced, in plain words
r.substitutedFonts();  // "Helvetica as Liberation Sans Regular (LiberationSans-Regular.ttf)"
r.flattenedPages();    // PDF/A-1 pages whose transparency was drawn as pictures
PdfToPdfA.convert(pdDocument, outputStream, options);     // an open document, written to a stream
```

- Fonts. Every font a page uses is embedded. A font without a program is drawn from a metric-compatible stand-in
  from the font library (Liberation, URW base 35, Carlito and the rest of the production set; `fonts` takes a
  `FontSet`, see Fonts, and `fontDirs` adds folders)
  as a new subset TrueType font whose glyph advances are the PDF's own widths, so no line moves. An embedded font whose
  widths disagree with its program, that lacks a glyph a page shows, or whose encoding PDF/A forbids is rebuilt from
  its own glyphs the same way (TrueType glyphs are copied with their hinting; Type 1 and CFF outlines are converted).
  Type 0 fonts keep their CIDs and CMaps behind a new `CIDToGIDMap`. An embedded TrueType font that carries far more
  glyphs than the pages show (Office keeps every glyph slot) is cut down to the glyphs in use, keeping its glyph
  outlines, hinting, advances and every cmap entry that leads to them, including the entries the encoding's glyph
  names lead to. Embedded Type 1 and CFF fonts keep only the glyphs in use (and, for Type 1, the subroutines they
  call) behind the same glyph names and IDs, with accented glyphs built from two others keeping both. A font
  dictionary missing its type, name or widths gets them from its program, and a font program stream gets the subtype
  its bytes show. CMaps PDF/A does not accept are embedded (part 1), merged with the CMap they refer to, or rewritten
  for the codes in use, with CIDs past 65,535 and, for parts 2 and 3, CID 0 (.notdef) moved to free CIDs; an unknown
  CMap name is read as Identity. CharSet and CIDSet are written for part 1 and dropped for parts 2 and 3. For the u and a levels every shown code gets a ToUnicode value, a private-use one (u) or
  U+FFFD (a) when the PDF gives no clue; a ToUnicode map with ranges across a byte boundary is written again.
- Colour. An sRGB output intent with an ICC profile generated in code (version 2, so it serves part 1 too) is added
  unless the PDF already has a usable one. Device CMYK is given a `DefaultCMYK` space with the CC0 CMYK profile that
  PDFBox ships (its own DeviceCMYK profile), so nothing is converted; it is added only when a page uses device CMYK.
  Invalid or, for part 1, version 4 ICC profiles are replaced, and an output intent whose profile is not a printer or
  monitor profile is replaced. DeviceN spaces with more colourants than the level allows (8 for part 1, 32 for parts 2
  and 3) are drawn in their alternate space: colour operators are converted through the tint transform, images are
  converted pixel by pixel and shadings get a sampled function. For parts 2 and 3 every DeviceN spot colourant is
  described in `Colorants` and Separations that share a name share one definition, or are renamed when they draw
  differently.
- JPEG 2000. Part 1 does not allow JPX images, so they are decoded and stored again (JPEG when photographic, lossless
  otherwise, with any alpha as a soft mask); parts 2 and 3 keep them unless they break the part 2 JPX rules (channel
  count, bit depth, colour boxes). Decoding uses the core library's own JPEG 2000 decoder, so nothing extra is
  needed.
- Limits. Content nested deeper than 28 graphics states moves into form XObjects; names over 127 bytes are shortened
  everywhere they are used; long strings and `TJ` arrays in content are split without moving a glyph; numbers are
  clamped; names that are not UTF-8 are rewritten as UTF-8; images and masks get a bit depth PDF/A allows; for part 1
  long number trees, name trees, page trees, CID width arrays, content arrays and ink paths are split or merged,
  structure elements with more children than the limit are grouped under `NonStruct` elements, resource dictionaries
  past 4,095 entries lose the names no content uses, named destinations move into a name tree and custom document
  properties past the limit are dropped; for parts 2 and 3 a
  page larger than 14,400 units gets a `UserUnit`, with its content, annotations and destinations scaled to match.
- Transparency. Parts 2 and 3 keep it. For part 1 each transparent object (soft masks, constant alpha, blend modes,
  transparency groups, translucent annotations) is drawn into a picture of the smallest box covering all of them on
  that page, at `flattenDpi` (200 by default; JPEG when the box is photographic, lossless otherwise), drawn from the
  content up to the last of them and placed after it; what comes after stays vector and is drawn once, and transparent
  text stays in the page as invisible text, so it can still be searched and copied.
- Removed, with a warning each: JavaScript, launch, sound, movie, reset, import and hide actions and every additional
  action, forbidden annotation types and hidden annotations, XFA, PostScript XObjects, image alternates, transfer
  functions, halftones and undefined operators, form field actions, digital signatures (the rewrite would break them),
  and, for parts 2 and 3, metadata of pages, images and fonts (only predefined XMP properties are allowed there).
  Annotations without an appearance get one; LZW streams and inline images with filters PDF/A does not allow are
  recompressed with Flate; forms that borrow their parent's resources get their own; encryption is removed (give the
  password for a protected file).
- Embedded files: removed for part 1, kept for part 2 only when they are PDF/A themselves, kept for part 3 with a MIME
  type, a modification date, an `AFRelationship` and the catalog's `AF` array.
- Optional content: part 1 has none, so content in hidden layers is deleted and the rest kept; parts 2 and 3 keep the
  layers and fix their configurations.
- XMP metadata is written from the Info dictionary (the two agree), with `pdfaid:part` and `pdfaid:conformance`; the
  file gets a trailer ID. Parts 2 and 3 are written with object streams and a cross-reference stream; part 1 with a
  classic table. Objects are numbered without gaps and unfiltered streams are compressed. Structure elements are
  written without their optional type and with a lone kid in place of a one-item array.

Level a (1a, 2a, 3a) is for PDFs that are already tagged: the module does not build a structure tree, so an untagged
file fails with an `IOException` that says to use b or u. For a tagged file it marks the document as tagged, maps
structure types without a standard meaning to `NonStruct`, drops standard types from the role map and invalid language
tags, gives every glyph real Unicode (Symbol and Wingdings private-use codes become their Unicode look-alikes, unknown
ones U+FFFD) and marks flattened pictures as artifacts. It then checks what veraPDF does not: every piece of text and
every image on a page must sit in marked content that the structure tree refers to, or in an artifact; every figure
needs alternative text; and the document needs a language. Untagged drawings, and marked drawings no element refers
to, become artifacts; a missing document language is taken from the structure; a parent tree that does not match the
structure is rebuilt from it. Anything else (untagged text or images, figures without alternative text, no language
anywhere) fails with an `IOException` naming the problem and the pages, rather than claiming level a. Limits and failure contract follow the other converters: a 1 GB input limit, `maxPages`
(10,000 by default) refuses longer documents rather than cutting them, the timeout and thread interrupts stop the work,
and the output is moved into place only when complete.

It depends on the core and topdf modules (and so on what those depend on) and `org.apache.pdfbox:pdfbox:3.0.8`.

## How it works

Two passes over the pages:

1. **Statistics.** A sample of pages (all of them under 40) gives the body font and size, heading tiers, line pitch,
   page frames, running headers/footers and whether the text was auto-hyphenated.
2. **Per page, streamed.** Extract, analyse, build, then write straight into the zip, so memory stays flat with page count.

| Package | Role |
|---|---|
| `extract` | PDFBox glyph and graphics collection in display space (top-left origin); font name resolution and substitution |
| `layout` | Lines, columns, paragraphs, lists, headings, footnotes, math, figures, text boxes, decorations |
| `table` | Ruled and unruled table detection (grid from rules, whitespace alignment) |
| `build` | Turns page layouts into Word model objects; list numbering, RTL ordering, page and column placement |
| `docx` | Streaming OOXML writer: document, styles, numbering, footnotes, headers/footers, media |
| `odt`, `rtf`, `text` | The same document model as OpenDocument Text, RTF and plain text |
| `slides`, `pptx`, `odp` | Pages to slides: placed text boxes, pictures, shapes and tables in paint order, and their writers |
| `sheet` | Spreadsheet model and layout: pages to rows, table grids, cell typing (numbers, dates, identifiers) |
| `xlsx`, `ods` | Streaming spreadsheet writers: each sheet buffered (spilling to disk), shared strings and styles |

Word's own layout rules are modelled so the DOCX re-flows back onto the same pages. For example, with exact line
spacing the baseline sits 0.8 of the line height below the line top, and Word drops space-before at the top of a page.
Fonts Word will not have are swapped for Arial, Times New Roman or Courier New, with per-run letter spacing that keeps
the original widths, or a character scale where the stand-in is far wider (a condensed display face). Text whose
ToUnicode map points into the private use area is read from the embedded font's glyph ids instead.

## Performance

Measured on Windows with JDK 25, one conversion per JVM, peak memory taken from the process working set:

| Document | Stirling Office Convert | LibreOffice |
|---|---|---|
| 506 pages, 1.7M glyphs | 3.8 s, 194 MB (64 MB heap) | 112 s, 908 MB |
| 204 pages, mixed content | 3.5 s, 221 MB (64 MB heap) | 55 s, about 880 MB |

The output is byte-identical at a 64 MB heap and a 1 GB heap. Font caches are keyed weakly by font dictionary,
so parsed font programs are dropped once no page uses them.

Smallest heap giving byte-identical output: a 161-page text benchmark 28 MB, a 348-page manual
94 MB, 40 magazine pages 85 MB, a 40-page handbook 34 MB. A page image is decoded at no more than the resolution
it is shown at (220 ppi, Word's own default), and media past 4 MB spills to a temporary file.

Each CLI run starts a JVM, and for one small file most of the time is class loading. On JDK 25 an AOT cache,
trained once on a few of your own files of each kind, removes most of it (JDK 25, two CPUs: a one-page PDF to DOCX
0.77 s to 0.45 s, a small workbook 0.86 s to 0.61 s, a one-page DOCX 0.53 s to 0.34 s):

```
java -XX:AOTCacheOutput=office-convert.aot -jar stirling-office-convert-cli.jar a.pdf b.docx c.pptx d.xlsx -o /tmp/train/
java -XX:AOTCache=office-convert.aot -jar stirling-office-convert-cli.jar in.pdf -o out.docx
```

The cache belongs to the JDK build that made it; the JVM ignores a cache from another JDK and runs as usual.

## Changes

### 0.2.0

- Needs Java 25 or later; 0.1.0 also ran on Java 21.
- `OfficeToPdf.Format` has three new values, `TEXT`, `CSV` and `TSV`, for plain text and delimited tables. A caller
  with an exhaustive `switch` over `Format` needs cases for them.
- A new module, `stirling-office-convert-pdfa`, converts PDF to PDF/A.
- JPEG 2000 pictures are decoded by the library's own reader, which becomes the JVM's preferred `jpeg2000` ImageIO
  reader; `-Dstirling.officeconvert.jpxReader=imageio` keeps another one first (see Pictures).

## Releasing

Published to Maven Central as `com.stirling:stirling-office-convert`, `com.stirling:stirling-office-convert-legacy`
(the `.ppt` writer), `com.stirling:stirling-office-convert-topdf` (Office to PDF) and
`com.stirling:stirling-office-convert-pdfa` (PDF to PDF/A), signed, the same way as JPDFium:

- A `v1.2.3` tag runs `.github/workflows/release.yml`: build, test, then `publishAllToCentralPortal`, which uploads to
  the Central Portal staging API and finalizes the deployment for review at
  https://central.sonatype.com/publishing/deployments (dispatch with `autoRelease` to skip the review click).
- Pushes to `main` publish a `-SNAPSHOT` (`snapshot.yml`) once the secrets below are set; pull requests build and test on Linux, macOS and Windows
  with Java 25 (`ci.yml`).
- Repository secrets: `CENTRAL_PORTAL_USERNAME`, `CENTRAL_PORTAL_PASSWORD` (a Central Portal user token),
  `GPG_SIGNING_KEY` (ASCII-armoured private key) and `GPG_SIGNING_PASSWORD`. A release version without a signing key
  is refused before anything is uploaded.
- Locally: `./gradlew publishToMavenLocal` builds the jar, sources, javadoc and POM.

## Tests

- `./gradlew test`: unit tests for font names, list markers, line building, side notes, OCR text, table plausibility
  and backdrop figures, plus an end-to-end conversion of a generated PDF that checks the heading, Word list, table and
  page range in the DOCX.
- `./gradlew :topdf:test`: Office to PDF, including the network-safety tests (hostile documents convert without a
  single connection or DNS lookup, and a scan of every class for code that could reach the network or run scripts).
