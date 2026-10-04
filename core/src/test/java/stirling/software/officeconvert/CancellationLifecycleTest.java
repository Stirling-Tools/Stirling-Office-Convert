package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class CancellationLifecycleTest {
    @Test
    void largeValidDurationDoesNotOverflowAfterStartingWorker() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            OfficeConvert.convert(doc, out, OfficeConvert.Format.DOCX,
                    OfficeConvert.Settings.defaults().timeout(Duration.ofDays(200_000)));
            assertEquals('P', out.toByteArray()[0]);
        }
    }

    @Test
    void callerInterruptionWaitsForCooperativeWorkerCleanup() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean();
        AtomicBoolean cleanedOnReturn = new AtomicBoolean();
        AtomicBoolean interruptedOnReturn = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        OutputStream sink = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                if (cleaned.get()) {
                    throw new InterruptedIOException("Sink already cancelled");
                }
                writing.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException again) {
                        Thread.currentThread().interrupt();
                    }
                    cleaned.set(true);
                    throw new InterruptedIOException("Sink cancelled");
                }
                throw new IOException("Sink was not cancelled");
            }
        };
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    OfficeConvert.convert(doc, sink, OfficeConvert.Format.DOCX,
                            OfficeConvert.Settings.defaults().timeout(Duration.ofSeconds(20)));
                } catch (Throwable e) {
                    failure.set(e);
                    cleanedOnReturn.set(cleaned.get());
                    interruptedOnReturn.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                assertTrue(writing.await(10, TimeUnit.SECONDS));
                caller.interrupt();
                caller.join(10_000);
                assertFalse(caller.isAlive());
                assertInstanceOf(InterruptedIOException.class, failure.get());
                assertTrue(cleanedOnReturn.get(), "Caller must not close resources while the worker cleans up");
                assertTrue(interruptedOnReturn.get());
            } finally {
                caller.interrupt();
                caller.join(10_000);
            }
        }
    }
}
