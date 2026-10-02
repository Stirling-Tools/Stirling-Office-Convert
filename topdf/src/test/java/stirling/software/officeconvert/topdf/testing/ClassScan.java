package stirling.software.officeconvert.topdf.testing;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ClassScan {

    public record Refs(String name, Set<String> classes, Set<String> members, List<String> strings, Set<String> methods) {

        public Refs(String name, Set<String> classes, Set<String> members, List<String> strings) {
            this(name, classes, members, strings, Set.of());
        }

        public Refs as(String otherName) {
            return new Refs(otherName, classes, members, strings, methods);
        }
    }

    public static final Set<String> ALLOWED_NET = Set.of("java/net/URI", "java/net/URISyntaxException");

    public static final List<String> FORBIDDEN_PREFIXES = List.of("javax/net/", "javax/script/", "javax/naming/",
            "java/rmi/", "javax/xml/transform/", "jdk/net/", "sun/net/", "com/sun/net/", "java/lang/ProcessBuilder",
            "java/lang/ProcessHandle", "java/awt/Desktop", "java/nio/channels/SocketChannel",
            "java/nio/channels/ServerSocketChannel", "java/nio/channels/DatagramChannel",
            "java/nio/channels/AsynchronousSocketChannel", "java/nio/channels/AsynchronousServerSocketChannel",
            "javax/tools/", "jdk/jshell/", "org/apache/batik/", "org/mozilla/javascript/", "java/beans/",
            "java/io/ObjectInputStream", "javax/xml/validation/", "javax/print/", "java/awt/print/",
            "java/sql/Driver", "javax/sql/", "javax/management/remote/", "javax/swing/text/html/",
            "javax/swing/JEditorPane", "javax/swing/ImageIcon", "javax/imageio/spi/IIORegistry",
            "com/sun/org/apache/xalan/", "org/apache/xalan/", "net/sf/saxon/", "org/apache/xmlbeans/impl/tool/",
            "org/apache/poi/xslf/draw/", "org/apache/poi/xslf/util/", "org/apache/poi/poifs/crypt/dsig/",
            "org/apache/poi/sl/image/ImageHeaderBitmap", "org/apache/poi/ss/util/ImageUtils",
            "org/apache/poi/ss/usermodel/FormulaEvaluator", "org/apache/poi/ss/formula/BaseFormulaEvaluator",
            "org/apache/poi/ss/formula/WorkbookEvaluator", "org/apache/poi/ss/formula/ConditionalFormattingEvaluator",
            "org/apache/poi/ss/formula/DataValidationEvaluator", "org/apache/poi/ss/formula/eval/",
            "org/apache/poi/ss/formula/functions/", "org/apache/poi/ss/formula/atp/", "org/apache/poi/ss/formula/udf/",
            "org/apache/poi/xssf/usermodel/XSSFFormulaEvaluator", "org/apache/poi/xssf/usermodel/BaseXSSFFormulaEvaluator",
            "org/apache/poi/xssf/streaming/SXSSFFormulaEvaluator", "org/apache/poi/hssf/usermodel/HSSFFormulaEvaluator");

    public static final Set<String> FORBIDDEN_MEMBERS = Set.of("java/lang/Runtime.exec", "java/lang/Runtime.load",
            "java/lang/Runtime.loadLibrary", "java/lang/System.load", "java/lang/System.loadLibrary",
            "java/lang/Class.forName", "java/lang/ClassLoader.loadClass", "java/lang/ClassLoader.defineClass",
            "java/lang/invoke/MethodHandles$Lookup.findClass", "java/lang/invoke/MethodHandles$Lookup.defineClass",
            "java/lang/invoke/MethodHandles$Lookup.defineHiddenClass", "java/net/URL.openConnection",
            "java/net/URL.openStream", "java/net/URL.getContent", "java/awt/Toolkit.getImage",
            "java/awt/Toolkit.createImage", "javax/imageio/ImageIO.read", "javax/imageio/ImageIO.getImageReaders",
            "javax/imageio/ImageIO.getImageReadersBySuffix", "javax/imageio/ImageIO.getImageReadersByMIMEType",
            "javax/imageio/ImageIO.scanForPlugins", "org/apache/xmlbeans/XmlBeans.compileXsd",
            "org/apache/xmlbeans/XmlBeans.compileXmlBeans", "org/apache/poi/xslf/usermodel/XSLFPictureShape.addSvgImage");

    public static final Set<String> FORBIDDEN_MEMBER_NAMES = Set.of("createFormulaEvaluator",
            "setForceFormulaRecalculation", "evaluateAll", "evaluateAllFormulaCells", "evaluateFormulaCell",
            "evaluateFormulaCellEnum", "evaluateInCell", "execQuery");

    public static final Set<String> POI_PICTURE_SIZE_HELPERS = Set.of("getImageDimension",
            "getImageDimensionInPixels", "getPreferredSize", "resize", "getChecksum", "findPictureData");

    private static final String IO = "stirling/software/officeconvert/topdf/io/";

    public static final Set<String> POI_DRAWING_CLASSES = Set.of(IO + "SafeImageRenderer", IO + "Metafiles");

    public static final List<String> POI_DRAWING_TYPES = List.of("org/apache/poi/sl/draw/DrawFactory",
            "org/apache/poi/sl/draw/DrawPictureShape", "org/apache/poi/sl/draw/DrawTexturePaint",
            "org/apache/poi/sl/draw/BitmapImageRenderer", "org/apache/poi/sl/draw/ImageRenderer",
            "org/apache/poi/hemf/draw/", "org/apache/poi/hwmf/draw/", "org/apache/poi/hemf/usermodel/HemfPicture",
            "org/apache/poi/hwmf/usermodel/HwmfPicture");

    public static final Set<String> PICTURE_DECODING_CLASSES = Set.of(IO + "PictureDecoder");

    public static final Set<String> PICTURE_DECODING_MEMBERS = Set.of(
            "javax/imageio/ImageIO.getImageReadersByFormatName",
            "org/apache/pdfbox/pdmodel/graphics/image/PDImageXObject.createFromByteArray",
            "org/apache/pdfbox/pdmodel/graphics/image/PDImageXObject.createFromFile",
            "org/apache/pdfbox/pdmodel/graphics/image/PDImageXObject.createFromFileByContent",
            "org/apache/pdfbox/pdmodel/graphics/image/PDImageXObject.createFromFileByExtension",
            "org/apache/pdfbox/pdmodel/graphics/image/JPEGFactory.createFromByteArray",
            "org/apache/pdfbox/pdmodel/graphics/image/JPEGFactory.createFromStream");

    private static final String TOPDF = "stirling/software/officeconvert/topdf/";

    public static final List<String> FORMAT_PACKAGES = Stream.of("docx", "pptx", "xlsx", "xls", "ppt", "doc", "doc6",
            "rtf", "odf", "ooo1", "wordml", "sml", "flat", "xlsb", "biff5", "lotus", "grid", "vsdx", "iwork", "text")
            .map(p -> TOPDF + p + "/").toList();

    public static final List<String> FORMAT_FILE_TYPES = List.of("java/io/File", "java/io/FileInputStream",
            "java/io/FileOutputStream", "java/io/FileReader", "java/io/FileWriter", "java/io/RandomAccessFile",
            "java/nio/file/Files", "java/nio/file/Paths", "java/nio/file/FileSystems", "java/nio/file/FileSystem",
            "java/nio/channels/FileChannel", "java/nio/channels/AsynchronousFileChannel", "java/util/zip/ZipFile",
            "java/util/jar/JarFile", "java/util/logging/FileHandler", "javax/imageio/stream/FileImageInputStream",
            "javax/imageio/stream/FileCacheImageInputStream", "org/apache/pdfbox/io/RandomAccessReadBufferedFile",
            "org/apache/pdfbox/io/RandomAccessReadMemoryMappedFile", "org/apache/pdfbox/Loader",
            "org/apache/poi/openxml4j/opc/ZipPackage", "org/apache/poi/openxml4j/util/ZipSecureFile",
            "org/apache/poi/poifs/filesystem/FileBackedDataSource", "org/apache/poi/util/TempFile");

    public static final List<String> FORMAT_PARSER_TYPES = List.of("javax/xml/parsers/DocumentBuilderFactory",
            "javax/xml/parsers/SAXParserFactory", "javax/xml/stream/XMLInputFactory",
            "javax/xml/transform/TransformerFactory", "javax/xml/validation/SchemaFactory",
            "org/xml/sax/helpers/XMLReaderFactory", "org/apache/poi/util/XMLHelper",
            "org/apache/poi/ooxml/util/DocumentHelper", "org/apache/xmlbeans/impl/common/SAXHelper");

    public static final Set<String> FORMAT_FORBIDDEN_MEMBERS = Set.of("java/nio/file/Path.of",
            "java/nio/file/Path.resolve", "java/nio/file/Path.resolveSibling", "java/nio/file/Path.toFile",
            "java/nio/file/Path.toUri", "java/nio/file/Path.getFileSystem", "java/nio/file/Path.register",
            "java/nio/file/Path.toRealPath", "org/apache/poi/openxml4j/opc/OPCPackage.open",
            "org/apache/poi/openxml4j/opc/OPCPackage.openOrCreate", "org/apache/poi/openxml4j/opc/OPCPackage.create",
            "org/apache/poi/ss/usermodel/WorkbookFactory.create", "org/apache/poi/sl/usermodel/SlideShowFactory.create",
            "org/apache/poi/extractor/ExtractorFactory.createExtractor");

    public static final List<String> FORMAT_FORBIDDEN_METHODS = List.of(
            "org/apache/poi/xssf/usermodel/XSSFWorkbook.<init>(Ljava/lang/String;",
            "org/apache/poi/xssf/streaming/SXSSFWorkbook.<init>(", "java/util/Scanner.<init>(Ljava/nio/file/Path;",
            "java/util/Scanner.<init>(Ljava/lang/String;Ljava/lang/String;", "java/util/Formatter.<init>(Ljava/lang/String;",
            "java/io/PrintStream.<init>(Ljava/lang/String;", "java/io/PrintWriter.<init>(Ljava/lang/String;");

    public static final Map<String, String> NETWORK_CAPABLE = network();

    private static final Pattern NET_TYPE = Pattern.compile("java[/.]net[/.][A-Za-z0-9_$/.]+");

    private ClassScan() {}

    public static Map<String, byte[]> classes(Path dirOrJar) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        if (Files.isDirectory(dirOrJar)) {
            try (Stream<Path> files = Files.walk(dirOrJar)) {
                for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                    out.put(dirOrJar.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                }
            }
            return out;
        }
        try (ZipFile zip = new ZipFile(dirOrJar.toFile())) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                if (e.getName().endsWith(".class") && !e.getName().endsWith("module-info.class")) {
                    try (InputStream in = zip.getInputStream(e)) {
                        out.put(e.getName(), in.readAllBytes());
                    }
                }
            }
        }
        return out;
    }

    public static Refs read(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("Not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf = new String[count];
        int[] tags = new int[count];
        int[][] refs = new int[count][];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> utf[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 7, 8, 16, 19, 20 -> refs[i] = new int[] {in.readUnsignedShort()};
                case 9, 10, 11, 12, 17, 18 -> refs[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                case 15 -> {
                    in.readUnsignedByte();
                    refs[i] = new int[] {in.readUnsignedShort()};
                }
                default -> throw new IOException("Unknown constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();
        int self = in.readUnsignedShort();
        Set<String> classes = new LinkedHashSet<>();
        Set<String> members = new LinkedHashSet<>();
        Set<String> methods = new LinkedHashSet<>();
        List<String> strings = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            if (tags[i] == 1) {
                strings.add(utf[i]);
            } else if (tags[i] == 7) {
                classes.add(utf[refs[i][0]]);
            } else if (tags[i] == 9 || tags[i] == 10 || tags[i] == 11) {
                String owner = utf[refs[refs[i][0]][0]];
                String name = utf[refs[refs[i][1]][0]];
                members.add(owner + "." + name);
                methods.add(owner + "." + name + utf[refs[refs[i][1]][1]]);
            }
        }
        return new Refs(utf[refs[self][0]], classes, members, strings, methods);
    }

    public static List<String> ownCodeViolations(Refs r) {
        Set<String> out = new LinkedHashSet<>();
        boolean poiDrawing = inside(r.name(), POI_DRAWING_CLASSES);
        boolean decoding = inside(r.name(), PICTURE_DECODING_CLASSES);
        for (String s : r.strings()) {
            Matcher m = NET_TYPE.matcher(s);
            while (m.find()) {
                String type = m.group().replace('.', '/');
                if (!ALLOWED_NET.contains(type) && !isAllowedNetPrefix(type)) {
                    out.add("uses " + m.group());
                }
            }
            for (String prefix : FORBIDDEN_PREFIXES) {
                if (s.contains(prefix) || s.contains(prefix.replace('/', '.'))) {
                    out.add("uses " + prefix);
                }
            }
            for (String type : POI_DRAWING_TYPES) {
                if (!poiDrawing && (s.contains(type) || s.contains(type.replace('/', '.')))) {
                    out.add("uses " + type + " outside SafeImageRenderer");
                }
            }
        }
        if (inFormatPackage(r.name())) {
            out.addAll(formatViolations(r));
        }
        for (String m : r.members()) {
            int dot = m.lastIndexOf('.');
            String owner = m.substring(0, dot);
            String name = m.substring(dot + 1);
            boolean poi = owner.startsWith("org/apache/poi/");
            if (FORBIDDEN_MEMBERS.contains(m) || FORBIDDEN_MEMBER_NAMES.contains(name)
                    || poi && (POI_PICTURE_SIZE_HELPERS.contains(name) || name.equals("evaluate"))) {
                out.add("calls " + m);
            }
            if (!poiDrawing && poi && (name.startsWith("draw") || name.equals("getImageRenderer")
                    || name.equals("loadImage") || name.equals("IMAGE_RENDERER")
                    || m.equals("org/apache/poi/sl/draw/DrawPaint.getPaint"))) {
                out.add("calls " + m + " outside SafeImageRenderer");
            }
            if (!decoding && PICTURE_DECODING_MEMBERS.contains(m)) {
                out.add("calls " + m + " outside PictureDecoder");
            }
        }
        return new ArrayList<>(out);
    }

    public static boolean inFormatPackage(String className) {
        for (String p : FORMAT_PACKAGES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> formatViolations(Refs r) {
        Set<String> out = new LinkedHashSet<>();
        for (String type : FORMAT_FILE_TYPES) {
            if (mentions(r, type)) {
                out.add("opens files: " + type + " (only the facade and topdf.io touch the source path)");
            }
        }
        for (String type : FORMAT_PARSER_TYPES) {
            if (mentions(r, type)) {
                out.add("makes its own XML parser: " + type + " (use io.SecureXml or io.PoiPackages)");
            }
        }
        for (String m : r.members()) {
            String owner = m.substring(0, m.lastIndexOf('.'));
            if (FORMAT_FORBIDDEN_MEMBERS.contains(m) || FORMAT_FILE_TYPES.contains(owner)) {
                out.add("opens files: calls " + m + " (only the facade and topdf.io touch the source path)");
            }
        }
        for (String m : r.methods()) {
            for (String prefix : FORMAT_FORBIDDEN_METHODS) {
                if (m.startsWith(prefix)) {
                    out.add("opens files: calls " + m + " (only the facade and topdf.io touch the source path)");
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean mentions(Refs r, String type) {
        if (r.classes().contains(type)) {
            return true;
        }
        String descriptor = "L" + type + ";";
        String dotted = type.replace('/', '.');
        for (String s : r.strings()) {
            if (s.contains(descriptor) || s.equals(type) || s.equals(dotted)) {
                return true;
            }
        }
        for (String m : r.members()) {
            if (m.startsWith(type + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean inside(String className, Set<String> owners) {
        for (String owner : owners) {
            if (className.equals(owner) || className.startsWith(owner + "$")) {
                return true;
            }
        }
        return false;
    }

    public static List<String> networkCapable(Refs r) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : r.classes()) {
            for (Map.Entry<String, String> e : NETWORK_CAPABLE.entrySet()) {
                if (c.equals(e.getKey()) || e.getKey().endsWith("/") && c.startsWith(e.getKey())) {
                    out.add(e.getValue());
                }
            }
        }
        for (String m : r.members()) {
            String label = NETWORK_CAPABLE.get(m);
            if (label != null) {
                out.add(label);
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean isAllowedNetPrefix(String type) {
        for (String allowed : ALLOWED_NET) {
            if (type.equals(allowed) || type.startsWith(allowed + "/") || type.startsWith(allowed + "$")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> network() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("java/net/Socket", "socket");
        m.put("java/net/ServerSocket", "server socket");
        m.put("java/net/DatagramSocket", "datagram socket");
        m.put("java/net/URLConnection", "URL connection");
        m.put("java/net/HttpURLConnection", "HTTP connection");
        m.put("java/net/URL.openConnection", "URL.openConnection");
        m.put("java/net/URL.openStream", "URL.openStream");
        m.put("java/net/URL.getContent", "URL.getContent");
        m.put("java/net/InetAddress.getByName", "DNS lookup");
        m.put("java/net/InetAddress.getAllByName", "DNS lookup");
        m.put("java/net/InetAddress.getLocalHost", "local host lookup");
        m.put("java/net/http/", "java.net.http client");
        m.put("javax/net/", "SSL sockets");
        m.put("java/nio/channels/SocketChannel", "socket channel");
        m.put("javax/naming/", "JNDI");
        m.put("java/rmi/", "RMI");
        m.put("javax/script/", "script engine");
        m.put("java/lang/ProcessBuilder", "process launch");
        m.put("java/lang/Runtime.exec", "process launch");
        m.put("javax/xml/transform/TransformerFactory.newTransformer", "XSLT transformer");
        m.put("java/awt/Desktop", "desktop browse");
        m.put("org/apache/batik/", "Batik SVG");
        m.put("javax/imageio/ImageIO.read", "ImageIO registry, any installed plugin");
        m.put("javax/imageio/ImageIO.getImageReaders", "ImageIO registry, any installed plugin");
        m.put("javax/imageio/ImageIO.getImageReadersByFormatName", "ImageIO registry by format name");
        m.put("org/apache/poi/sl/draw/DrawPictureShape.getImageRenderer", "POI ImageRenderer ServiceLoader");
        return m;
    }
}
