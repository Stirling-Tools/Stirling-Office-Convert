# Conversion performance measurements, 4 November 2026

These measurements compare the changes in this branch with unchanged v0.3.3,
revision `68bd2ad26656c9a15f5f834e9a0f0d0c99f4e96c`. They measure complete public API
calls, including input loading and output writing, on repository-owned fixtures.
Normal conversion settings, resolution and document content are preserved.

## Timing

Windows 11, Temurin JDK 25, logical CPU affinity 0–3, separate JVMs with a 1 GiB
maximum heap, 128 MiB initial heap and Serial GC. No benchmark builds overlapped
these runs. Other host activity can affect timings.

Each case has 12 warmup calls and 25 measured calls per JVM. Build order was
baseline, candidate, candidate, baseline, baseline, candidate. Values are medians
of three JVM medians, rather than treating calls within one JVM as independent
experiments.

| Conversion | v0.3.3, ms | Candidate, ms | Speedup |
| --- | ---: | ---: | ---: |
| 100-page manual PDF → DOCX | 714.503 | 684.884 | 1.043× |
| 100-page manual PDF → PPTX | 1140.026 | 812.793 | 1.403× |
| 20-page illustrated PDF → DOCX | 429.805 | 215.836 | 1.991× |
| 20-page illustrated PDF → PPTX | 441.877 | 242.609 | 1.821× |

The manual-to-DOCX fork ranges overlap substantially; its small median difference
does not establish a repeatable speedup. Office-to-PDF timings are also generally
neutral; these changes do not promise a speedup for every format or document.

[Call measurements](office-convert-2026-11-04.csv) include warmups (`iteration <= 0`),
elapsed time, allocation counters, GC count/time and output sizes. The
[evidence JSON](office-convert-2026-11-04.json) includes fork ranges, fixture and
artifact hashes, and per-case memory results. Allocation counters cover all JVM
threads, including completed worker threads.

## Memory

The memory retest used the same CPU and JVM settings, 55 cases covering all 41
conversion-family cases plus larger Office/PDF and image-heavy RTF cases. Each
build ran in two separate JVMs, in baseline/pre-fix/candidate/candidate/pre-fix/baseline
order. The pre-fix build contains these optimisations before the overflow fix.
Ordinary cases used two warmups and five measured calls; stress cases used four
warmups and eight measured calls.

| Build | Peak working set, MiB, two JVMs | Median total allocation over measured case workload, MiB |
| --- | --- | ---: |
| v0.3.3 | 698.40, 683.09 | 47639.73 |
| Pre-fix optimisation build | 688.13, 711.88 | 42852.91 |
| Candidate | 687.68, 709.57 | 42918.89 |

The candidate allocated 9.91% less over this workload than v0.3.3. The largest
per-case median allocation increase was 0.961% for manual PDF → DOCX. Median
natural GC time over the case workload was 1499 ms at baseline and 1412.5 ms for
the candidate. Small per-case differences and GC variation remain in the JSON.

Each JVM then ran four warmup and 32 measured mixed batches, with eight conversion
requests and four workers per batch: 1536 measured soak conversions overall.
Explicit full GC at nine checkpoints was outside timed regions and natural-GC
metrics. Candidate settled heap after closing workers was 111.63–112.55 MiB,
versus 112.53–112.55 MiB for baseline. No sustained growth was observed. Worker
threads were released; direct/mapped buffer memory was zero at candidate soak
checkpoints. Windows process memory was sampled every 20 ms, with OS lifetime
peak counters also recorded. Heap pool peak sums are conservative sums of separate
pool maxima, not simultaneous heap peaks.

This bounded test found no material memory regression; it cannot prove that all
possible documents or arbitrarily long sessions are leak-free.

## Output and correctness validation

- `./gradlew.bat check javadoc --max-workers=1 --no-parallel --console=plain`:
  4942 tests, zero failures/errors, six skipped; includes packaged CLI checks.
- Fresh baseline/candidate output comparisons passed for all 41 family cases,
  240 PDF holdout cases and the four large timing cases.
- All 378 output comparisons from the memory runs passed.
- PDF comparisons include page count/dimensions, rotation, extracted text, links
  and rendered pixels. Package comparisons include XML structure and decoded
  image dimensions/pixels; only known generated metadata dates are normalised.
  Authored publication/content dates remain visible. RTF and other non-package
  outputs are compared as bytes except identified generated timestamps.
- Overflow regression coverage includes public PDF → DOCX output, finite endpoints
  with overflowing intermediate matrices, threshold boundaries and non-affine
  transforms. Extreme transforms retain the original matrix validation behavior.

These are equivalence checks against v0.3.3 output, not independent proof of source
fidelity. The fixtures are synthetic/repository-owned; external Microsoft Office
and LibreOffice renderers were not used. Full raw logs, output documents and
benchmark scripts remain in the local benchmark archive; the CSV/JSON here are a
portable measurement record, not a complete fixture-generation harness.
