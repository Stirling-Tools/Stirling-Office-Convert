package stirling.software.officeconvert.app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpServer;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.io.PoiXml;

public final class App {

    private static final Logger[] QUIET = {Logger.getLogger("org.apache.pdfbox"), Logger.getLogger("org.apache.fontbox")};

    private App() {}

    public static void main(String[] args) throws IOException {
        for (Logger l : QUIET) {
            l.setLevel(Level.OFF);
        }
        Limits limits = Limits.fromEnvironment(args);
        limits.applyServerSettings();
        PoiXml.raiseProcessLimits();
        Thread.ofPlatform().daemon().name("office-warm-up").start(() -> OfficeToPdf.warmUp(OfficeToPdf.Format.values()));
        LibreOffice libreOffice = LibreOffice.find(limits.libreOffice(), limits.libreOfficeTemplate(),
                limits.libreOfficeConcurrent());
        ConvertHandler convert = new ConvertHandler(limits, libreOffice);
        HttpServer server = HttpServer.create(new InetSocketAddress(limits.host(), limits.port()), 64);
        server.createContext("/", new PageHandler(limits, libreOffice, convert::healthy));
        server.createContext("/convert", convert);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Stirling Office Convert on http://" + limits.host() + ":" + limits.port() + " " + limits);
        System.out.println(libreOffice == null ? "LibreOffice engine: off"
                : "LibreOffice engine: " + libreOffice.executable() + " (" + libreOffice.slots() + " at a time), to compare against");
    }
}
