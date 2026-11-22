package stirling.software.officeconvert;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

import com.sun.management.GarbageCollectionNotificationInfo;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

// Times PDF to Office conversions of generated text, table, image, vector and long documents.
// Usage: ConversionBench <work dir> [warmups] [runs] [document:format ...]
public final class ConversionBench {

    private static final String[] WORDS = ("the of and to in is that for it as was with be by on not this are or from"
            + " at which but have an they were there would their been has when who will more no if out so said what"
            + " up its about into than them can only other new some could time these two may then first any now such"
            + " like over even most made after also many before must through back years where much way well down")
            .split(" ");

    private static final Map<String, Integer> PAGES = Map.of("text", 60, "tables", 40, "images", 24, "vectors", 24,
            "long", 600);

    private ConversionBench() {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/bench");
        int warmups = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int runs = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        List<String> cases = new ArrayList<>(Arrays.asList(args).subList(Math.min(3, args.length), args.length));
        if (cases.isEmpty()) {
            for (String doc : List.of("text", "tables", "images", "vectors", "long")) {
                for (String format : List.of("docx", "xlsx", "pptx")) {
                    cases.add(doc + ":" + format);
                }
            }
        }
        Files.createDirectories(dir);
        AtomicLong live = liveHeapWatch();
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        System.out.printf("%-14s %8s %8s %8s %10s %9s %8s  %s%n", "case", "ms", "min ms", "cpu ms", "alloc MiB",
                "KiB/page", "heap MiB", "output");
        for (String c : cases) {
            String doc = c.substring(0, c.indexOf(':'));
            Path pdf = dir.resolve(doc + ".pdf");
            if (!Files.exists(pdf)) {
                generate(doc, pdf);
            }
            Path out = dir.resolve(doc + "." + c.substring(c.indexOf(':') + 1));
            for (int i = 0; i < warmups; i++) {
                OfficeConvert.convert(pdf, out);
            }
            long[] ms = new long[runs];
            long[] cpu = new long[runs];
            long[] alloc = new long[runs];
            System.gc();
            live.set(0);
            for (int i = 0; i < runs; i++) {
                long a0 = threads.getTotalThreadAllocatedBytes();
                long c0 = os.getProcessCpuTime();
                long t0 = System.nanoTime();
                OfficeConvert.convert(pdf, out);
                ms[i] = (System.nanoTime() - t0) / 1_000_000;
                cpu[i] = (os.getProcessCpuTime() - c0) / 1_000_000;
                alloc[i] = threads.getTotalThreadAllocatedBytes() - a0;
            }
            Arrays.sort(ms);
            Arrays.sort(cpu);
            Arrays.sort(alloc);
            double mib = alloc[runs / 2] / 1048576.0;
            System.out.printf("%-14s %8d %8d %8d %10.1f %9.0f %8.1f  %s%n", c, ms[runs / 2], ms[0], cpu[runs / 2], mib,
                    mib * 1024 / PAGES.get(doc), live.get() / 1048576.0, digest(out));
        }
    }

    // Largest heap in use just after a collection while measuring, an upper bound on the live set
    private static AtomicLong liveHeapWatch() {
        AtomicLong live = new AtomicLong();
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            ((NotificationEmitter) gc).addNotificationListener((n, h) -> {
                if (GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) {
                    var info = GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
                    long used = info.getGcInfo().getMemoryUsageAfterGc().values().stream()
                            .mapToLong(MemoryUsage::getUsed).sum();
                    live.accumulateAndGet(used, Math::max);
                }
            }, null, null);
        }
        return live;
    }

    // Hash of the package parts except docProps, whose dates change on every run
    private static String digest(Path out) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Map<String, byte[]> parts = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(out))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                if (!e.getName().startsWith("docProps/")) {
                    parts.put(e.getName(), zip.readAllBytes());
                }
            }
        }
        parts.forEach((name, bytes) -> {
            md.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update(bytes);
        });
        return HexFormat.of().formatHex(md.digest()).substring(0, 12);
    }

    private static void generate(String doc, Path pdf) throws IOException {
        try (PDDocument d = new PDDocument()) {
            PDFont body;
            try (InputStream ttf = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                body = PDType0Font.load(d, ttf);
            }
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            Random r = new Random(doc.hashCode());
            for (int p = 0; p < PAGES.get(doc); p++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                d.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    switch (doc) {
                        case "tables" -> tables(cs, body, bold, r, p);
                        case "images" -> images(d, cs, body, r, p);
                        case "vectors" -> vectors(cs, body, r, p);
                        default -> text(cs, body, bold, r, p, doc.equals("long"));
                    }
                }
            }
            d.save(pdf.toFile());
        }
    }

    private static void text(PDPageContentStream cs, PDFont body, PDFont bold, Random r, int p, boolean mixed)
            throws IOException {
        float y = 720;
        show(cs, body, 9, 72, 760, "Annual Report");
        if (p % 3 == 0) {
            show(cs, bold, 18, 72, y, (p / 3 + 1) + ". " + sentence(r, 4));
            y -= 30;
        }
        if (mixed && p % 7 == 4) {
            y = grid(cs, body, bold, r, y, 6, 8);
        }
        boolean columns = mixed && p % 5 == 2;
        while (y > 90) {
            StringBuilder para = new StringBuilder();
            for (int s = 3 + r.nextInt(5); s > 0; s--) {
                para.append(sentence(r, 6 + r.nextInt(12))).append(' ');
            }
            for (String line : wrap(body, 10.5f, para.toString().trim(), columns ? 220 : 468)) {
                if (y < 90) {
                    break;
                }
                show(cs, body, 10.5f, 72, y, line);
                if (columns) {
                    show(cs, body, 10.5f, 320, y, line);
                }
                y -= 13.5f;
            }
            y -= 8;
            if (r.nextInt(6) == 0 && y > 150) {
                show(cs, bold, 12.5f, 72, y, sentence(r, 3));
                y -= 20;
            }
        }
        show(cs, body, 9, 300, 50, String.valueOf(p + 1));
    }

    private static void tables(PDPageContentStream cs, PDFont body, PDFont bold, Random r, int p) throws IOException {
        show(cs, bold, 14, 72, 740, "Schedule " + (p + 1));
        if (p % 2 == 0) {
            grid(cs, body, bold, r, 720, 7, 34);
            return;
        }
        float y = 715;
        for (int row = 0; row < 40; row++, y -= 15.5f) {
            for (int col = 0; col < 6; col++) {
                String s = col == 0 ? word(r) + " " + word(r) : String.format("%,d", r.nextInt(1_000_000));
                float x = col == 0 ? 72 : 200 + col * 62 - body.getStringWidth(s) / 1000 * 9;
                show(cs, row == 0 ? bold : body, 9, x, y, s);
            }
        }
    }

    private static float grid(PDPageContentStream cs, PDFont body, PDFont bold, Random r, float top, int cols,
            int rows) throws IOException {
        float w = 468f / cols;
        float h = 18;
        for (int i = 0; i <= rows; i++) {
            cs.moveTo(72, top - i * h);
            cs.lineTo(72 + w * cols, top - i * h);
        }
        for (int c = 0; c <= cols; c++) {
            cs.moveTo(72 + c * w, top);
            cs.lineTo(72 + c * w, top - rows * h);
        }
        cs.setLineWidth(0.6f);
        cs.stroke();
        for (int i = 0; i < rows; i++) {
            for (int c = 0; c < cols; c++) {
                String s = i == 0 ? "Col " + (c + 1)
                        : c == 0 ? word(r) : String.format("%,d.%02d", r.nextInt(100_000), r.nextInt(100));
                show(cs, i == 0 ? bold : body, 8.5f, 75 + c * w, top - i * h - 12.5f, s);
            }
        }
        return top - rows * h - 20;
    }

    private static void images(PDDocument d, PDPageContentStream cs, PDFont body, Random r, int p) throws IOException {
        BufferedImage photo = new BufferedImage(1600, 1100, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < photo.getHeight(); y++) {
            for (int x = 0; x < photo.getWidth(); x++) {
                int v = (int) (127 + 60 * Math.sin((x + p * 37) / 53.0) + 60 * Math.cos(y / 41.0));
                photo.setRGB(x, y, (v & 255) << 16 | ((v * 3 + x) & 255) << 8 | (y + p * 11) & 255);
            }
        }
        BufferedImage logo = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = logo.createGraphics();
        g.setColor(new Color(20, 80, 160));
        g.fillOval(10, 10, 280, 280);
        g.dispose();
        cs.drawImage(JPEGFactory.createFromImage(d, photo, 0.85f), 72, 400, 468, 322);
        cs.drawImage(LosslessFactory.createFromImage(d, logo), 450, 90, 90, 90);
        float y = 370;
        for (String line : wrap(body, 10.5f, sentence(r, 120), 468)) {
            show(cs, body, 10.5f, 72, y, line);
            y -= 13.5f;
        }
    }

    private static void vectors(PDPageContentStream cs, PDFont body, Random r, int p) throws IOException {
        show(cs, body, 14, 72, 740, "Figure " + (p + 1));
        for (int i = 0; i < 40; i++) {
            cs.setNonStrokingColor(new Color(r.nextInt(200), r.nextInt(200), 200));
            cs.addRect(80 + i * 11, 450, 8, 20 + r.nextInt(240));
            cs.fill();
        }
        cs.setStrokingColor(Color.RED);
        cs.moveTo(80, 300);
        for (int i = 1; i < 1500; i++) {
            cs.lineTo(80 + i * 0.3f, 300 + 60 * (float) Math.sin(i / 30.0) + r.nextInt(10));
        }
        cs.stroke();
        cs.setNonStrokingColor(Color.BLUE);
        for (int i = 0; i < 600; i++) {
            float x = 80 + r.nextInt(450);
            float y = 90 + r.nextInt(130);
            cs.moveTo(x + 1.2f, y);
            cs.curveTo(x + 1.2f, y + 1.2f, x - 1.2f, y + 1.2f, x - 1.2f, y);
            cs.curveTo(x - 1.2f, y - 1.2f, x + 1.2f, y - 1.2f, x + 1.2f, y);
            cs.fill();
        }
        for (int i = 0; i < 10; i++) {
            show(cs, body, 7, 60, 450 + i * 26, String.valueOf(i * 10));
        }
    }

    private static void show(PDPageContentStream cs, PDFont font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private static List<String> wrap(PDFont font, float size, String text, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String w : text.split(" ")) {
            String longer = line.isEmpty() ? w : line + " " + w;
            if (!line.isEmpty() && font.getStringWidth(longer) / 1000 * size > width) {
                lines.add(line);
                line = w;
            } else {
                line = longer;
            }
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    private static String sentence(Random r, int words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            String w = word(r);
            sb.append(i == 0 ? Character.toUpperCase(w.charAt(0)) + w.substring(1) : " " + w);
        }
        return sb.append('.').toString();
    }

    private static String word(Random r) {
        return WORDS[r.nextInt(WORDS.length)];
    }
}
