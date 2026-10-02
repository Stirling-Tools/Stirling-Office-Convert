# Stirling Office Convert

Convert between PDF and editable Office documents in plain Java, built on PDFBox and Apache POI.
The converter needs no native libraries or external processes. It also converts PDF to PDF/A.

| Conversion | Formats |
|---|---|
| PDF to documents | DOCX, ODT, flat ODT (`.fodt` / `.xml`), RTF (`.rtf` / `.doc`), plain text |
| PDF to presentations | PPTX, ODP, PPT (via the `legacy` module) |
| PDF to spreadsheets | XLSX, ODS |
| Office to PDF | DOCX, PPTX, XLSX, legacy DOC/XLS/PPT, XLSB, RTF, OpenDocument, Office XML, Visio, text and table formats |
| PDF to PDF/A | 1a, 1b, 2a, 2b, 2u, 3a, 3b, 3u |

PDF-to-Office documents contain real paragraphs, headings, lists, tables, footnotes, headers and footers.
Office-to-PDF reads saved content without running macros, scripts or formulas or fetching linked resources.
iWork files use their embedded PDF or picture preview. PDF/A level a requires an already tagged PDF;
the converter does not create a structure tree for an untagged document.

See the [usage guide](docs/usage.md) for the complete format list, limitations, API options, fonts,
JPEG 2000 reader registration, timeout behavior and the LibreOffice comparison tester.

## Requirements

Java 25 or later. The 0.2.0 changes are unreleased; the checkout's default version remains
`0.1.0-SNAPSHOT`. Build from source to use the new modules.

## Build

```bash
./gradlew build
./gradlew publishToMavenLocal
```

The second command installs the library modules locally, including sources, javadoc and POMs.

## CLI

```bash
java -jar cli/build/libs/stirling-office-convert-cli.jar in.pdf -o out.docx
java -jar cli/build/libs/stirling-office-convert-cli.jar report.docx slides.pptx book.xlsx -o outdir/
java -jar cli/build/libs/stirling-office-convert-cli.jar folder/ -o outdir/ --format pdf
java -jar cli/build/libs/stirling-office-convert-cli.jar in.pdf --pdfa 2b -o archive.pdf
```

The output extension picks the PDF-to-Office format. Office inputs produce PDFs. A folder can contain both
PDFs and Office files; `--format pdf` selects Office inputs and includes text and CSV files.
Inputs are never overwritten. Existing outputs are skipped unless `--overwrite` is given, and colliding
input names retain their extensions in the output name. A failure, skipped input or empty folder exits with status 1.

| Option | Description |
|---|---|
| `--format ext` | Select the output format for a folder |
| `--pages a-b` | PDF page range |
| `--password p` | Password for a protected input |
| `--timeout s` | Office-to-PDF or PDF/A timeout; 0 disables it |
| `--max-pages n` | Office-to-PDF page limit; 0 converts all pages |
| `--fonts dir` | Add a font directory for Office-to-PDF or PDF/A |
| `--pictures compact\|lossless` | PDF-to-Office picture storage |
| `--sheets page\|table\|single` | PDF-to-spreadsheet layout |
| `--overwrite` | Replace existing output files |
| `-q` | Quiet |

The [usage guide](docs/usage.md#build-and-run) documents the remaining options and exit statuses.

## Library

The main API entry points are `OfficeConvert`, `OfficeToPdf` and `PdfToPdfA`.
Use `FontSet` to share configured fonts between Office-to-PDF and PDF/A conversions.

```java
import java.nio.file.Path;
import stirling.software.officeconvert.OfficeConvert;
import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.pdfa.PdfToPdfA;
import stirling.software.officeconvert.pdfa.PdfALevel;

OfficeConvert.convert(Path.of("in.pdf"), Path.of("out.docx"));
OfficeToPdf.convert(Path.of("in.docx"), Path.of("out.pdf"));
PdfToPdfA.convert(Path.of("in.pdf"), Path.of("archive.pdf"),
        PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
```

The library modules use the `com.stirling` group and the artifact names listed below.
Use the locally installed `0.1.0-SNAPSHOT` artifacts when building against this unreleased checkout.
See [library use](docs/usage.md#library-use), [Office to PDF](docs/usage.md#office-to-pdf) and
[PDF to PDF/A](docs/usage.md#pdf-to-pdfa) for dependency and option details.

Different documents can convert in parallel. A single `PDDocument` must not be used on two threads.
Path overloads move completed output into place. The usage guide explains the ownership and timeout rules
for caller-owned documents and streams.

## Test app

`app` provides a local page for trying PDF-to-Office and Office-to-PDF conversions. It is not published to Maven.

```bash
./gradlew :app:jar
java -jar app/build/libs/stirling-office-convert-app.jar
```

Open `http://localhost:8177`. The [usage guide](docs/usage.md#local-test-app) covers the HTTP API,
Docker demo and optional LibreOffice comparison tester.

## Project layout

| Module | Purpose | Library artifact |
|---|---|---|
| `core` | PDF to editable Office; shared image decoder and memory admission | `stirling-office-convert` |
| `legacy` | PowerPoint 97-2003 (`.ppt`) writer | `stirling-office-convert-legacy` |
| `topdf` | Office to PDF | `stirling-office-convert-topdf` |
| `pdfa` | PDF to PDF/A | `stirling-office-convert-pdfa` |
| `cli` | Command line tool | |
| `app` | Local test web app | |

## Tests

```bash
./gradlew check :cli:jar :app:jar
```

CI builds, tests and generates javadoc on Linux, macOS and Windows with Java 25.
Tests generate their document fixtures in code, including JPEG 2000 pixel coverage and active-content guards.
See [tests](docs/usage.md#tests) for details.

## License

MIT, see [LICENSE](LICENSE).
