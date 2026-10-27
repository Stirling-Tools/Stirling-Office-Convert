# Stirling Office Convert usage guide

Run the commands in this guide from the repository root. See [the README](../README.md) for a concise overview.

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
`--picture-fallback`, `--pictures compact|lossless` (see Pictures below), `--overwrite`, `-q`.
For Word, PowerPoint and Excel input (`.docx .docm .dotx .dotm .pptx .pptm .ppsx .ppsm .potx .potm .xlsx .xlsm
.xltx .xltm .xlsb`, Word 6.0 to 2003 `.doc .dot`, Excel 2.0 to 2003 `.xls .xlt`, PowerPoint 97-2003 `.ppt .pps .pot`,
`.rtf`, Word 2003 XML, XML Spreadsheet 2003 and Flat OPC `.xml`, Visio 2013 and later `.vsdx .vsdm .vssx .vstx`,
OpenDocument `.odt .ott .fodt .odm .ods .ots .fods .odp .otp .fodp .odg .otg .fodg`, OpenOffice.org 1.x `.sxw .stw .sxc
.stc .sxi .sti .sxd .std`, SYLK, DIF and dBASE `.slk .dif .dbf`, Lotus 1-2-3 `.wk1 .wks .wk3 .wk4 .123`, Apple iWork `.pages .numbers .key`, Publisher `.pub` and Visio 2003-2010 `.vsd .vss .vst .vsw` (from the preview they hold), plain text `.txt .text .log .asc`, tables
`.csv .tsv .tab`, HTML `.html .htm .xhtml` or a `.zip` of a page (with `--allow-packages`), eBooks `.epub .mobi .azw3 .azw .prc .pdb .fb2 .fbz` and this library's JSON `.json`), which converts to PDF: `--password p` (a password protected document), `--max-pages n` (default 10000, 0 = all), `--timeout s` (default 300, 0 =
none), `--fonts dir` (repeatable; an extra folder of fonts), `--font-map Family=Installed` (repeatable; draw a
family with an installed one, such as `Aptos=Inter`), `--font-width Family=scale` (repeatable; scale a substituted
family's widths, 0.5 to 2), `--no-system-fonts` (only the given fonts and the bundled Liberation Sans, for
reproducible output), `--allow-packages` (convert a `.zip` of an HTML page, or an EPUB book not named `.epub`), `--allow-local-files` (let an HTML page
draw the pictures and style sheets in its own folder, never outside it), `-q`, and `--format pdf` to take only the Office files out
of a folder. A folder converts both its PDFs and its Office files; its text and CSV files only with `--format pdf`. Inputs that would write the same output name (such
as `report.docx` and `report.xlsx`) keep their own extension in it (`report.docx.pdf`, `report.xlsx.pdf`), and Office
owner files (`~$name`) are skipped. An input is never overwritten: an output that would take an input's name keeps that
input's extension instead (`b.docx` and `b.pdf` in one folder give `b.docx.pdf` and `b.pdf.docx`), and nothing a run
writes is converted again by it. An output that exists already is left alone and its input skipped (`SKIP <file>:
<reason>` on stderr) unless `--overwrite` is given. A folder with nothing to convert is reported. The exit status is 0
when everything converted, 1 when something failed or was skipped or a folder held nothing to convert, and 2 for a
mistake in the arguments. Warnings (substituted fonts, skipped active content, pictures that could not be
drawn) print to stderr as `warning: <file>: <message>` unless `-q`.
The output file's extension picks the format: `.docx`, `.odt`, `.fodt`, `.xml` (flat ODT), `.rtf`, `.doc`, `.txt`,
`.html`, `.json`, `.md`, `.epub`, `.azw3`, `.pptx`, `.odp`, `.ppt`, `.xlsx` or `.ods` (see Other formats). For a folder of PDFs, `--format ext` names it; `--sheets
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
| `CONVERT_TIMEOUT_SECONDS` | 180 | A conversion taking longer is stopped and the user told; 180 outside the image too, and at least 1 |
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
content), `.txt`, `.html`, `.json`, `.md`, `.epub`, `.azw3`, `.pptx`, `.odp`, `.xlsx` or `.ods`.

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
once. The per-format classes (`PdfToDocx`, `PdfToOdt`, `PdfToRtf`, `PdfToText`, `PdfToHtml`, `PdfToJson`, `PdfToPptx`, `PdfToOdp`, `PdfToXlsx`)
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

The API is `OfficeToPdf`, `FontSet` and the types they name. Apache POI and pdfbox-graphics2d are runtime
dependencies only: the few other public classes that show their types (`Word6Upgrade`, `LegacyOffice`, `PoiPackages`,
`SafeImageRenderer`) are shared between the converter's own packages and are not for callers.

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
CryptoAPI or XOR obfuscation (Excel 5.0/95 XOR included), and OpenDocument packages encrypted per entry with
AES-256-CBC (ODF 1.2 and later) or Blowfish CFB (OpenOffice.org and ODF 1.0/1.1), or as a whole with AES-256-GCM and
Argon2id (LibreOffice's ODF 1.4 extended default). The file is decrypted in the conversion's own scratch
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
left out. Only cached cell values are used: no ShapeSheet formula is evaluated and no macro is run. Each Visio page keeps its own PDF page size; oversized pages are scaled to the PDF limits. Visio 2003 to 2010 drawings (`.vsd .vss .vst .vsw`) are drawn from the preview picture of the first page they keep in their summary information, as described below; `.vdx` is refused with a plain reason.

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
drawn yet. Password protected packages open with their password, as described above. OpenDocument drawings (`.odg .otg .fodg`) are
drawn like presentations, one page per drawing page, leaving out shapes on layers that are not printed.
OpenOffice.org 1.x documents (`.sxw .stw .sxc .stc .sxi .sti .sxd .std`) are reshaped as OpenDocument (namespaces,
renamed elements, property sets split by style family) and converted as such; their embedded objects are left out.

Apple Pages, Numbers and Keynote files are not read in their own format: the PDF preview iWork stores in the file is
drawn when there is one, else its preview picture of the first page, and a warning says which. Microsoft Publisher
publications (`.pub`) and Visio 2003 to 2010 drawings (`.vsd .vss .vst .vsw`, found by their content whatever the
extension) are not read in their own format either: the preview picture of the first page kept in the file's summary
information (an EMF or WMF metafile, drawn as vectors, or a bitmap) becomes a single page, sized to the preview or to
the paper its shape matches, with a warning. A file without a preview, with a blank one, or with only a bitmap thumbnail
under 600 pixels on its longer side (what Publisher keeps) is refused with a plain reason. Formats that are not
converted (WordPerfect, Works, MHTML web archives, Quattro Pro, StarOffice 5, Windows Write and others) fail with a
reason naming the format, found by extension or by content.

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
`curvesapi` (BSD-3-Clause), and `org.jsoup:jsoup:1.22.2` (MIT) for HTML. Automatic hyphenation uses the English Hyphen patterns bundled under
`stirling/software/officeconvert/topdf/docx/hyph/` (BSD-style licence beside them).

HTML (`.html .htm .xhtml`, or any file whose content starts `<!DOCTYPE html` or `<html`, such as a Word "web page"
saved as `.doc`) is laid out without a browser and drawn by the DOCX renderer: headings, paragraphs, lists, tables
(with `colspan`, `rowspan`, borders, shading and widths), pre-formatted text, links, pictures, inline SVG and
`data:image/svg+xml` (shapes, paths, transforms and same-page `<use>`), floats beside text, page breaks, and `@page`
size and margins, named pages included, with the default page's `@top-left|center|right` and
`@bottom-left|center|right` margin boxes as running headers and footers (`content` strings, `counter(page)` and
`counter(pages)`). Style comes from `<style>` sheets, `style` attributes and the old
presentational attributes over a browser's defaults, with the cascade as Chrome applies it when printing: media
queries are answered for print at the page's text width, `!important`, custom properties (`var()`), `calc()`,
`min()`, `max()`, `clamp()`, `rem`, `hsl()` and alpha colours. Flex rows and grids become columns side by side
(short items share a line); absolute positions and visually hidden text (screen-reader-only, off-page, closed
`<details>`) are left out. Nothing is fetched and no script runs: linked style sheets, `@import`, `@font-face`,
`url()` and pictures on the web or on disk are left out with a warning, and only pictures inside the file are drawn
(PNG, JPEG, GIF, BMP, WebP and SVG; AVIF is not). A `.zip` of a page is refused unless
`Options.allowPackages(true)` (`--allow-packages`) is given; then the page it holds (the shallowest `index.html`, else the
shallowest `.html`) draws the pictures, linked style sheets and `@import`s it holds, resolved relative to the page and
never outside the archive. A library caller can supply them another way with `Options.htmlAssets(HtmlAssets)`:
`HtmlAssets.folder(root, page)` reads files inside one folder (no `..`, absolute paths or links leading out of it; what
`--allow-local-files` uses, rooted at the page's folder), and
a caller's own implementation receives each reference resolved against its `base()`, such as a URL it fetches itself.
At most 4,000 linked files and 192 MB are read per page, 64 MB each.

EPUB (`.epub`, EPUB 2 and 3) is read in its reading order (the package document's spine) into one HTML page: each
document starts a new page, its own `<style>` rules apply only to it, links between documents become links inside
the PDF, and the cover picture (an SVG-wrapped cover page included) comes first. Pictures and style sheets come from
the book. A style sheet that only some documents link applies only to those. A book converts by default when it is
named `.epub` or streamed as `Format.EPUB`; a book inside any other `.zip`, or streamed as `Format.HTML`, is a package
and needs `allowPackages`. DRM-protected books cannot be read.

Kindle and Mobipocket books (`.mobi .azw .prc`, `.pdb` PalmDOC text, and KF8 `.azw3`) are read without DRM only:
uncompressed, PalmDOC and HUFF/CDIC text; a KF8 book (or the KF8 half of a combined `.mobi`) is rebuilt from its
skeleton and fragment tables with its style sheet flows, `kindle:embed` pictures and `kindle:pos` links; an older
Mobipocket book keeps its `filepos` links, page breaks and pictures. The title, author and cover come from the EXTH
metadata. FictionBook 2 (`.fb2`, or a `.fbz`/`.fb2.zip` holding one; any other `.zip` holding one only with
`--allow-packages`) is read with sections as chapters, its titles,
epigraphs, poems, citations, tables, embedded pictures, cover and notes; a DOCTYPE is refused. eBooks are limited to
256 MB (Kindle) and 128 MB (FictionBook) with 64 MB of text. An eBook only ever draws what the book file holds. HTML files are limited to 64 MB, and parsing, the cascade, SVG
painting and nesting each work within fixed budgets so a hostile page cannot hang or exhaust the converter.

JSON (`.json`) is the document model that PDF to JSON writes ([format](json-format.md)), hand written or edited;
it is replayed into a WordprocessingML package and drawn. Unknown fields are ignored, a value of the wrong type fails
the conversion with its name, and pictures that cannot be read are left out with a warning.

## Other formats

The same document model also goes out as OpenDocument, RTF, plain text, HTML, JSON and Markdown, each through its own writer
(`odt`, `rtf`, `text`, `html`, `json` and `markdown` packages), with the options, streaming and failure contract of `PdfToDocx`:

```java
PdfToOdt.convert(pdf, Path.of("out.odt"), options);        // OpenDocument Text
PdfToOdt.convertFlat(pdf, Path.of("out.fodt"), options);   // flat XML OpenDocument, one file, pictures inline
PdfToRtf.convert(pdf, Path.of("out.rtf"), options);        // Rich Text Format
PdfToRtf.convert(pdf, Path.of("out.doc"), options);        // the same RTF under a Word 97-2003 name
PdfToText.convert(pdf, Path.of("out.txt"), options);       // UTF-8 text in reading order
PdfToMarkdown.convert(pdf, Path.of("out.md"), options);    // GitHub Flavored Markdown, pictures as data: URIs
PdfToMarkdown.convertPackage(pdDocument, zipStream, options); // document.md plus images/ in a ZIP
String text = PdfToText.text(pdDocument, options);
PdfToHtml.convert(pdf, Path.of("out.html"), options);      // one HTML5 file, pictures as data: URIs
PdfToHtml.convertFixed(pdf, Path.of("page.html"), FixedHtml.Options.defaults()); // each page as it looks
PdfToJson.convert(pdf, Path.of("out.json"), options);      // the document model as JSON
PdfToEpub.convert(pdf, Path.of("out.epub"), options);      // a reflowing EPUB 3 book
PdfToAzw3.convert(pdf, Path.of("out.azw3"), options);      // the same book for Kindle (KF8)
```

- ODT and flat ODT carry what the DOCX carries: styles, lists, tables with merged cells, pictures (cropped and turned
  in their pixels), text boxes and turned text boxes as frames, shapes, sections with columns, page setup per section,
  headers and footers with page fields, footnotes, links and bookmarks. Word and LibreOffice both open them.
- RTF likewise, as Word writes it; `.doc` is that RTF, which Word and LibreOffice open by its content without a prompt.
  Word lays RTF out in its 2007 compatibility mode, so it is a little further from the source than the DOCX.
- Markdown (GitHub Flavored) has headings, paragraphs with bold, italic, strikethrough, code spans for monospaced runs
  and links, nested bulleted and numbered lists (other labels such as `a)` or `IV.` stay in the text), pipe tables
  (merged cells become empty cells; the first row is the header), footnotes as `[^n]` with their text at the end, and
  pictures. Running headers, footers and page numbers are left out. Characters Markdown would read as syntax are
  escaped.
- Text reads columns one after the other, tables row by row with tabs between cells, running headers and footers once,
  footnotes after the page that refers to them, list items with their numbers; pictures are left out and never decoded.
- HTML is one self-contained, flowing page: headings as `h1`-`h6`, paragraphs with their indents, spacing and
  alignment, runs with their font, size, colour and emphasis, numbered and bulleted items with their numbers, tables
  with merged cells, borders and shading, pictures as `data:` URIs, links, footnotes at the end, and the page size and
  margins as `@page` for printing. Floating pictures and text boxes join the flow next to their paragraph; columns
  flow as one. There are no scripts and no external references.
- EPUB is an EPUB 3 book (with an EPUB 2 table of contents for older readers): a new chapter at each top-level
  heading or every 150,000 characters, pictures as files, footnotes in a notes chapter, links kept across chapters,
  and a table of contents from the headings. Running headers and footers are left out, as a reflowing book has no
  pages; the title comes from the PDF's metadata, else its first heading.
- AZW3 is the EPUB's book as a Kindle KF8 file: the same chapters, pictures, style sheet, footnotes and links
  (`kindle:pos`), PalmDOC-compressed text, the table of contents as an NCX index, and the title, author and
  language (`und`) in its metadata. It has no cover picture and no DRM.
- Fixed-layout HTML keeps every page as it looks instead of reflowing it, like `pdftohtml -c`: one self-contained
  file with a box per page at the page's size. `Text.LIVE` (the default) draws the page's graphics and pictures without
  its text as a picture (left out when the page has nothing else) and places the text over it as real SVG text at its
  exact baseline and width (so it lines up even in a substitute font), in the
  PDF font's family with a generic fallback, colour, weight and slant; invisible (OCR) text stays a transparent layer, and glyphs with no Unicode
  stay in the picture. `Text.PICTURE`
  draws everything into the page picture and adds the text as a transparent layer to select and search, so it looks
  exactly like the PDF at the cost of size. Pages are drawn at 150 dpi (36 to 300); web links stay clickable.
  `Options.fonts(FontSource)` lets the caller turn the PDF's own fonts into web fonts for `Text.LIVE` (for example
  `WebFonts::toTrueType` from the `pdfa` module), so live text is drawn in the document's real faces; each font is
  asked for once, and fonts over 4 MB or past 32 MB in total, or that fail, fall back to the family name.
- JSON is the document model itself, the format [described here](json-format.md): everything the DOCX carries, as
  data a program can read, change and convert back to PDF (or, through it, to anything else).


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

`convert(PDDocument, ...)` converts the document it is given in place: afterwards it holds the PDF/A changes (output
intent, metadata, fonts, rewritten content), whether the call succeeds or fails, so load a fresh copy if the original
is still needed. After a timeout the converter waits up to ten seconds for its worker to stop. If third-party
work ignores interruption, that worker can still use the document after the call returns; do not reuse or close
that instance. Prefer the file-based overload for documents that need a strict timeout.

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
  type, a modification date, an `AFRelationship` and the catalog's `AF` array. Part 2 attachment checks allow up to
  16 MiB of PDF bytes, 100,000 parsed values, 64 levels of object nesting and 8 MiB of decoded object and cross-reference
  streams; files that cannot be verified within these bounds are removed with a warning.
- Optional content: part 1 has none, so content in hidden layers is deleted and the rest kept; parts 2 and 3 keep the
  layers and fix their configurations.
- XMP metadata and Info agree. Valid XMP-only titles, creators, descriptions, creation dates and creator tools fill
  missing Info entries; valid rights, language alternatives, labels, ratings, Photoshop properties and history remain.
  Multiple creator names share one matching author string. Extension schemas and carried values are type-checked;
  converter identification and timestamps are written afresh with `pdfaid:part` and `pdfaid:conformance`. The
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

### Web fonts from PDF font programs

`WebFonts.toTrueType` turns a font program embedded in a PDF into a TrueType font a browser loads with `@font-face`
or `FontFace`. `toTrueType(PDFont)` reads the font's FontFile (Type 1), FontFile2 (TrueType, simple or CIDFontType2)
or FontFile3 (`Type1C`, `CIDFontType0C`, `OpenType`) and builds the cmap from the font's ToUnicode, encoding and
CIDToGIDMap. `toTrueType(byte[], FontKind, Map<Integer, Integer>)` takes the program bytes (`TYPE1` as PFB or as the
raw FontFile stream, `CFF` for bare and CID-keyed CFF, `TRUETYPE` for TrueType or OpenType) and a glyph id to code
point map, where glyph ids are the font's own, and for Type 1 `.notdef` followed by the CharStrings in file order.
Glyph names that the Adobe Glyph List resolves, and for TrueType the font's own Unicode cmap, fill in code points the
map leaves out. Every glyph keeps its glyph id and its advance width. Type 1 and CFF outlines are converted to
TrueType at 1000 units per em through the FontMatrix; TrueType glyphs, hinting (`fpgm`, `prep`, `cvt `) and metrics
are kept as they are, and a missing or truncated `OS/2` is replaced. The font has `glyf`, `loca`, `hmtx`, `hhea`,
`head`, `maxp`, `OS/2`, `post`, `name` and a fresh `cmap` with format 4 and, for code points beyond the BMP, format 12.
A program that cannot be read fails with an `IOException`, and so does one whose charstrings expand past 131,072
tokens for a glyph, 64 nested subroutine calls or 2^27 tokens for the font, or whose converted glyphs need more than
eight times the program's size (at least 16 MB).

## Scanned photos

`PhotoSplitter` (in the core module, package `stirling.software.officeconvert.photosplit`) finds the photos laid on a
flatbed scanner and cuts each one out as its own picture, without OpenCV or any other native library. It replaces
Stirling-PDF's `split_photos.py` and takes the same five settings with the same defaults: `angleThreshold` 10,
`tolerance` 30, `minArea` 10000, `minContourArea` 500 and `borderSize` 0.

```java
import stirling.software.officeconvert.photosplit.PhotoSplitter;
import stirling.software.officeconvert.photosplit.PhotoSplitter.Options;

List<PhotoSplitter.Photo> photos = PhotoSplitter.split(bufferedImage, Options.defaults());
photos.get(0).image();     // the photo, upright, as a BufferedImage
photos.get(0).corners();   // where it lay on the scan, in scan pixels, clockwise from its top left
photos.get(0).angle();     // degrees it was turned to stand upright

List<Path> written = PhotoSplitter.split(Path.of("album.pdf"), Path.of("photos"), Options.defaults()
        .tolerance(30).minArea(10000).minContourArea(500).borderSize(1).dpi(300));
```

The file form reads a PNG, JPEG, BMP, GIF, TIFF (first frame) or PDF; each PDF page is drawn at `dpi` (default 300, the
resolution Stirling-PDF uses) and searched. Photos are written as `name_1.png`, `name_2.png` and so on, numbered on
across pages, and an existing file is never replaced unless `overwrite(true)`. Images over 100 million pixels are
refused; a PDF page that would exceed that is drawn at a lower resolution. SCRIPT padding is included in the
same pixel limit, so a large border cannot bypass it.

An interrupted buffered-image call throws `CancellationException`; the file API reports `InterruptedIOException`
and preserves the thread's interrupt flag. Pixel rows, analysis phases, cropping and PNG encoding have cooperative
checkpoints. A third-party image reader or PDF renderer may finish its current decode before reaching a checkpoint.
Each PNG is encoded to a temporary file beside its target, then published only after encoding succeeds. A failed or
cancelled encode removes that temporary file and preserves an existing target. Crops completed before a later
photo or PDF page fails remain in the output folder; the whole scan is not a transaction. The CLI rejects a batch
before conversion if a possible PNG target is one of its supplied inputs, including file aliases, even with
`--overwrite`; choose a separate output folder for such a batch.

There are two methods:

- `Method.STRAIGHTEN` (the default) models the scanner bed from the scan's border and a coarse grid, so a tinted lid,
  uneven lighting, a lid shadow or photos over the corners and the centre do not upset it. A photo is the area that
  differs from the bed, extended by a fainter pass so white print borders and pale photos are kept whole. Photos
  that touch are told apart by narrowing necks, straight gaps (including shadow-filled gaps), and colour steps along
  shared edges. A text-pattern check leaves out typed pages. Each photo's edges are then fitted to sub-pixel
  accuracy, and the photo is cut out along them and turned level.
  Photos come back in reading order. `borderSize` trims that many pixels from every side; `angleThreshold` is not
  used, because the angle comes from the fitted photo edges. Large scans are searched on a reduced copy limited to
  roughly 4.5 million pixels; edges are refined and crops are taken at the original resolution. The file API writes
  one crop at a time, while the buffered-image API returns all cropped images together.
- `Method.SCRIPT` reproduces `split_photos.py`: background from four corners and the centre, the 9 by 9 dilated
  difference mask, the upright bounding box of every outer contour, and a turn by the median Hough line angle when it
  is at least `angleThreshold`, with the photos in the script's order (bottom of the scan first). Unlike the script,
  it honours `minArea` and `minContourArea` (the script always used 10000 and 500). Its full-resolution masks,
  edge detection and Hough transform can need substantially more memory than the default reduced analysis path;
  the pixel limit is an input bound, not a guaranteed heap or process-memory limit.

`office-convert split-photos <scan.png|scan.pdf>... [-o dir]` runs it from the command line with `--angle-threshold`,
`--tolerance`, `--min-area`, `--min-contour-area`, `--border-size`, `--dpi`, `--method straighten|script`,
`--overwrite` and `-q`; the underscore spellings the script takes (`--min_area`) work too.

Rechecked on 2026-10-27 in offline Docker, with two CPUs, a 1500 MiB container limit and `-Xmx1200m`, against
stored outputs from the original `split_photos.py` in the Stirling-PDF 3.2.0 image (OpenCV 5.0). Settings were
Stirling-PDF's 10, 30, 10000, 500 and border 1. All 99 input scans and their declared JSON and PNG outputs were
checked for completeness and decodability.

The truth sets contain 57 base synthetic scans (201 photos), 26 further generated scans (106 photos: aligned and
angled shared edges, white product borders, Polaroids, rounded and torn edges, dark photos on dark beds, perforated
stamps, typed pages, greyscale and JPEG), and 16 generated layouts using five real photographs (80 photos). The
last set is not a collection of scans from independent scanners.

| Set | Original script: found / tight / exact scans / extra crops | `STRAIGHTEN`: found / tight / exact scans / extra crops |
|---|---|---|
| Base synthetic | 156/201; 30/201; 33/57; 30 | 198/201; 175/201; 54/57; 0 |
| Further generated | 96/106; 12/106; 12/26; 30 | 102/106; 100/106; 24/26; 2 |
| Real-photograph layouts | 76/80; 7/80; 10/16; 14 | 79/80; 78/80; 15/16; 2 |

Found means a truth photo matched by a crop at polygon IoU at least 0.5; tight means IoU at least 0.95. Exact scans
have every truth photo matched and no extra crop. Matching uses the existing greedy IoU evaluator. Mean per-scan IoU for
`STRAIGHTEN` is 0.964 / 0.935 / 0.979 across the three sets, and mean residual angle among matched photos is
0.01 / 0.02 / 0.03 degrees.
The historical colour-pixel MAE is 5.4 / 11.0 / 2.2, versus the script's 37.5 / 36.3 / 33.6. The greyscale fixtures
retain coloured truth thumbnails, so that metric also counts intentionally removed colour; a separate measurement
on the 12 photos in four known greyscale-input fixtures preserves their historical RGB MAE (29.97 for
`STRAIGHTEN`, 52.43 for the script) and adds a greyscale-compatible MAE. Converting both aligned outputs and truth
thumbnails with OpenCV BGR2GRAY gives 1.52 for `STRAIGHTEN` and 34.02 for the script.

`SCRIPT` matches the original script's counts, boxes and angles on all 99 scans. The per-scan mean exact-pixel
matches are 94.37%, 95.43% and 96.43% across the three sets; every differing channel is at most one level away.
OpenCV 5's fixed-point bicubic weights are not reproduced exactly.

Current file-API timings include PNG reading and writing, on generated A4/A3 scans. The time is the best of four
calls at `-Xmx1200m`; memory is measured separately in a fresh process at the smallest successful heap from a fixed
sweep, and is not the resident peak at `-Xmx1200m`. Other jobs shared the host, so timings are descriptive.

| Scan | Pixels | Best of four | Smallest tested heap | RSS at that heap | Crops |
|---|---|---|---|---|---|
| A4, 150 dpi | 2.17 million | 290 ms | 48 MiB | 101 MiB | 4/4 |
| A4, 200 dpi | 3.86 million | 409 ms | 80 MiB | 130 MiB | 4/4 |
| A4, 300 dpi | 8.70 million | 561 ms | 64 MiB | 118 MiB | 4/4 |
| A4, 600 dpi | 34.80 million | 1988 ms | 192 MiB | 239 MiB | 4/4 |
| A3, 600 dpi | 69.61 million | 3871 ms | 320 MiB | 366 MiB | 5/5 |

The analysis scale changes in whole-number steps, which explains why the 300 dpi case needs less heap than the
200 dpi case in this sweep. `STRAIGHTEN` still merges perfectly aligned photos that abut along a whole edge; the
abutting A4 cases are also loose or merged. A product photograph whose pale background matches its white print
border can still lose its pale margin. These generated sets do not establish accuracy on arbitrary scanner output.

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
| `odt`, `rtf`, `text`, `html`, `json`, `markdown` | The same document model as OpenDocument Text, RTF, plain text, HTML, JSON and Markdown |
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
- `OfficeToPdf.Format` has four new values, `PPT`, `TEXT`, `CSV` and `TSV`, for legacy PowerPoint, plain text and
  delimited tables. A caller with an exhaustive `switch` over `Format` needs cases for them.
- `Format.of` returns a format for files it used to refuse: `DOCX` for `.doc`, `.dot`, `.rtf`, `.xml` and the
  OpenDocument, OpenOffice.org 1.x and Pages extensions, `XLSX` for `.xls`, `.xlt`, `.xlsb`, the table formats and
  Numbers, `PPTX` for the presentation, drawing, Visio and Keynote extensions, and `PPT` for `.ppt`, `.pps` and
  `.pot`. It still throws `IllegalArgumentException` for anything else.
- `Format.recognises` accepts every one of those extensions, including any `.xml` file.
- `OfficeToPdf.Options` has three more components, `fonts`, `displayName` and `password`, so its canonical
  constructor takes seven arguments. The 0.1.0 constructors `Options(timeout, fontDirs, maxPages)` and
  `Options(timeout, fontDirs, maxPages, maxScratchBytes)` are kept; a record pattern over `Options` needs the new
  components.
- A new module, `stirling-office-convert-pdfa`, converts PDF to PDF/A.
- `WebFonts` in the pdfa module converts Type 1, CFF and TrueType font programs from PDFs to TrueType web fonts.
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
