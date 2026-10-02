package stirling.software.officeconvert.topdf.testing;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Scanner;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.transform.TransformerFactory;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class FormatPackageProbe {

    private FormatPackageProbe() {}

    static List<Object> files(Path source, String named) throws Exception {
        List<Object> out = List.of(new File(named), new FileInputStream(named), new RandomAccessFile(named, "r"),
                Files.newInputStream(source), Files.newByteChannel(source), Files.readAllBytes(source));
        return List.of(out, Paths.get(named), Path.of(named), source.resolve(named), source.toFile(),
                new Scanner(source), new PrintWriter(named));
    }

    static List<Object> parsers(String named) throws Exception {
        return List.of(DocumentBuilderFactory.newInstance(), SAXParserFactory.newInstance(), XMLInputFactory.newInstance(),
                TransformerFactory.newInstance(), XMLHelper.newDocumentBuilder(), OPCPackage.open(named), new XSSFWorkbook(named));
    }
}
