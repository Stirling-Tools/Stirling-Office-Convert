package stirling.software.officeconvert.topdf.xlsx;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.pdf.PageSize;

public final class XlsxRenderer {

    static final int MAX_PLANNED_PAGES = 100_000;

    private XlsxRenderer() {}

    public static void render(Path source, RenderJob job) throws IOException {
        WorkbookModel workbook = new WorkbookModel(job);
        Book book = new Book(job, workbook);
        List<Object> plans = new ArrayList<>();
        int budget = job.maxPages() > 0 ? job.maxPages() + 1 : MAX_PLANNED_PAGES;
        for (WorkbookModel.SheetRef ref : workbook.sheets) {
            job.checkpoint();
            if (ref.visible() && ref.chartsheet()) {
                ChartSheet chart = ChartSheet.read(job, ref);
                if (chart != null) {
                    plans.add(chart);
                    budget--;
                }
                continue;
            }
            if (!ref.visible() || !ref.worksheet()) {
                continue;
            }
            if (budget <= 0) {
                job.truncate();
                break;
            }
            try {
                SheetPlan plan = new SheetPlan(book, ref, job, budget);
                budget -= plan.pages.size();
                plans.add(plan);
            } catch (RenderJob.PageLimitReached e) {
                throw e;
            } catch (RuntimeException e) {
                job.warn("Sheet " + ref.name() + " could not be read and was left out");
            }
        }
        int total = 0;
        for (Object p : plans) {
            total += p instanceof SheetPlan s ? s.pages.size() : 1;
        }
        String file = fileName(source);
        LocalDateTime now = LocalDateTime.now();
        String date = book.formatter().dates().date(now);
        String time = book.formatter().dates().time(now);
        PagePainter painter = new PagePainter(book);
        int number = 0;
        PageSize first = null;
        for (Object entry : plans) {
            if (entry instanceof ChartSheet chart) {
                number++;
                chart.print(job, workbook.themePart);
                continue;
            }
            SheetPlan plan = (SheetPlan) entry;
            if (first == null) {
                first = plan.setup.output();
            }
            if (plan.setup.firstPageNumber() != PageSetup.AUTO_FIRST_PAGE) {
                number = plan.setup.firstPageNumber() - 1;
            }
            for (int i = 0; i < plan.pages.size(); i++) {
                number++;
                painter.paint(plan, plan.pages.get(i), i,
                        new HeaderFooterText.Context(number, total, plan.name, file, date, time));
            }
        }
        if (job.pageCount() == 0) {
            job.newPage(first == null ? PageSetup.DEFAULT_PAPER : first).close();
        }
    }

    private static String fileName(Path source) {
        if (source == null || source.getFileName() == null) {
            return "";
        }
        return source.getFileName().toString();
    }
}
