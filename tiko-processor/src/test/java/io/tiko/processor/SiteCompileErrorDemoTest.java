package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Drift gate for the landing page: the "Compile-time safe" section of {@code site/index.html} shows two
 * source files and the error Tiko reports for them. This compiles exactly the page's sources and fails
 * when the processor's real output no longer matches what the page shows.
 */
class SiteCompileErrorDemoTest {

    /** Relative to the {@code tiko-processor} module directory. */
    private static final Path PAGE = Path.of("..", "site", "index.html");

    @Test
    void pageShowsTheErrorTikoActuallyReports() throws IOException {
        var page = Files.readString(PAGE);
        var orderService = SitePage.section(page, "order-service");
        var paymentGateway = SitePage.section(page, "payment-gateway");
        var shownError = SitePage.section(page, "compile-error");

        var compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString("demo.OrderService", orderService),
                        JavaFileObjects.forSourceString("demo.PaymentGateway", paymentGateway));

        var errors = compilation.errors();
        assertThat(errors)
                .as("the page's sample must produce exactly one error")
                .hasSize(1);
        var error = errors.get(0);
        var actual = "OrderService.java:" + error.getLineNumber() + ": error: " + error.getMessage(Locale.ROOT);

        assertThat(SitePage.normalize(shownError))
                .as("site/index.html shows a different compile error than Tiko reports for the page's own sample."
                        + " Update the sync:compile-error block to the actual output.")
                .isEqualTo(SitePage.normalize(actual));
    }
}
